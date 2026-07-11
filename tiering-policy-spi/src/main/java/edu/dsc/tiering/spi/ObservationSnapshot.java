package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * TieringPolicy#decide()의 입력. 정책이 목표 배치를 결정하는 데 필요한 모든 관측치를
 * 담는 불변 스냅샷.
 * <p>
 * {@code decidedAt - observedAt}은 관측 지연(staleness)이며 RQ3의 실험 변수이므로
 * 정확히 보존한다.
 */
public final class ObservationSnapshot {

    private final String schemaVersion;
    private final Instant observedAt;
    private final Instant decidedAt;
    private final ObservationMode observationMode;
    private final ClusterInfo cluster;
    private final List<FileState> files;
    private final List<Event> events;
    private final Params params;

    @JsonCreator
    public ObservationSnapshot(@JsonProperty("schema_version") String schemaVersion,
                                @JsonProperty("observed_at") Instant observedAt,
                                @JsonProperty("decided_at") Instant decidedAt,
                                @JsonProperty("observation_mode") ObservationMode observationMode,
                                @JsonProperty("cluster") ClusterInfo cluster,
                                @JsonProperty("files") List<FileState> files,
                                @JsonProperty("events") List<Event> events,
                                @JsonProperty("params") Params params) {
        this.schemaVersion = schemaVersion;
        this.observedAt = observedAt;
        this.decidedAt = decidedAt;
        this.observationMode = observationMode;
        this.cluster = cluster;
        this.files = files;
        this.events = events;
        this.params = params;
    }

    @JsonProperty("schema_version")
    public String schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("observed_at")
    public Instant observedAt() {
        return observedAt;
    }

    @JsonProperty("decided_at")
    public Instant decidedAt() {
        return decidedAt;
    }

    @JsonProperty("observation_mode")
    public ObservationMode observationMode() {
        return observationMode;
    }

    @JsonProperty("cluster")
    public ClusterInfo cluster() {
        return cluster;
    }

    @JsonProperty("files")
    public List<FileState> files() {
        return files;
    }

    @JsonProperty("events")
    public List<Event> events() {
        return events;
    }

    @JsonProperty("params")
    public Params params() {
        return params;
    }
}
