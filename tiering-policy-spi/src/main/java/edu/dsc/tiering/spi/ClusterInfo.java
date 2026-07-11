package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** ObservationSnapshot.cluster — 클러스터 티어 용량 현황. */
public final class ClusterInfo {

    private final List<TierInfo> tiers;

    @JsonCreator
    public ClusterInfo(@JsonProperty("tiers") List<TierInfo> tiers) {
        this.tiers = tiers;
    }

    @JsonProperty("tiers")
    public List<TierInfo> tiers() {
        return tiers;
    }
}
