package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** ObservationSnapshot.files[] 원소 — 파일 한 개의 관측 상태. */
public final class FileState {

    private final String path;
    private final long sizeBytes;
    private final Placement currentPlacement;
    private final Instant atime;
    private final Instant mtime;
    private final AccessStats accessStats;

    @JsonCreator
    public FileState(@JsonProperty("path") String path,
                      @JsonProperty("size_bytes") long sizeBytes,
                      @JsonProperty("current_placement") Placement currentPlacement,
                      @JsonProperty("atime") Instant atime,
                      @JsonProperty("mtime") Instant mtime,
                      @JsonProperty("access_stats") AccessStats accessStats) {
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.currentPlacement = currentPlacement;
        this.atime = atime;
        this.mtime = mtime;
        this.accessStats = accessStats;
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("size_bytes")
    public long sizeBytes() {
        return sizeBytes;
    }

    @JsonProperty("current_placement")
    public Placement currentPlacement() {
        return currentPlacement;
    }

    @JsonProperty("atime")
    public Instant atime() {
        return atime;
    }

    @JsonProperty("mtime")
    public Instant mtime() {
        return mtime;
    }

    /** fsimage 모드에서는 null. */
    @JsonProperty("access_stats")
    public AccessStats accessStats() {
        return accessStats;
    }
}
