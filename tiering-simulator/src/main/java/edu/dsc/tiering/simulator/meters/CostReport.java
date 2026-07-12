package edu.dsc.tiering.simulator.meters;

import edu.dsc.tiering.simulator.model.PhysicalTier;

import java.util.Map;

/** {@link CostMeter#finalizeReport}의 산출물: 총비용, 티어별 비용 분해, 이관 바이트/비용, 거부 횟수. */
public final class CostReport {

    private final Map<PhysicalTier, Double> costByTier;
    private final long migratedBytes;
    private final double migrationCost;
    private final long rejectionCount;

    public CostReport(Map<PhysicalTier, Double> costByTier, long migratedBytes,
                       double migrationCost, long rejectionCount) {
        this.costByTier = Map.copyOf(costByTier);
        this.migratedBytes = migratedBytes;
        this.migrationCost = migrationCost;
        this.rejectionCount = rejectionCount;
    }

    /** 티어별 저장 비용 합 + 이관 비용. */
    public double totalCost() {
        double storageCost = costByTier.values().stream().mapToDouble(Double::doubleValue).sum();
        return storageCost + migrationCost;
    }

    public double costByTier(PhysicalTier tier) {
        return costByTier.getOrDefault(tier, 0.0);
    }

    public Map<PhysicalTier, Double> costByTier() {
        return costByTier;
    }

    public long migratedBytes() {
        return migratedBytes;
    }

    public double migrationCost() {
        return migrationCost;
    }

    public long rejectionCount() {
        return rejectionCount;
    }

    @Override
    public String toString() {
        return "CostReport{total=" + totalCost() + ", costByTier=" + costByTier
                + ", migratedBytes=" + migratedBytes + ", migrationCost=" + migrationCost
                + ", rejectionCount=" + rejectionCount + '}';
    }
}
