package edu.dsc.tiering.simulator.model;

import edu.dsc.tiering.spi.Placement;

import java.util.Map;

/**
 * Placement 쌍(from→to)이 몇 개의 복제본을 다른 물리 티어로 옮기는지 계산한다.
 * 복제본은 항상 3개 고정이므로, "양쪽 Placement에서 같은 물리 티어에 남아있는 복제본 수"
 * (겹치는 티어별 min)를 구해 3에서 빼면 "이동한 복제본 수"가 된다.
 * <p>
 * 예시(CLAUDE.md): HOT({DISK:3})→ONE_SSD({SSD:1,DISK:2}) — 겹침은 DISK 2벌(min(3,2))뿐이라
 * 이동 = 3-2 = 1. HOT→COLD({ARCHIVE:3}) — 겹치는 티어가 없어 이동 = 3-0 = 3.
 */
public final class MigrationModel {

    private static final int REPLICA_COUNT = 3;

    private MigrationModel() {
    }

    public static int movedReplicaCount(Placement from, Placement to) {
        Map<PhysicalTier, Integer> before = PlacementFootprint.replicaCounts(from);
        Map<PhysicalTier, Integer> after = PlacementFootprint.replicaCounts(to);

        int unchanged = 0;
        for (PhysicalTier tier : PhysicalTier.values()) {
            unchanged += Math.min(before.getOrDefault(tier, 0), after.getOrDefault(tier, 0));
        }
        return REPLICA_COUNT - unchanged;
    }

    public static long movedBytes(Placement from, Placement to, long sizeBytes) {
        return (long) movedReplicaCount(from, to) * sizeBytes;
    }
}
