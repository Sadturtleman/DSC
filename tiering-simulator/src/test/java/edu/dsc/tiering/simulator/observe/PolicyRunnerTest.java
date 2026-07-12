package edu.dsc.tiering.simulator.observe;

import edu.dsc.tiering.simulator.core.ClusterStateModel;
import edu.dsc.tiering.simulator.core.SimulationDriver;
import edu.dsc.tiering.simulator.model.PhysicalTier;
import edu.dsc.tiering.simulator.trace.FileCreateEvent;
import edu.dsc.tiering.spi.Decision;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.Params;
import edu.dsc.tiering.spi.Placement;
import edu.dsc.tiering.spi.PlacementDecision;
import edu.dsc.tiering.spi.TieringPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRunnerTest {

    private static ClusterStateModel modelWithCapacity(long ssd, long disk, long archive) {
        Map<PhysicalTier, Long> capacity = new EnumMap<>(PhysicalTier.class);
        capacity.put(PhysicalTier.SSD, ssd);
        capacity.put(PhysicalTier.DISK, disk);
        capacity.put(PhysicalTier.ARCHIVE, archive);
        return new ClusterStateModel(capacity);
    }

    private static TieringPolicy fixedDecisionPolicy(List<Decision> decisions) {
        return new TieringPolicy() {
            @Override
            public String policyId() {
                return "test-fixed";
            }

            @Override
            public PlacementDecision decide(ObservationSnapshot snapshot) {
                return new PlacementDecision("1.0", "test-fixed", decisions);
            }
        };
    }

    @Test
    void acceptedDecisionNotifiesListenersWithFromAndTo() {
        ClusterStateModel model = modelWithCapacity(1_000_000_000L, 1_000_000_000L, 1_000_000_000L);
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 1000L)); // HOT에서 시작

        TieringPolicy policy = fixedDecisionPolicy(
                List.of(new Decision("/a", Placement.COLD, 1.0, "test")));
        PolicyRunner runner = new PolicyRunner(policy, new SnapshotAssembler(model, new Params(0.5, 90)), model);

        List<String> notified = new ArrayList<>();
        runner.addPlacementChangeListener((atMillis, path, from, to) ->
                notified.add(atMillis + ":" + path + ":" + from + "->" + to));

        runner.runCycle(5000L);

        assertEquals(List.of("5000:/a:HOT->COLD"), notified);
        assertEquals(Placement.COLD, model.fileState("/a").placement());
    }

    @Test
    void rejectedDecisionDoesNotNotifyListeners() {
        // SSD 정원이 0이라 ALL_SSD로의 이동은 반드시 거부된다.
        ClusterStateModel model = modelWithCapacity(0L, 1_000_000_000L, 1_000_000_000L);
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 1000L));

        TieringPolicy policy = fixedDecisionPolicy(
                List.of(new Decision("/a", Placement.ALL_SSD, 1.0, "test")));
        PolicyRunner runner = new PolicyRunner(policy, new SnapshotAssembler(model, new Params(0.5, 90)), model);

        List<String> notified = new ArrayList<>();
        runner.addPlacementChangeListener((atMillis, path, from, to) -> notified.add(path));

        runner.runCycle(5000L);

        assertTrue(notified.isEmpty());
        assertEquals(Placement.HOT, model.fileState("/a").placement()); // 변경되지 않음
        assertEquals(1L, model.rejectionCount());
    }

    @Test
    void decisionForUnknownPathIsSkippedSafely() {
        ClusterStateModel model = modelWithCapacity(1_000_000_000L, 1_000_000_000L, 1_000_000_000L);

        TieringPolicy policy = fixedDecisionPolicy(
                List.of(new Decision("/does-not-exist", Placement.COLD, 1.0, "test")));
        PolicyRunner runner = new PolicyRunner(policy, new SnapshotAssembler(model, new Params(0.5, 90)), model);

        List<String> notified = new ArrayList<>();
        runner.addPlacementChangeListener((atMillis, path, from, to) -> notified.add(path));

        runner.runCycle(5000L); // 예외 없이 조용히 건너뛰어야 함

        assertTrue(notified.isEmpty());
    }

    @Test
    void noDecisionsMeansNoChangesAndNoNotifications() {
        ClusterStateModel model = modelWithCapacity(1_000_000_000L, 1_000_000_000L, 1_000_000_000L);
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 1000L));

        TieringPolicy policy = fixedDecisionPolicy(List.of());
        PolicyRunner runner = new PolicyRunner(policy, new SnapshotAssembler(model, new Params(0.5, 90)), model);

        List<String> notified = new ArrayList<>();
        runner.addPlacementChangeListener((atMillis, path, from, to) -> notified.add(path));

        runner.runCycle(5000L);

        assertTrue(notified.isEmpty());
        assertEquals(Placement.HOT, model.fileState("/a").placement());
    }

    @Test
    void attachToDrivesRunCycleOnScheduleViaDriver() {
        ClusterStateModel model = modelWithCapacity(1_000_000_000L, 1_000_000_000L, 1_000_000_000L);
        model.onEvent(0L, new FileCreateEvent(0L, "/a", 1000L));

        TieringPolicy policy = fixedDecisionPolicy(
                List.of(new Decision("/a", Placement.COLD, 1.0, "test")));
        PolicyRunner runner = new PolicyRunner(policy, new SnapshotAssembler(model, new Params(0.5, 90)), model);

        List<String> notified = new ArrayList<>();
        runner.addPlacementChangeListener((atMillis, path, from, to) -> notified.add(atMillis + ":" + path));

        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 100_000L);
        runner.attachTo(driver, 1000L, 1000L);
        driver.run();

        // 1000부터 1000 간격으로 100000까지 반복 실행되지만, 첫 사이클에서 이미 COLD로
        // 이동한 뒤에는 from==to라 PolicyRunner가 스스로 걸러내므로 통지는 딱 한 번뿐이다.
        assertEquals(List.of("1000:/a"), notified);
    }
}
