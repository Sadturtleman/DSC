package edu.dsc.tiering.spi;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiValidatorTest {

    @Test
    void fsimageModeWithNonEmptyEventsThrows() {
        ObservationSnapshot snapshot = new ObservationSnapshot(
                "1.0", Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"),
                ObservationMode.FSIMAGE,
                new ClusterInfo(List.of()),
                List.of(),
                List.of(new Event(EventType.READ, "/a", null, null, Instant.parse("2026-07-01T00:00:00Z"))),
                new Params(0.5, 30)
        );

        SpiValidationException ex = assertThrows(SpiValidationException.class,
                () -> SpiValidator.validate(snapshot));
        assertTrue(ex.getMessage().contains("fsimage"));
    }

    @Test
    void fsimageModeWithEmptyEventsPasses() {
        ObservationSnapshot snapshot = new ObservationSnapshot(
                "1.0", Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"),
                ObservationMode.FSIMAGE,
                new ClusterInfo(List.of()),
                List.of(),
                List.of(),
                new Params(0.5, 30)
        );

        assertDoesNotThrow(() -> SpiValidator.validate(snapshot));
    }

    @Test
    void auditHybridModeWithEventsPasses() {
        ObservationSnapshot snapshot = new ObservationSnapshot(
                "1.0", Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"),
                ObservationMode.AUDIT_HYBRID,
                new ClusterInfo(List.of()),
                List.of(),
                List.of(new Event(EventType.READ, "/a", null, null, Instant.parse("2026-07-01T00:00:00Z"))),
                new Params(0.5, 30)
        );

        assertDoesNotThrow(() -> SpiValidator.validate(snapshot));
    }

    @Test
    void priorityAboveOneThrows() {
        PlacementDecision decision = new PlacementDecision("1.0", "test",
                List.of(new Decision("/a", Placement.WARM, 1.5, "x")));

        SpiValidationException ex = assertThrows(SpiValidationException.class,
                () -> SpiValidator.validate(decision));
        assertTrue(ex.getMessage().contains("priority"));
    }

    @Test
    void negativePriorityThrows() {
        PlacementDecision decision = new PlacementDecision("1.0", "test",
                List.of(new Decision("/a", Placement.WARM, -0.1, "x")));

        assertThrows(SpiValidationException.class, () -> SpiValidator.validate(decision));
    }

    @Test
    void boundaryPriorityValuesPass() {
        PlacementDecision decision = new PlacementDecision("1.0", "test",
                List.of(new Decision("/a", Placement.WARM, 0.0, "x"),
                        new Decision("/b", Placement.COLD, 1.0, "y")));

        assertDoesNotThrow(() -> SpiValidator.validate(decision));
    }
}
