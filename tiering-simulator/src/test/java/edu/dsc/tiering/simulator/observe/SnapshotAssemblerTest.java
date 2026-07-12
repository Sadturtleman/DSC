package edu.dsc.tiering.simulator.observe;

import edu.dsc.tiering.simulator.core.ClusterStateModel;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.simulator.trace.ReadEvent;
import edu.dsc.tiering.spi.FileState;
import edu.dsc.tiering.spi.ObservationMode;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotAssemblerTest {

    @Test
    void assembleProducesFsimageSnapshotWithNoStaleness() {
        Map<PhysicalTier, Long> capacity = new EnumMap<>(PhysicalTier.class);
        capacity.put(PhysicalTier.SSD, 100L);
        capacity.put(PhysicalTier.DISK, 100_000_000_000L);
        capacity.put(PhysicalTier.ARCHIVE, 100_000_000_000L);
        ClusterStateModel model = new ClusterStateModel(capacity);

        model.onEvent(0L, new FileCreateEvent(0L, "/a", 1000L));
        model.onEvent(5000L, new ReadEvent(5000L, "/a"));
        model.onEvent(1000L, new FileCreateEvent(1000L, "/b", 2000L));

        Params params = new Params(0.5, 90);
        SnapshotAssembler assembler = new SnapshotAssembler(model, params);

        ObservationSnapshot snapshot = assembler.assemble(9000L);

        assertEquals("1.0", snapshot.schemaVersion());
        assertEquals(Instant.ofEpochMilli(9000L), snapshot.observedAt());
        assertEquals(snapshot.observedAt(), snapshot.decidedAt()); // staleness 없음
        assertEquals(ObservationMode.FSIMAGE, snapshot.observationMode());
        assertTrue(snapshot.events().isEmpty());
        assertEquals(params, snapshot.params());

        assertEquals(2, snapshot.files().size());
        FileState a = findFile(snapshot, "/a");
        assertEquals(1000L, a.sizeBytes());
        assertEquals(Placement.HOT, a.currentPlacement());
        assertEquals(Instant.ofEpochMilli(5000L), a.atime()); // read로 갱신된 마지막 접근
        assertEquals(Instant.ofEpochMilli(0L), a.mtime()); // 생성 시각
        assertNull(a.accessStats());

        FileState b = findFile(snapshot, "/b");
        assertEquals(2000L, b.sizeBytes());
        assertEquals(Instant.ofEpochMilli(1000L), b.atime()); // read 없었으므로 생성 시각 그대로

        assertEquals(3, snapshot.cluster().tiers().size());
    }

    private static FileState findFile(ObservationSnapshot snapshot, String path) {
        Optional<FileState> found = snapshot.files().stream().filter(f -> f.path().equals(path)).findFirst();
        assertTrue(found.isPresent(), path + " not found in snapshot");
        return found.get();
    }
}
