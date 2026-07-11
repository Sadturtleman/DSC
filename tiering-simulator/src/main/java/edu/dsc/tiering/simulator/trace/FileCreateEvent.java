package edu.dsc.tiering.simulator.trace;

/** {"type":"file_create","at":...,"path":...,"size_bytes":...} — 파일이 생성됨. */
public final class FileCreateEvent extends TraceEvent {

    private final String path;
    private final long sizeBytes;

    public FileCreateEvent(long at, String path, long sizeBytes) {
        super(at);
        this.path = path;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public TraceEventType type() {
        return TraceEventType.FILE_CREATE;
    }

    public String path() {
        return path;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public String toString() {
        return "FileCreateEvent{at=" + at() + ", path='" + path + "', sizeBytes=" + sizeBytes + '}';
    }
}
