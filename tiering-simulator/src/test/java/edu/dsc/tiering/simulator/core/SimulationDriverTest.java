package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.simulator.trace.ReadEvent;
import edu.dsc.tiering.simulator.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationDriverTest {

    @Test
    void repeatingReservationFiresAtExactTimesBoundedByHorizon() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 10_000L);
        List<Long> fireTimes = new ArrayList<>();

        driver.scheduleRepeating(500L, 1000L, fireTimes::add);
        driver.run();

        List<Long> expected = List.of(500L, 1500L, 2500L, 3500L, 4500L, 5500L, 6500L, 7500L, 8500L, 9500L);
        assertEquals(expected, fireTimes);
    }

    @Test
    void oneShotReservationFiresExactlyOnce() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 10_000L);
        List<Long> fireTimes = new ArrayList<>();

        driver.scheduleOnce(3000L, fireTimes::add);
        driver.run();

        assertEquals(List.of(3000L), fireTimes);
    }

    @Test
    void reservationBeyondHorizonNeverFires() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 1000L);
        List<Long> fireTimes = new ArrayList<>();

        driver.scheduleOnce(5000L, fireTimes::add);
        driver.run();

        assertTrue(fireTimes.isEmpty());
    }

    @Test
    void clockAdvancesToEachProcessedEventTime() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 10_000L);
        List<Long> observedNow = new ArrayList<>();

        driver.scheduleOnce(100L, at -> observedNow.add(driver.clock().now()));
        driver.scheduleOnce(200L, at -> observedNow.add(driver.clock().now()));
        driver.run();

        assertEquals(List.of(100L, 200L), observedNow);
        assertEquals(200L, driver.clock().now());
    }

    @Test
    void traceEventAndReservedEventAtSameTimeProcessTraceFirst() {
        List<TraceEvent> trace = List.of(new ReadEvent(1000L, "/a"));
        SimulationDriver driver = new SimulationDriver(trace.iterator(), 0L, 10_000L);
        List<String> order = new ArrayList<>();

        driver.addListener(new SimListener() {
            @Override
            public void onEvent(long atMillis, TraceEvent event) {
                order.add("trace@" + atMillis);
            }

            @Override
            public void onTimeAdvance(long fromMillis, long toMillis) {
                // no-op
            }
        });
        driver.scheduleOnce(1000L, at -> order.add("reserved@" + at));

        driver.run();

        assertEquals(List.of("trace@1000", "reserved@1000"), order);
    }

    @Test
    void multipleReservationsAtSameTimeFireInRegistrationOrder() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 10_000L);
        List<String> order = new ArrayList<>();

        driver.scheduleOnce(500L, at -> order.add("first"));
        driver.scheduleOnce(500L, at -> order.add("second"));
        driver.scheduleOnce(500L, at -> order.add("third"));
        driver.run();

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void emptyTraceAndNoReservationsRunsWithoutError() {
        SimulationDriver driver = new SimulationDriver(Collections.emptyIterator(), 0L, 1000L);
        driver.run(); // 예외 없이 즉시 종료되어야 함
        assertEquals(0L, driver.clock().now());
    }
}
