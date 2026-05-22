package edu.dsc.tiering.scheduler;

import edu.dsc.tiering.config.AppConfig;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

class WindowSelector {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final List<AppConfig.Scheduler.Window> windows;

    WindowSelector(List<AppConfig.Scheduler.Window> windows) {
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("at least one window required");
        }
        this.windows = windows;
    }

    AppConfig.Scheduler.Window currentWindow() {
        LocalTime now = LocalTime.now();
        for (AppConfig.Scheduler.Window w : windows) {
            if (contains(w, now)) return w;
        }
        return windows.get(0);
    }

    static boolean contains(AppConfig.Scheduler.Window w, LocalTime t) {
        LocalTime start = LocalTime.parse(w.start(), HM);
        LocalTime end = LocalTime.parse(w.end(), HM);
        if (start.equals(end)) return true; // 24h window
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        // 자정을 가로지르는 윈도우 (예: 18:00 - 09:00)
        return !t.isBefore(start) || t.isBefore(end);
    }
}
