package edu.dsc.tiering.simulator.config;

import edu.dsc.tiering.simulator.model.PhysicalTier;

import java.util.Map;

/**
 * {@code sim-constants.yaml}에서 로드한 시뮬레이터 상수. CLAUDE.md "시뮬레이터" 절의 키
 * 구조 그대로 + {@code tier_capacity_bytes}(ClusterStateModel 용량 로딩용, 원본 스키마
 * 확장 — {@link SimConstantsLoader} javadoc 참고).
 */
public final class SimConstants {

    private final Map<PhysicalTier, Long> throughputBytesPerSec;
    private final long migrationBandwidthBytesPerSec;
    private final long rateLimitBytesPerSec;
    private final Map<PhysicalTier, Double> pricePerGbHour;
    private final double migrationPricePerGb;
    private final Map<PhysicalTier, Long> tierCapacityBytes;

    public SimConstants(Map<PhysicalTier, Long> throughputBytesPerSec,
                         long migrationBandwidthBytesPerSec,
                         long rateLimitBytesPerSec,
                         Map<PhysicalTier, Double> pricePerGbHour,
                         double migrationPricePerGb,
                         Map<PhysicalTier, Long> tierCapacityBytes) {
        this.throughputBytesPerSec = Map.copyOf(throughputBytesPerSec);
        this.migrationBandwidthBytesPerSec = migrationBandwidthBytesPerSec;
        this.rateLimitBytesPerSec = rateLimitBytesPerSec;
        this.pricePerGbHour = Map.copyOf(pricePerGbHour);
        this.migrationPricePerGb = migrationPricePerGb;
        this.tierCapacityBytes = Map.copyOf(tierCapacityBytes);
    }

    public long throughputBytesPerSec(PhysicalTier tier) {
        return throughputBytesPerSec.get(tier);
    }

    public long migrationBandwidthBytesPerSec() {
        return migrationBandwidthBytesPerSec;
    }

    public long rateLimitBytesPerSec() {
        return rateLimitBytesPerSec;
    }

    public double pricePerGbHour(PhysicalTier tier) {
        return pricePerGbHour.get(tier);
    }

    public double migrationPricePerGb() {
        return migrationPricePerGb;
    }

    public long tierCapacityBytes(PhysicalTier tier) {
        return tierCapacityBytes.get(tier);
    }

    public Map<PhysicalTier, Long> tierCapacityBytes() {
        return tierCapacityBytes;
    }
}
