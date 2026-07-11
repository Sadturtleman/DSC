package edu.dsc.tiering.spi.policy;

import edu.dsc.tiering.spi.ClusterInfo;
import edu.dsc.tiering.spi.Decision;
import edu.dsc.tiering.spi.FileState;
import edu.dsc.tiering.spi.ObservationMode;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.Placement;
import edu.dsc.tiering.spi.PlacementDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 어댑터의 경계값·용어 충돌 케이스 단위 테스트. hdfs-auto-tiering의 실제
 * {@code PriorityRule}과의 동등성 자체는 hdfs-auto-tiering 모듈의
 * {@code RuleWeightedPolicyEquivalenceTest}가 증명한다 — 이 클래스는 그 전제가 되는
 * SPI 입출력 변환(Placement↔Tier, params override)만 독립적으로 검증한다.
 */
class RuleWeightedPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final RuleWeightedPolicy POLICY = new RuleWeightedPolicy();

    @Test
    void policyIdMatchesSpec() {
        assertEquals("B1-rule-weighted", POLICY.policyId());
    }

    @Test
    void recentFileStaysInPlace() {
        FileState f = file("/recent", oneGiB(), Placement.ALL_SSD, NOW.minus(10, ChronoUnit.DAYS));

        assertNoDecision(decide(f, 90), "/recent");
    }

    @Test
    void exactlyAtimeEqualsObservedAtStays() {
        FileState f = file("/just-seen", oneGiB(), Placement.ALL_SSD, NOW);

        assertNoDecision(decide(f, 90), "/just-seen");
    }

    @Test
    void futureAtimeStays() {
        FileState f = file("/future", oneGiB(), Placement.ALL_SSD, NOW.plus(5, ChronoUnit.DAYS));

        assertNoDecision(decide(f, 90), "/future");
    }

    @Test
    void exactlyThirtyDaysFromHotMovesToWarm() {
        FileState f = file("/boundary-30", oneGiB(), Placement.ALL_SSD, NOW.minus(30, ChronoUnit.DAYS));

        Decision d = decisionFor(decide(f, 90), "/boundary-30");
        assertEquals(Placement.ONE_SSD, d.targetPlacement());
    }

    @Test
    void exactlyNinetyDaysMovesStraightToColdNotWarm() {
        FileState f = file("/boundary-90", oneGiB(), Placement.ALL_SSD, NOW.minus(90, ChronoUnit.DAYS));

        Decision d = decisionFor(decide(f, 90), "/boundary-90");
        assertEquals(Placement.COLD, d.targetPlacement());
    }

    @Test
    void exactlyTenGibSizeClampsToOne() {
        long tenGiB = 10L * 1024 * 1024 * 1024;
        FileState f = file("/ten-gib", tenGiB, Placement.ALL_SSD, NOW.minus(90, ChronoUnit.DAYS));

        Decision d = decisionFor(decide(f, 90), "/ten-gib");
        assertEquals(1.0, d.priority(), 1e-9);
    }

    @Test
    void nativeHotPlacementIsTermCollisionNotLogicalHot() {
        // Placement.HOT (네이티브 Hot, 정책 코드 7) -> 내부 Tier.WARM.
        // 30~90일 구간에서 WARM 이동은 currentTier==HOT일 때만 발동하므로 무이동이어야 한다.
        FileState f = file("/native-hot", oneGiB(), Placement.HOT, NOW.minus(60, ChronoUnit.DAYS));

        assertNoDecision(decide(f, 90), "/native-hot");
    }

    @Test
    void nativeWarmPlacementAlsoMapsToInternalWarm() {
        FileState f = file("/native-warm", oneGiB(), Placement.WARM, NOW.minus(60, ChronoUnit.DAYS));

        assertNoDecision(decide(f, 90), "/native-warm");
    }

    @Test
    void oneSsdPastNinetyDaysMovesToCold() {
        // ONE_SSD -> 내부 Tier.WARM (!= COLD) 이므로 90일 초과 시 COLD로 이동해야 한다.
        FileState f = file("/one-ssd-stale", oneGiB(), Placement.ONE_SSD, NOW.minus(120, ChronoUnit.DAYS));

        Decision d = decisionFor(decide(f, 90), "/one-ssd-stale");
        assertEquals(Placement.COLD, d.targetPlacement());
    }

    @Test
    void alreadyColdStays() {
        FileState f = file("/already-cold", oneGiB(), Placement.COLD, NOW.minus(120, ChronoUnit.DAYS));

        assertNoDecision(decide(f, 90), "/already-cold");
    }

    @Test
    void accessHorizonOverrideChangesScoreButNotTargetTier() {
        FileState f = file("/override", oneGiB(), Placement.ALL_SSD, NOW.minus(45, ChronoUnit.DAYS));

        Decision withDefault = decisionFor(decide(f, 90), "/override");
        Decision withOverride = decisionFor(decide(f, 30), "/override");

        assertEquals(Placement.ONE_SSD, withDefault.targetPlacement());
        assertEquals(Placement.ONE_SSD, withOverride.targetPlacement());
        assertTrue(withOverride.priority() > withDefault.priority(),
                "45/30(clamped to 1.0) 이 45/90(0.5) 보다 accessRecency가 커야 한다");
    }

    @Test
    void zeroAccessHorizonFallsBackToDefaultNinety() {
        FileState f = file("/zero-horizon", oneGiB(), Placement.ALL_SSD, NOW.minus(45, ChronoUnit.DAYS));

        Decision withZero = decisionFor(decide(f, 0), "/zero-horizon");
        Decision withNinety = decisionFor(decide(f, 90), "/zero-horizon");

        assertEquals(withNinety.priority(), withZero.priority(), 1e-9);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static PlacementDecision decide(FileState file, int accessHorizonDays) {
        ObservationSnapshot snapshot = new ObservationSnapshot(
                "1.0", NOW, NOW, ObservationMode.FSIMAGE,
                new ClusterInfo(List.of()),
                List.of(file),
                List.of(),
                new Params(0.5, accessHorizonDays));
        return POLICY.decide(snapshot);
    }

    private static FileState file(String path, long sizeBytes, Placement placement, Instant atime) {
        return new FileState(path, sizeBytes, placement, atime, atime, null);
    }

    private static long oneGiB() {
        return 1024L * 1024 * 1024;
    }

    private static void assertNoDecision(PlacementDecision decision, String path) {
        assertFalse(findDecision(decision, path).isPresent(),
                () -> path + "에 대한 결정이 없어야 하는데 존재함: " + decision.decisions());
    }

    private static Decision decisionFor(PlacementDecision decision, String path) {
        return findDecision(decision, path)
                .orElseThrow(() -> new AssertionError(path + "에 대한 결정이 있어야 하는데 없음"));
    }

    private static Optional<Decision> findDecision(PlacementDecision decision, String path) {
        return decision.decisions().stream().filter(d -> d.path().equals(path)).findFirst();
    }
}
