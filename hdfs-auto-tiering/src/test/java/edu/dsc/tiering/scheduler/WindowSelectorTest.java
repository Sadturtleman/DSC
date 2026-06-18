package edu.dsc.tiering.scheduler;

import edu.dsc.tiering.config.AppConfig.Scheduler.Window;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSelectorTest {

    private static Window window(String name, String start, String end) {
        return new Window(name, start, end, 1, 1L);
    }

    @Test
    void normalWindowStartInclusiveEndExclusive() {
        Window day = window("day", "09:00", "18:00");
        assertTrue(WindowSelector.contains(day, LocalTime.of(9, 0)),  "start is inclusive");
        assertTrue(WindowSelector.contains(day, LocalTime.of(12, 0)));
        assertTrue(WindowSelector.contains(day, LocalTime.of(17, 59)));
        assertFalse(WindowSelector.contains(day, LocalTime.of(18, 0)), "end is exclusive");
        assertFalse(WindowSelector.contains(day, LocalTime.of(8, 59)));
    }

    @Test
    void wrapAroundMidnightCoversBothSides() {
        Window night = window("night", "18:00", "09:00");
        assertTrue(WindowSelector.contains(night, LocalTime.of(18, 0)));
        assertTrue(WindowSelector.contains(night, LocalTime.of(22, 0)));
        assertTrue(WindowSelector.contains(night, LocalTime.of(0, 0)));
        assertTrue(WindowSelector.contains(night, LocalTime.of(2, 0)));
        assertTrue(WindowSelector.contains(night, LocalTime.of(8, 59)));
        assertFalse(WindowSelector.contains(night, LocalTime.of(9, 0)), "end is exclusive");
        assertFalse(WindowSelector.contains(night, LocalTime.of(12, 0)));
    }

    @Test
    void equalStartAndEndMeansAlwaysActive() {
        Window allDay = window("all", "00:00", "00:00");
        assertTrue(WindowSelector.contains(allDay, LocalTime.of(0, 0)));
        assertTrue(WindowSelector.contains(allDay, LocalTime.MIDNIGHT));
        assertTrue(WindowSelector.contains(allDay, LocalTime.NOON));
        assertTrue(WindowSelector.contains(allDay, LocalTime.of(23, 59)));
    }

    @Test
    void emptyOrNullWindowsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WindowSelector(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WindowSelector(null));
    }

    @Test
    void singleAllDayWindowAlwaysSelected() {
        WindowSelector sel = new WindowSelector(List.of(window("only", "00:00", "00:00")));
        assertEquals("only", sel.currentWindow().name());
    }

    @Test
    void firstMatchingWindowWinsWhenMultipleMatch() {
        // both 24/7 windows match; iteration order決定
        WindowSelector sel = new WindowSelector(List.of(
                window("first",  "00:00", "00:00"),
                window("second", "00:00", "00:00")
        ));
        assertEquals("first", sel.currentWindow().name());
    }
}
