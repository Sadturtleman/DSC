package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** FileState.access_stats — audit_hybrid 모드에서만 채워지는 접근 횟수 통계. */
public final class AccessStats {

    private final long reads1h;
    private final long reads24h;
    private final long reads7d;

    @JsonCreator
    public AccessStats(@JsonProperty("reads_1h") long reads1h,
                        @JsonProperty("reads_24h") long reads24h,
                        @JsonProperty("reads_7d") long reads7d) {
        this.reads1h = reads1h;
        this.reads24h = reads24h;
        this.reads7d = reads7d;
    }

    @JsonProperty("reads_1h")
    public long reads1h() {
        return reads1h;
    }

    @JsonProperty("reads_24h")
    public long reads24h() {
        return reads24h;
    }

    @JsonProperty("reads_7d")
    public long reads7d() {
        return reads7d;
    }
}
