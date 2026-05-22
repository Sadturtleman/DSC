package edu.dsc.tiering.scoring;

import edu.dsc.tiering.model.Tier;

import java.time.Duration;
import java.time.Instant;

/**
 * 파일 메타데이터로부터 티어 이동 우선순위 점수를 계산하고,
 * 현재 티어 → 목표 티어를 결정한다.
 *
 * <h2>우선순위 공식</h2>
 * <pre>
 *   priority_score = w_access × (1 − accessRecency) + w_size × sizeScore
 *
 *   accessRecency = clamp(daysSinceAccess / ACCESS_HORIZON_DAYS, 0, 1)
 *                   → 0이면 최근 접근 (HOT 유지), 1이면 오래된 파일
 *   sizeScore     = clamp(fileSizeBytes / SIZE_REFERENCE_BYTES, 0, 1)
 *                   → 클수록 이동 비용 대비 효율이 높아 우선 처리
 * </pre>
 *
 * <p>점수가 높을수록 "빨리 COLD로 내려야 할" 파일이다.
 *
 * <h2>티어 결정 규칙</h2>
 * <ul>
 *   <li>30일 이내 접근 → HOT 유지 (이동 대상 아님)</li>
 *   <li>30일~90일      → WARM 이동 (HOT에 있을 경우)</li>
 *   <li>90일 초과      → COLD 이동 (HOT/WARM에 있을 경우)</li>
 * </ul>
 */
public class PriorityRule {

    /** 접근 기간 기준선: 이 일수를 초과하면 accessRecency = 1.0 */
    private static final double ACCESS_HORIZON_DAYS = 90.0;

    /** 파일 크기 기준선: 이 크기 이상이면 sizeScore = 1.0 (기본 10 GB) */
    private static final double SIZE_REFERENCE_BYTES = 10.0 * 1024 * 1024 * 1024;

    private static final long HOT_TO_WARM_DAYS = 30;
    private static final long WARM_TO_COLD_DAYS = 90;

    private final double weightAccessTime;
    private final double weightFileSize;
    private final Instant now;

    /**
     * @param weightAccessTime 접근 시간 가중치 (YAML: weight-access-time)
     * @param weightFileSize   파일 크기 가중치 (YAML: weight-file-size)
     */
    public PriorityRule(double weightAccessTime, double weightFileSize) {
        this(weightAccessTime, weightFileSize, Instant.now());
    }

    /** 테스트용 — 현재 시각 주입 가능 */
    PriorityRule(double weightAccessTime, double weightFileSize, Instant now) {
        if (Math.abs(weightAccessTime + weightFileSize - 1.0) > 1e-9) {
            throw new IllegalArgumentException(
                    "가중치 합이 1.0 이어야 합니다: " + (weightAccessTime + weightFileSize));
        }
        this.weightAccessTime = weightAccessTime;
        this.weightFileSize   = weightFileSize;
        this.now              = now;
    }

    // ── 공개 API ─────────────────────────────────────────────────────────

    /**
     * 파일의 목표 티어를 결정한다.
     * 이미 올바른 티어에 있거나 이동이 불필요하면 {@code null}을 반환한다.
     *
     * @param meta        FSImage에서 추출한 파일 메타데이터
     * @param currentTier 현재 스토리지 정책으로 매핑된 티어
     * @return 이동 목표 티어, 또는 {@code null} (이동 불필요)
     */
    public Tier targetTier(FileMetadata meta, Tier currentTier) {
        long daysSinceAccess = daysSince(meta.accessTimeMs());

        if (daysSinceAccess < HOT_TO_WARM_DAYS) {
            // 최근 접근 파일 — 현재 위치 유지
            return null;
        }

        if (daysSinceAccess < WARM_TO_COLD_DAYS) {
            // WARM이 목표: HOT에 있는 파일만 이동 대상
            return (currentTier == Tier.HOT) ? Tier.WARM : null;
        }

        // COLD가 목표: HOT 또는 WARM에 있는 파일만 이동 대상
        return (currentTier != Tier.COLD) ? Tier.COLD : null;
    }

    /**
     * 우선순위 점수를 계산한다. 높을수록 먼저 처리된다.
     *
     * @param meta FSImage에서 추출한 파일 메타데이터
     * @return [0.0, 1.0] 범위의 점수
     */
    public double score(FileMetadata meta) {
        double daysSince    = daysSince(meta.accessTimeMs());
        double accessRecency = clamp(daysSince / ACCESS_HORIZON_DAYS);
        double sizeScore     = clamp((double) meta.fileSizeBytes() / SIZE_REFERENCE_BYTES);

        // accessRecency가 클수록(=오래된 파일) 점수가 높아야 하므로 (1 - accessRecency) 반전
        // → 오래되고 큰 파일이 가장 높은 점수
        return weightAccessTime * (1.0 - accessRecency) + weightFileSize * sizeScore;

        // 주의: 위 식은 "HOT→COLD 이동 우선순위"를 나타낸다.
        // accessRecency = 0 (최신 파일) → weightAccessTime × 1.0 (점수 ↑ = 빨리 HOT에 올려야)
        // accessRecency = 1 (구 파일)   → weightAccessTime × 0.0 (접근시간 기여 없음)
        //
        // 실제 COLD 이동 우선순위 의미: 오래되고 클수록 COLD로 먼저 보내는 것이므로,
        // 접근 기여를 (accessRecency)로 바꾸면 의미가 명확해진다.
        // 팀 논의 후 weight 방향 조정 가능 (미해결 항목 §5).
    }

    // ── 내부 유틸 ──────────────────────────────────────────────────────

    private long daysSince(long epochMs) {
        if (epochMs == 0) return Long.MAX_VALUE / 2; // atime 비활성화 시 최대값 처리
        return Duration.between(Instant.ofEpochMilli(epochMs), now).toDays();
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}