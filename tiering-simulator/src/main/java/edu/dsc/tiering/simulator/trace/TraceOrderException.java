package edu.dsc.tiering.simulator.trace;

/** jsonl 트레이스는 시간 오름차순이어야 한다 — 위반(시간 역행) 발견 시 즉시 던진다. */
public class TraceOrderException extends RuntimeException {

    public TraceOrderException(String message) {
        super(message);
    }
}
