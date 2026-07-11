package edu.dsc.tiering.simulator.trace;

import java.util.List;

/** {"type":"job","at":...,"job_id":...,"input_paths":[...]} — job이 입력 파일들을 읽음. */
public final class JobEvent extends TraceEvent {

    private final String jobId;
    private final List<String> inputPaths;

    public JobEvent(long at, String jobId, List<String> inputPaths) {
        super(at);
        this.jobId = jobId;
        this.inputPaths = List.copyOf(inputPaths);
    }

    @Override
    public TraceEventType type() {
        return TraceEventType.JOB;
    }

    public String jobId() {
        return jobId;
    }

    public List<String> inputPaths() {
        return inputPaths;
    }

    @Override
    public String toString() {
        return "JobEvent{at=" + at() + ", jobId='" + jobId + "', inputPaths=" + inputPaths + '}';
    }
}
