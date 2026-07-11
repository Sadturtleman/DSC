package edu.dsc.tiering.simulator.trace;

/** {"type":"read","at":...,"path":...} — job 외 단발 읽기. */
public final class ReadEvent extends TraceEvent {

    private final String path;

    public ReadEvent(long at, String path) {
        super(at);
        this.path = path;
    }

    @Override
    public TraceEventType type() {
        return TraceEventType.READ;
    }

    public String path() {
        return path;
    }

    @Override
    public String toString() {
        return "ReadEvent{at=" + at() + ", path='" + path + "'}";
    }
}
