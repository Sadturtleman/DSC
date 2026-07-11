package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ObservationSnapshot의 관측 방식.
 */
public enum ObservationMode {

    /** 배치 스냅샷 (FSImage). access_stats는 null, events는 빈 배열이어야 한다. */
    @JsonProperty("fsimage") FSIMAGE,

    /** 준실시간 관측. */
    @JsonProperty("audit_hybrid") AUDIT_HYBRID
}
