package edu.dsc.tiering.scoring;

import edu.dsc.tiering.model.Tier;
import edu.dsc.tiering.spi.ClusterInfo;
import edu.dsc.tiering.spi.Decision;
import edu.dsc.tiering.spi.FileState;
import edu.dsc.tiering.spi.ObservationMode;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.Placement;
import edu.dsc.tiering.spi.PlacementDecision;
import edu.dsc.tiering.spi.policy.RuleWeightedPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * tiering-policy-spi의 {@link RuleWeightedPolicy}(B1 어댑터)가 이 모듈의 실제
 * {@link PriorityRule}과 항상 같은 결정을 내리는지 증명하는 회귀 테스트.
 * <p>
 * 이 테스트만이 두 모듈(hdfs-auto-tiering, tiering-policy-spi)을 동시에 볼 수 있으므로
 * (의존 방향: hdfs-auto-tiering → tiering-policy-spi) 동등성 검증은 여기서만 가능하다.
 * {@code PriorityRule} 자체나 기존 {@code PriorityRuleTest}/{@code ScoringEngineTest}는
 * 건드리지 않는다(신규 파일).
 * <p>
 * 두 구현을 나란히 놓기 위해, {@link RuleWeightedPolicy}가 내부적으로 쓰는
 * Placement→Tier 매핑({@code RuleWeightedPolicy} 클래스 javadoc, 기존
 * {@code ScoringEngine.POLICY_TO_TIER}와 동일 취지)을 이 테스트에도 그대로 복제해
 * "같은 currentTier를 의미하는 입력"을 두 경로에 각각 준다.
 */
class RuleWeightedPolicyEquivalenceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final PriorityRule RULE = new PriorityRule(0.5, 0.5, NOW);
    private static final RuleWeightedPolicy POLICY = new RuleWeightedPolicy();

    /** PriorityRule.ACCESS_HORIZON_DAYS 기본값(90)과 맞춰 override 없이 순수 비교한다. */
    private static final int ACCESS_HORIZON_DAYS_MATCHING_DEFAULT = 90;

    @Test
    void recentFileStaysOnBothSides() {
        assertEquivalent("/recent", 1024L, Placement.ALL_SSD, NOW.minus(10, ChronoUnit.DAYS));
    }

    @Test
    void thirtyToNinetyDaysFromLogicalHotMovesToWarmOnBothSides() {
        assertEquivalent("/warm", 5L * 1024 * 1024 * 1024, Placement.ALL_SSD, NOW.minus(60, ChronoUnit.DAYS));
    }

    @Test
    void overNinetyDaysFromLogicalHotMovesToColdOnBothSides() {
        assertEquivalent("/cold", 10L * 1024 * 1024 * 1024, Placement.ALL_SSD, NOW.minus(120, ChronoUnit.DAYS));
    }

    @Test
    void alreadyColdStaysOnBothSides() {
        assertEquivalent("/already-cold", 1024L, Placement.COLD, NOW.minus(120, ChronoUnit.DAYS));
    }

    @Test
    void nativeHotPlacementTermCollisionAgreesOnBothSides() {
        // Placement.HOT(네이티브, 정책코드7) -> Tier.WARM. 30~90일 구간에서 WARM 승격은
        // currentTier==Tier.HOT일 때만 발동하므로, 여기서는 두 경로 모두 무이동이어야 한다.
        assertEquivalent("/native-hot", 1024L, Placement.HOT, NOW.minus(60, ChronoUnit.DAYS));
    }

    @Test
    void oneSsdPastNinetyDaysMovesToColdOnBothSides() {
        assertEquivalent("/one-ssd-stale", 1024L, Placement.ONE_SSD, NOW.minus(120, ChronoUnit.DAYS));
    }

    @Test
    void boundaryValuesAgreeOnBothSides() {
        // 경계값 스윕: 정확히 30일/90일, 정확히 10GiB, atime==observed_at, 미래 atime.
        long tenGiB = 10L * 1024 * 1024 * 1024;

        assertEquivalent("/b-30d", 1024L, Placement.ALL_SSD, NOW.minus(30, ChronoUnit.DAYS));
        assertEquivalent("/b-90d", 1024L, Placement.ALL_SSD, NOW.minus(90, ChronoUnit.DAYS));
        assertEquivalent("/b-90d-warm-start", 1024L, Placement.ONE_SSD, NOW.minus(90, ChronoUnit.DAYS));
        assertEquivalent("/b-10gib", tenGiB, Placement.ALL_SSD, NOW.minus(90, ChronoUnit.DAYS));
        assertEquivalent("/b-atime-eq-now", 1024L, Placement.ALL_SSD, NOW);
        assertEquivalent("/b-future-atime", 1024L, Placement.ALL_SSD, NOW.plus(5, ChronoUnit.DAYS));
    }

    @Test
    void sweepOverAllPlacementsAndAccessAgesAgree() {
        long[] sizes = {0L, 1024L, 5L * 1024 * 1024 * 1024, 10L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024};
        long[] daysAgo = {0, 1, 29, 30, 31, 89, 90, 91, 200};

        for (Placement placement : Placement.values()) {
            for (long size : sizes) {
                for (long days : daysAgo) {
                    String path = String.format("/sweep/%s/%d/%d", placement, size, days);
                    assertEquivalent(path, size, placement, NOW.minus(days, ChronoUnit.DAYS));
                }
            }
        }
    }

    // ── 핵심: 두 경로를 나란히 실행해 비교 ────────────────────────────────────

    private static void assertEquivalent(String path, long sizeBytes, Placement placement, Instant atime) {
        Tier currentTier = toLogicalTierMirroringAdapter(placement);
        FileMetadata meta = new FileMetadata(path, sizeBytes, atime.toEpochMilli(), atime.toEpochMilli(), 0);

        Tier expectedTargetTier = RULE.targetTier(meta, currentTier);
        double expectedScore = RULE.score(meta);

        Decision actual = decideOne(path, sizeBytes, placement, atime);

        if (expectedTargetTier == null) {
            assertNull(actual, () -> path + ": PriorityRule은 무이동인데 RuleWeightedPolicy는 이동 결정("
                    + actual + ")을 냈다");
            return;
        }

        assertNotNull(actual, () -> path + ": PriorityRule은 " + expectedTargetTier
                + "로 이동인데 RuleWeightedPolicy는 무이동이었다");
        assertEquals(tierToPlacement(expectedTargetTier), actual.targetPlacement(),
                () -> path + ": target_placement 불일치");
        assertEquals(clamp(expectedScore), actual.priority(), 1e-9,
                () -> path + ": priority(score) 불일치");
    }

    private static Decision decideOne(String path, long sizeBytes, Placement placement, Instant atime) {
        FileState file = new FileState(path, sizeBytes, placement, atime, atime, null);
        ObservationSnapshot snapshot = new ObservationSnapshot(
                "1.0", NOW, NOW, ObservationMode.FSIMAGE,
                new ClusterInfo(List.of()),
                List.of(file),
                List.of(),
                new Params(0.5, ACCESS_HORIZON_DAYS_MATCHING_DEFAULT));

        PlacementDecision decision = POLICY.decide(snapshot);
        Optional<Decision> found = decision.decisions().stream()
                .filter(d -> d.path().equals(path))
                .findFirst();
        return found.orElse(null);
    }

    /** RuleWeightedPolicy 내부의 Placement→Tier 매핑을 그대로 복제 (클래스 javadoc 참고). */
    private static Tier toLogicalTierMirroringAdapter(Placement placement) {
        switch (placement) {
            case ALL_SSD:
                return Tier.HOT;
            case ONE_SSD:
                return Tier.WARM;
            case HOT:   // 네이티브 Hot(DISK) — 용어 충돌: 논리 HOT이 아니다
            case WARM:  // 네이티브 Warm(DISK+ARCHIVE)
                return Tier.WARM;
            case COLD:
                return Tier.COLD;
            default:
                throw new IllegalArgumentException("Unknown Placement: " + placement);
        }
    }

    /** CLAUDE.md B1 행: HOT→ALL_SSD, WARM→ONE_SSD, COLD→COLD. */
    private static Placement tierToPlacement(Tier tier) {
        switch (tier) {
            case HOT:
                return Placement.ALL_SSD;
            case WARM:
                return Placement.ONE_SSD;
            case COLD:
                return Placement.COLD;
            default:
                throw new IllegalArgumentException("Unknown Tier: " + tier);
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
