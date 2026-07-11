package edu.dsc.tiering.simulator.model;

import edu.dsc.tiering.spi.Placement;

import java.util.EnumMap;
import java.util.Map;

/**
 * CLAUDE.md "ClusterStateModel" 절의 복제본 배치 표를 코드로 옮긴 것 (복제본 3 고정):
 * <pre>
 *   ALL_SSD  = SSD 3벌
 *   ONE_SSD  = SSD 1벌 + DISK 2벌
 *   HOT      = DISK 3벌
 *   WARM     = DISK 1벌 + ARCHIVE 2벌
 *   COLD     = ARCHIVE 3벌
 * </pre>
 */
public final class PlacementFootprint {

    private static final Map<Placement, Map<PhysicalTier, Integer>> REPLICA_COUNTS = buildReplicaCounts();

    private PlacementFootprint() {
    }

    /** Placement 하나가 물리 티어별로 차지하는 복제본 개수. */
    public static Map<PhysicalTier, Integer> replicaCounts(Placement placement) {
        return REPLICA_COUNTS.get(placement);
    }

    /** 파일 크기(sizeBytes) 기준, Placement가 물리 티어별로 실제 점유하는 바이트. */
    public static Map<PhysicalTier, Long> occupiedBytes(Placement placement, long sizeBytes) {
        Map<PhysicalTier, Integer> counts = replicaCounts(placement);
        Map<PhysicalTier, Long> bytes = new EnumMap<>(PhysicalTier.class);
        for (Map.Entry<PhysicalTier, Integer> e : counts.entrySet()) {
            bytes.put(e.getKey(), sizeBytes * e.getValue());
        }
        return bytes;
    }

    private static Map<Placement, Map<PhysicalTier, Integer>> buildReplicaCounts() {
        Map<Placement, Map<PhysicalTier, Integer>> m = new EnumMap<>(Placement.class);
        m.put(Placement.ALL_SSD, mapOf(PhysicalTier.SSD, 3));
        m.put(Placement.ONE_SSD, mapOf(PhysicalTier.SSD, 1, PhysicalTier.DISK, 2));
        m.put(Placement.HOT, mapOf(PhysicalTier.DISK, 3));
        m.put(Placement.WARM, mapOf(PhysicalTier.DISK, 1, PhysicalTier.ARCHIVE, 2));
        m.put(Placement.COLD, mapOf(PhysicalTier.ARCHIVE, 3));
        return Map.copyOf(m);
    }

    private static Map<PhysicalTier, Integer> mapOf(PhysicalTier t1, int c1) {
        Map<PhysicalTier, Integer> m = new EnumMap<>(PhysicalTier.class);
        m.put(t1, c1);
        return Map.copyOf(m);
    }

    private static Map<PhysicalTier, Integer> mapOf(PhysicalTier t1, int c1, PhysicalTier t2, int c2) {
        Map<PhysicalTier, Integer> m = new EnumMap<>(PhysicalTier.class);
        m.put(t1, c1);
        m.put(t2, c2);
        return Map.copyOf(m);
    }
}
