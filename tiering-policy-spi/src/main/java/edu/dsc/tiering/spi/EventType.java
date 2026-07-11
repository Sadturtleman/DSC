package edu.dsc.tiering.spi;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ObservationSnapshot.events[].type.
 */
public enum EventType {

    /** 파일 읽기. {@link Event#path()}가 채워진다. */
    @JsonProperty("read") READ,

    /** Spark job 제출. {@link Event#jobId()}, {@link Event#inputPaths()}가 채워진다. */
    @JsonProperty("job_submitted") JOB_SUBMITTED
}
