package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** cluster.tiers[] 원소 — 물리 티어 하나의 용량 정보. */
public final class TierInfo {

    private final String name;
    private final long capacityBytes;
    private final long usedBytes;

    @JsonCreator
    public TierInfo(@JsonProperty("name") String name,
                     @JsonProperty("capacity_bytes") long capacityBytes,
                     @JsonProperty("used_bytes") long usedBytes) {
        this.name = name;
        this.capacityBytes = capacityBytes;
        this.usedBytes = usedBytes;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("capacity_bytes")
    public long capacityBytes() {
        return capacityBytes;
    }

    @JsonProperty("used_bytes")
    public long usedBytes() {
        return usedBytes;
    }
}
