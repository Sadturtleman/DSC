package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.TraceEvent;
import edu.dsc.tiering.simulator.trace.TraceReader;
import edu.dsc.tiering.spi.Placement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * fixtures/mini-trace-01을 SimulationDriver + ClusterStateModel로 재생해 최종 상태를
 * 손계산 기대값(README.md)과 대조한다.
 */
class ClusterStateModelReplayTest {

    private static final Map<PhysicalTier, Long> GENEROUS_CAPACITY = capacities(
            100_000_000_000L, 100_000_000_000L, 100_000_000_000L);

    @Test
    void replayProducesHandCalculatedFinalState() throws IOException {
        List<TraceEvent> events = loadFixture();
        ClusterStateModel model = new ClusterStateModel(GENEROUS_CAPACITY);

        SimulationDriver driver = new SimulationDriver(events.iterator(), 0L, 20_000L);
        driver.addListener(model);
        driver.run();

        ClusterFileState a = model.fileState("/data/a.parquet");
        ClusterFileState b = model.fileState("/data/b.parquet");
        ClusterFileState c = model.fileState("/data/c.parquet");

        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);

        assertEquals(Placement.HOT, a.placement());
        assertEquals(Placement.HOT, b.placement());
        assertEquals(Placement.HOT, c.placement());

        assertEquals(5000L, a.lastAccessAtMillis());
        assertEquals(6000L, b.lastAccessAtMillis());
        assertEquals(9000L, c.lastAccessAtMillis());

        long expectedDiskUsage = (1_000_000_000L + 2_000_000_000L + 500_000_000L) * 3;
        assertEquals(expectedDiskUsage, model.usedBytes(PhysicalTier.DISK));
        assertEquals(0L, model.usedBytes(PhysicalTier.SSD));
        assertEquals(0L, model.usedBytes(PhysicalTier.ARCHIVE));

        assertEquals(0L, model.rejectionCount());
    }

    private static List<TraceEvent> loadFixture() throws IOException {
        try (InputStream in = ClusterStateModelReplayTest.class
                .getResourceAsStream("/fixtures/mini-trace-01/trace.jsonl")) {
            assertNotNull(in, "fixture not found on classpath");
            return TraceReader.readAll(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Map<PhysicalTier, Long> capacities(long ssd, long disk, long archive) {
        Map<PhysicalTier, Long> m = new EnumMap<>(PhysicalTier.class);
        m.put(PhysicalTier.SSD, ssd);
        m.put(PhysicalTier.DISK, disk);
        m.put(PhysicalTier.ARCHIVE, archive);
        return m;
    }
}
