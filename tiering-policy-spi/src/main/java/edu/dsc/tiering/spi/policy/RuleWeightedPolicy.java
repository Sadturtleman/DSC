package edu.dsc.tiering.spi.policy;

import edu.dsc.tiering.spi.Decision;
import edu.dsc.tiering.spi.FileState;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.Placement;
import edu.dsc.tiering.spi.PlacementDecision;
import edu.dsc.tiering.spi.TieringPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B1 baseline: {@code hdfs-auto-tiering}의 {@code edu.dsc.tiering.scoring.PriorityRule}을
 * 감싸는 어댑터.
 * <p>
 * {@code tiering-policy-spi}는 {@code hdfs-auto-tiering}에 의존할 수 없으므로(역방향 의존
 * 금지, CLAUDE.md "모듈 구성" 참고) {@code PriorityRule}을 import하는 대신 그 점수식·티어
 * 결정 로직을 상수 단위까지 동일하게 재구현한다. 두 구현의 결과가 항상 일치함은
 * {@code hdfs-auto-tiering}의 {@code RuleWeightedPolicyEquivalenceTest}(신규 테스트,
 * 실제 {@code PriorityRule} 직접 호출과 비교)로 증명한다.
 * <p>
 * <b>용어 충돌 주의(CLAUDE.md):</b> 입력 {@link FileState#currentPlacement()}는 SPI의
 * {@link Placement} 5종이며, 이 중 {@code Placement.HOT}은 HDFS 네이티브 "Hot" 정책
 * (정책 코드 7, DISK만)이지 논리 {@code Tier.HOT}이 아니다. 기존
 * {@code ScoringEngine.POLICY_TO_TIER}가 정책 코드 7을 {@code Tier.WARM}으로 보수적
 * 매핑하는 것과 동일하게, 이 클래스도 {@code Placement.HOT}/{@code Placement.WARM}(네이티브)
 * 을 둘 다 내부 {@link LogicalTier#WARM}으로 취급한다. 논리 HOT에 대응하는 Placement는
 * {@code ALL_SSD}뿐이다.
 */
public final class RuleWeightedPolicy implements TieringPolicy {

    public static final String POLICY_ID = "B1-rule-weighted";

    /** PriorityRule.ACCESS_HORIZON_DAYS 기본값 — params.access_horizon_days가 없거나 0 이하면 사용. */
    private static final double DEFAULT_ACCESS_HORIZON_DAYS = 90.0;

    /** PriorityRule.SIZE_REFERENCE_BYTES와 동일 (10 GiB). */
    private static final double SIZE_REFERENCE_BYTES = 10.0 * 1024 * 1024 * 1024;

    /** PriorityRule.HOT_TO_WARM_DAYS / WARM_TO_COLD_DAYS와 동일. override 대상 아님. */
    private static final long HOT_TO_WARM_DAYS = 30;
    private static final long WARM_TO_COLD_DAYS = 90;

    /** PriorityRule 기본 가중치(AppConfig.Scoring 기본값과 동일). B1은 이 값을 고정한다. */
    private static final double WEIGHT_ACCESS_TIME = 0.5;
    private static final double WEIGHT_FILE_SIZE = 0.5;

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public PlacementDecision decide(ObservationSnapshot snapshot) {
        Instant now = snapshot.observedAt();
        double accessHorizonDays = resolveAccessHorizonDays(snapshot.params());

        List<Decision> decisions = new ArrayList<>();
        for (FileState file : snapshot.files()) {
            LogicalTier currentTier = toLogicalTier(file.currentPlacement());
            long daysSinceAccess = daysSince(file.atime(), now);

            LogicalTier targetTier = targetTier(daysSinceAccess, currentTier);
            if (targetTier == null) {
                continue; // 이동 불필요
            }

            double score = score(daysSinceAccess, file.sizeBytes(), accessHorizonDays);
            decisions.add(new Decision(
                    file.path(),
                    toPlacement(targetTier),
                    clamp(score),
                    String.format(Locale.ROOT, "score=%.2f", score)));
        }

        return new PlacementDecision("1.0", POLICY_ID, decisions);
    }

    // ── PriorityRule.targetTier 재구현 ──────────────────────────────────────

    private static LogicalTier targetTier(long daysSinceAccess, LogicalTier currentTier) {
        if (daysSinceAccess < HOT_TO_WARM_DAYS) {
            return null;
        }
        if (daysSinceAccess < WARM_TO_COLD_DAYS) {
            return (currentTier == LogicalTier.HOT) ? LogicalTier.WARM : null;
        }
        return (currentTier != LogicalTier.COLD) ? LogicalTier.COLD : null;
    }

    // ── PriorityRule.score 재구현 ────────────────────────────────────────────

    private static double score(long daysSinceAccess, long sizeBytes, double accessHorizonDays) {
        double accessRecency = clamp(daysSinceAccess / accessHorizonDays);
        double sizeScore = clamp((double) sizeBytes / SIZE_REFERENCE_BYTES);
        return WEIGHT_ACCESS_TIME * accessRecency + WEIGHT_FILE_SIZE * sizeScore;
    }

    private static double resolveAccessHorizonDays(Params params) {
        if (params == null || params.accessHorizonDays() <= 0) {
            return DEFAULT_ACCESS_HORIZON_DAYS;
        }
        return params.accessHorizonDays();
    }

    // ── PriorityRule.daysSince 재구현 ────────────────────────────────────────

    private static long daysSince(Instant atime, Instant now) {
        if (atime == null) {
            return Long.MAX_VALUE / 2; // atime 없음 — PriorityRule의 epochMs==0 처리와 동일 취지
        }
        return Duration.between(atime, now).toDays();
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── Placement ↔ 내부 논리 Tier 매핑 ──────────────────────────────────────
    // edu.dsc.tiering.model.Tier를 import할 수 없으므로(역방향 의존 금지) 이 열거형은
    // 어댑터 내부 전용 재구현이다. SPI 밖으로 노출하지 않는다.

    private enum LogicalTier { HOT, WARM, COLD }

    /** 기존 ScoringEngine.POLICY_TO_TIER를 Placement 기준으로 옮긴 것. 클래스 javadoc 참고. */
    private static LogicalTier toLogicalTier(Placement placement) {
        switch (placement) {
            case ALL_SSD:
                return LogicalTier.HOT;
            case ONE_SSD:
                return LogicalTier.WARM;
            case HOT:   // 네이티브 Hot(DISK) — 용어 충돌: 논리 HOT이 아니다
            case WARM:  // 네이티브 Warm(DISK+ARCHIVE)
                return LogicalTier.WARM;
            case COLD:
                return LogicalTier.COLD;
            default:
                throw new IllegalArgumentException("Unknown Placement: " + placement);
        }
    }

    /** CLAUDE.md "정책 라인업" B1 행: HOT→ALL_SSD, WARM→ONE_SSD, COLD→COLD. */
    private static Placement toPlacement(LogicalTier tier) {
        switch (tier) {
            case HOT:
                return Placement.ALL_SSD;
            case WARM:
                return Placement.ONE_SSD;
            case COLD:
                return Placement.COLD;
            default:
                throw new IllegalArgumentException("Unknown LogicalTier: " + tier);
        }
    }
}
