package edu.dsc.tiering.simulator.trace;

import java.util.EnumMap;
import java.util.Map;

/** hot/decaying/cold 그룹 혼합 비율. 합은 1.0이어야 한다. */
public final class AccessPatternMix {

    private static final double TOLERANCE = 1e-9;

    private final Map<AccessGroup, Double> fractions;

    public AccessPatternMix(double hotFraction, double decayingFraction, double coldFraction) {
        double sum = hotFraction + decayingFraction + coldFraction;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException("AccessPatternMix 비율 합이 1.0이어야 합니다: " + sum);
        }
        Map<AccessGroup, Double> m = new EnumMap<>(AccessGroup.class);
        m.put(AccessGroup.HOT, hotFraction);
        m.put(AccessGroup.DECAYING, decayingFraction);
        m.put(AccessGroup.COLD, coldFraction);
        this.fractions = Map.copyOf(m);
    }

    public double fraction(AccessGroup group) {
        return fractions.get(group);
    }

    /** 누적분포 기준 선택: draw는 [0,1). */
    public AccessGroup pick(double draw) {
        double cumulative = 0.0;
        for (AccessGroup group : AccessGroup.values()) {
            cumulative += fractions.get(group);
            if (draw < cumulative) {
                return group;
            }
        }
        return AccessGroup.COLD; // 부동소수 오차 안전망
    }
}
