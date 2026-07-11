package edu.dsc.tiering.simulator.core;

/**
 * 가상 시계. {@code long epochMillis}로만 시간을 표현하며, 실시간(System.currentTimeMillis 등)과
 * 무관하다. {@link SimulationDriver}만 시계를 전진시킬 수 있다 — 다른 코드는 {@link #now()}로
 * 읽기만 한다.
 */
public final class VirtualClock {

    private long epochMillis;

    public VirtualClock(long startEpochMillis) {
        this.epochMillis = startEpochMillis;
    }

    public long now() {
        return epochMillis;
    }

    /** {@link SimulationDriver} 전용. 시계는 앞으로만 전진할 수 있다. */
    void advanceTo(long newEpochMillis) {
        if (newEpochMillis < epochMillis) {
            throw new IllegalArgumentException(
                    "가상 시계는 뒤로 갈 수 없습니다: " + newEpochMillis + " < " + epochMillis);
        }
        epochMillis = newEpochMillis;
    }
}
