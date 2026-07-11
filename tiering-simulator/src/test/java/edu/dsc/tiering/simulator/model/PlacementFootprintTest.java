package edu.dsc.tiering.simulator.model;

import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementFootprintTest {

    @Test
    void allSsdIsThreeSsdReplicas() {
        assertEquals(Map.of(PhysicalTier.SSD, 3), PlacementFootprint.replicaCounts(Placement.ALL_SSD));
    }

    @Test
    void oneSsdIsOneSsdTwoDisk() {
        assertEquals(Map.of(PhysicalTier.SSD, 1, PhysicalTier.DISK, 2),
                PlacementFootprint.replicaCounts(Placement.ONE_SSD));
    }

    @Test
    void hotIsThreeDiskReplicas() {
        assertEquals(Map.of(PhysicalTier.DISK, 3), PlacementFootprint.replicaCounts(Placement.HOT));
    }

    @Test
    void warmIsOneDiskTwoArchive() {
        assertEquals(Map.of(PhysicalTier.DISK, 1, PhysicalTier.ARCHIVE, 2),
                PlacementFootprint.replicaCounts(Placement.WARM));
    }

    @Test
    void coldIsThreeArchiveReplicas() {
        assertEquals(Map.of(PhysicalTier.ARCHIVE, 3), PlacementFootprint.replicaCounts(Placement.COLD));
    }

    @Test
    void occupiedBytesScalesByReplicaCount() {
        Map<PhysicalTier, Long> occupied = PlacementFootprint.occupiedBytes(Placement.ONE_SSD, 100L);
        assertEquals(Map.of(PhysicalTier.SSD, 100L, PhysicalTier.DISK, 200L), occupied);
    }
}
