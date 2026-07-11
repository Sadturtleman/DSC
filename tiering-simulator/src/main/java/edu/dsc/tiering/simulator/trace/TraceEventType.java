package edu.dsc.tiering.simulator.trace;

/** jsonl 트레이스 이벤트 3종. JSON의 {@code type} 필드 값과 {@link #jsonName()}이 1:1 대응한다. */
public enum TraceEventType {

    FILE_CREATE("file_create"),
    JOB("job"),
    READ("read");

    private final String jsonName;

    TraceEventType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static TraceEventType fromJsonName(String jsonName) {
        for (TraceEventType type : values()) {
            if (type.jsonName.equals(jsonName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 트레이스 이벤트 타입: " + jsonName);
    }
}
