package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * ObservationSnapshot.events[] 원소. type에 따라 채워지는 필드가 다르다:
 * READ는 {@link #path()}, JOB_SUBMITTED는 {@link #jobId()}/{@link #inputPaths()}.
 * 해당 없는 필드는 직렬화 시 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Event {

    private final EventType type;
    private final String path;
    private final String jobId;
    private final List<String> inputPaths;
    private final Instant at;

    @JsonCreator
    public Event(@JsonProperty("type") EventType type,
                 @JsonProperty("path") String path,
                 @JsonProperty("job_id") String jobId,
                 @JsonProperty("input_paths") List<String> inputPaths,
                 @JsonProperty("at") Instant at) {
        this.type = type;
        this.path = path;
        this.jobId = jobId;
        this.inputPaths = inputPaths;
        this.at = at;
    }

    @JsonProperty("type")
    public EventType type() {
        return type;
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("job_id")
    public String jobId() {
        return jobId;
    }

    @JsonProperty("input_paths")
    public List<String> inputPaths() {
        return inputPaths;
    }

    @JsonProperty("at")
    public Instant at() {
        return at;
    }
}
