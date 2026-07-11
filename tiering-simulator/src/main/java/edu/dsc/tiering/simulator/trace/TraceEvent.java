package edu.dsc.tiering.simulator.trace;

/**
 * jsonl 트레이스의 이벤트 한 줄. {@link #at()}은 epoch millis(가상 시계 기준, 실시간과 무관).
 * 구체 타입은 {@link FileCreateEvent}, {@link JobEvent}, {@link ReadEvent} 3종뿐이다.
 */
public abstract class TraceEvent {

    private final long at;

    protected TraceEvent(long at) {
        this.at = at;
    }

    public final long at() {
        return at;
    }

    public abstract TraceEventType type();
}
