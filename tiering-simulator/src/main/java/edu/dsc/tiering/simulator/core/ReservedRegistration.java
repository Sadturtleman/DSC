package edu.dsc.tiering.simulator.core;

/** {@link SimulationDriver#scheduleOnce}/{@link SimulationDriver#scheduleRepeating}의 내부 등록 정보. */
final class ReservedRegistration {

    private final ReservedCallback callback;
    private final long intervalMillis; // 0이면 1회성

    ReservedRegistration(ReservedCallback callback, long intervalMillis) {
        this.callback = callback;
        this.intervalMillis = intervalMillis;
    }

    ReservedCallback callback() {
        return callback;
    }

    boolean isRepeating() {
        return intervalMillis > 0;
    }

    long intervalMillis() {
        return intervalMillis;
    }
}
