package edu.dsc.tiering.simulator.core;

/** {@link SimulationDriver#scheduleOnce}/{@link SimulationDriver#scheduleRepeating}용 콜백. */
@FunctionalInterface
public interface ReservedCallback {
    void onFire(long atMillis);
}
