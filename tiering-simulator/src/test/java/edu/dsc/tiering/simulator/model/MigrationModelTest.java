package edu.dsc.tiering.simulator.model;

import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationModelTest {

    @Test
    void sameToSameMovesNothing() {
        for (Placement p : Placement.values()) {
            assertEquals(0, MigrationModel.movedReplicaCount(p, p), p.toString());
        }
    }

    @Test
    void hotToOneSsdMovesOneReplica() {
        // CLAUDE.md 예시: HOT(DISK×3)→ONE_SSD(SSD×1,DISK×2) — DISK 2벌은 그대로, 1벌만 SSD로.
        assertEquals(1, MigrationModel.movedReplicaCount(Placement.HOT, Placement.ONE_SSD));
    }

    @Test
    void hotToColdMovesAllThreeReplicas() {
        // CLAUDE.md 예시: HOT(DISK×3)→COLD(ARCHIVE×3) — 겹치는 티어가 없어 전부 이동.
        assertEquals(3, MigrationModel.movedReplicaCount(Placement.HOT, Placement.COLD));
    }

    @Test
    void oneSsdToAllSsdMovesTwoReplicas() {
        // ONE_SSD(SSD×1,DISK×2)→ALL_SSD(SSD×3) — SSD 1벌은 그대로, DISK 2벌이 SSD로.
        assertEquals(2, MigrationModel.movedReplicaCount(Placement.ONE_SSD, Placement.ALL_SSD));
    }

    @Test
    void warmToColdMovesOneReplica() {
        // WARM(DISK×1,ARCHIVE×2)→COLD(ARCHIVE×3) — ARCHIVE 2벌은 그대로, DISK 1벌만 이동.
        assertEquals(1, MigrationModel.movedReplicaCount(Placement.WARM, Placement.COLD));
    }

    @Test
    void movedReplicaCountIsSymmetric() {
        for (Placement a : Placement.values()) {
            for (Placement b : Placement.values()) {
                assertEquals(MigrationModel.movedReplicaCount(a, b), MigrationModel.movedReplicaCount(b, a),
                        a + "<->" + b);
            }
        }
    }

    @Test
    void movedBytesScalesBySizeAndReplicaCount() {
        assertEquals(3_000_000_000L, MigrationModel.movedBytes(Placement.HOT, Placement.COLD, 1_000_000_000L));
        assertEquals(0L, MigrationModel.movedBytes(Placement.HOT, Placement.HOT, 1_000_000_000L));
    }
}
