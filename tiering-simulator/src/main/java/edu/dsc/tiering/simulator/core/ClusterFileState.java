package edu.dsc.tiering.simulator.core;

import edu.dsc.tiering.spi.Placement;

/** {@link ClusterStateModel}이 관리하는 파일 하나의 상태: 크기, 현재 Placement, 마지막 접근 시각. */
public final class ClusterFileState {

    private final String path;
    private final long sizeBytes;
    private Placement placement;
    private long lastAccessAtMillis;

    ClusterFileState(String path, long sizeBytes, Placement placement, long createdAtMillis) {
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.placement = placement;
        this.lastAccessAtMillis = createdAtMillis;
    }

    public String path() {
        return path;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Placement placement() {
        return placement;
    }

    public long lastAccessAtMillis() {
        return lastAccessAtMillis;
    }

    void touch(long atMillis) {
        this.lastAccessAtMillis = atMillis;
    }

    void setPlacement(Placement placement) {
        this.placement = placement;
    }

    @Override
    public String toString() {
        return "ClusterFileState{path='" + path + "', sizeBytes=" + sizeBytes
                + ", placement=" + placement + ", lastAccessAtMillis=" + lastAccessAtMillis + '}';
    }
}
