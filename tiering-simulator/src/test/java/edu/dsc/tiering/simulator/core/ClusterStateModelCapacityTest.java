package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterStateModelCapacityTest {

    /** SSD 300바이트, DISK 10^12(사실상 무제한), ARCHIVE 10^12. */
    private static ClusterStateModel modelWithTightSsd() {
        Map<PhysicalTier, Long> capacity = new EnumMap<>(PhysicalTier.class);
        capacity.put(PhysicalTier.SSD, 300L);
        capacity.put(PhysicalTier.DISK, 1_000_000_000_000L);
        capacity.put(PhysicalTier.ARCHIVE, 1_000_000_000_000L);
        return new ClusterStateModel(capacity);
    }

    @Test
    void placementChangeAcceptedWhenWithinCapacity() {
        ClusterStateModel model = modelWithTightSsd();
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 100L)); // HOT(DISK×3)에서 시작

        PlacementChangeResult result = model.requestPlacementChange("/a", Placement.ALL_SSD); // SSD×3 = 300

        assertEquals(PlacementChangeResult.ACCEPTED, result);
        assertEquals(Placement.ALL_SSD, model.fileState("/a").placement());
        assertEquals(300L, model.usedBytes(PhysicalTier.SSD));
        assertEquals(0L, model.usedBytes(PhysicalTier.DISK)); // HOT 점유(300)가 전부 반환됨
        assertEquals(0L, model.rejectionCount());
    }

    @Test
    void placementChangeRejectedWhenExceedingCapacity() {
        ClusterStateModel model = modelWithTightSsd();
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 100L)); // HOT(DISK×3)
        model.onEvent(0L, new FileCreateEvent(0L, "/b", 1L));
        assertEquals(PlacementChangeResult.ACCEPTED, model.requestPlacementChange("/a", Placement.ALL_SSD)); // SSD 300/300 소진

        // /b(1바이트)를 ALL_SSD로 옮기면 SSD 3바이트가 더 필요한데 잔여 용량이 0 — 거부되어야 함
        PlacementChangeResult result = model.requestPlacementChange("/b", Placement.ALL_SSD);

        assertEquals(PlacementChangeResult.REJECTED_CAPACITY, result);
        assertEquals(Placement.HOT, model.fileState("/b").placement()); // 변경되지 않음(원자적 거부)
        assertEquals(300L, model.usedBytes(PhysicalTier.SSD)); // 그대로
        assertEquals(1L, model.rejectionCount());
    }

    @Test
    void rejectedChangeDoesNotPartiallyApply() {
        // ONE_SSD = SSD 1벌 + DISK 2벌. SSD가 꽉 차 있으면 DISK 쪽도 전혀 반영되지 않아야 한다.
        ClusterStateModel model = modelWithTightSsd();
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 300L)); // HOT: DISK 900
        // ALL_SSD로 옮기면 SSD 900바이트가 필요한데 정원은 300 -> 거부되어야 함
        assertEquals(PlacementChangeResult.REJECTED_CAPACITY, model.requestPlacementChange("/a", Placement.ALL_SSD));

        assertEquals(Placement.HOT, model.fileState("/a").placement());
        assertEquals(0L, model.usedBytes(PhysicalTier.SSD));
        assertEquals(900L, model.usedBytes(PhysicalTier.DISK)); // 원래 HOT 점유 그대로
    }

    @Test
    void unknownPathIsRejectedAndCounted() {
        ClusterStateModel model = modelWithTightSsd();

        PlacementChangeResult result = model.requestPlacementChange("/does-not-exist", Placement.COLD);

        assertEquals(PlacementChangeResult.REJECTED_UNKNOWN_FILE, result);
        assertNull(model.fileState("/does-not-exist"));
        assertEquals(1L, model.rejectionCount());
    }

    @Test
    void freeingCapacityAllowsSubsequentAccept() {
        ClusterStateModel model = modelWithTightSsd();
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 100L));
        model.onEvent(0L, new FileCreateEvent(0L, "/b", 100L));

        assertEquals(PlacementChangeResult.ACCEPTED, model.requestPlacementChange("/a", Placement.ALL_SSD)); // SSD 300/300
        assertEquals(PlacementChangeResult.REJECTED_CAPACITY, model.requestPlacementChange("/b", Placement.ALL_SSD));

        assertEquals(PlacementChangeResult.ACCEPTED, model.requestPlacementChange("/a", Placement.HOT)); // SSD 반환
        assertEquals(0L, model.usedBytes(PhysicalTier.SSD));
        assertEquals(PlacementChangeResult.ACCEPTED, model.requestPlacementChange("/b", Placement.ALL_SSD)); // 이제 여유 있음

        assertEquals(1L, model.rejectionCount()); // 그 전에 거부됐던 카운트는 유지(감소하지 않음)
    }
}
