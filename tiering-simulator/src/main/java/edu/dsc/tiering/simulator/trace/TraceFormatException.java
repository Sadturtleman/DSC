package edu.dsc.tiering.simulator.trace;

/** jsonl 한 줄이 스키마(3종 이벤트 중 하나)에 맞지 않을 때. */
public class TraceFormatException extends RuntimeException {

    public TraceFormatException(String message) {
        super(message);
    }

    public TraceFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
