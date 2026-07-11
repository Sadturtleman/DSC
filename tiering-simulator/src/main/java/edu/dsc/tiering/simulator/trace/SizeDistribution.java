package edu.dsc.tiering.simulator.trace;

import java.util.EnumMap;
import java.util.Map;

/** {@link SyntheticTraceGenerator}가 소/중/대 파일 크기를 뽑을 때 쓰는 분포. weight 합은 1.0. */
public final class SizeDistribution {

    private static final double TOLERANCE = 1e-9;

    private final Map<SizeCategory, SizeBucket> buckets;

    public SizeDistribution(SizeBucket small, SizeBucket medium, SizeBucket large) {
        double sum = small.weight() + medium.weight() + large.weight();
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException("SizeBucket weight 합이 1.0이어야 합니다: " + sum);
        }
        Map<SizeCategory, SizeBucket> m = new EnumMap<>(SizeCategory.class);
        m.put(SizeCategory.SMALL, small);
        m.put(SizeCategory.MEDIUM, medium);
        m.put(SizeCategory.LARGE, large);
        this.buckets = Map.copyOf(m);
    }

    public SizeBucket bucket(SizeCategory category) {
        return buckets.get(category);
    }

    /** 누적분포 기준 선택: draw는 [0,1). */
    public SizeCategory pick(double draw) {
        double cumulative = 0.0;
        for (SizeCategory category : SizeCategory.values()) {
            cumulative += buckets.get(category).weight();
            if (draw < cumulative) {
                return category;
            }
        }
        return SizeCategory.LARGE; // 부동소수 오차로 누적합이 draw보다 살짝 작을 때의 안전망
    }
}
