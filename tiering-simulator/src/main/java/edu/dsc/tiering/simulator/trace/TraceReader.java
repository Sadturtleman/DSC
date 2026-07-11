package edu.dsc.tiering.simulator.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * jsonl 트레이스 파일을 한 줄씩 읽어 {@link TraceEvent} 스트림으로 변환한다. 전체 파일을
 * 메모리에 올리지 않고(다음 한 줄만 지연 조회) {@link Iterator}로 순차 소비한다.
 * <p>
 * 다음 줄을 실제로 조회하기 전까지는(즉 {@link #hasNext()}가 호출되기 전까지는) 아무것도
 * 읽지 않는다. 시간 오름차순 위반(시간 역행) 이벤트를 만나면 그 시점의 {@link #hasNext()}
 * 또는 {@link #next()} 호출에서 {@link TraceOrderException}을 던진다 — 그 이전까지 정상
 * 소비된 이벤트는 이미 호출자에게 반환된 상태로 유지된다.
 * <p>
 * 알 수 없는 {@code type}이나 필드 누락은 {@link TraceFormatException}으로 보고한다.
 */
public final class TraceReader implements Iterator<TraceEvent>, Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedReader reader;
    private int lineNumber = 0;
    private long lastAt = Long.MIN_VALUE;
    private TraceEvent pending;
    private boolean pendingReady = false;
    private boolean exhausted = false;

    public TraceReader(Reader source) {
        this.reader = (source instanceof BufferedReader) ? (BufferedReader) source : new BufferedReader(source);
    }

    public static TraceReader open(Path path) throws IOException {
        return new TraceReader(Files.newBufferedReader(path, StandardCharsets.UTF_8));
    }

    /** 편의 메서드 — 소규모 픽스처/테스트 용. 대용량 트레이스에는 {@link #open(Path)}를 쓸 것. */
    public static List<TraceEvent> readAll(Reader source) {
        List<TraceEvent> events = new ArrayList<>();
        try (TraceReader r = new TraceReader(source)) {
            while (r.hasNext()) {
                events.add(r.next());
            }
        }
        return events;
    }

    @Override
    public boolean hasNext() {
        ensurePending();
        return pending != null;
    }

    @Override
    public TraceEvent next() {
        ensurePending();
        if (pending == null) {
            throw new NoSuchElementException("트레이스 이벤트 소진됨");
        }
        TraceEvent current = pending;
        pending = null;
        pendingReady = false;
        return current;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensurePending() {
        if (pendingReady || exhausted) {
            return;
        }
        String line = readNextNonBlankLine();
        if (line == null) {
            exhausted = true;
            pending = null;
            pendingReady = true;
            return;
        }
        TraceEvent event = parseLine(line, lineNumber);
        if (event.at() < lastAt) {
            throw new TraceOrderException(String.format(
                    "시간 역행 이벤트 (line %d): at=%d < 직전 at=%d", lineNumber, event.at(), lastAt));
        }
        lastAt = event.at();
        pending = event;
        pendingReady = true;
    }

    private String readNextNonBlankLine() {
        try {
            String line = reader.readLine();
            lineNumber++;
            while (line != null && line.isBlank()) {
                line = reader.readLine();
                lineNumber++;
            }
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TraceEvent parseLine(String line, int lineNumber) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (IOException e) {
            throw new TraceFormatException("잘못된 JSON (line " + lineNumber + "): " + e.getMessage(), e);
        }

        String typeName = textField(node, "type", lineNumber);
        long at = longField(node, "at", lineNumber);

        TraceEventType type;
        try {
            type = TraceEventType.fromJsonName(typeName);
        } catch (IllegalArgumentException e) {
            throw new TraceFormatException("알 수 없는 이벤트 타입 '" + typeName + "' (line " + lineNumber + ")");
        }

        switch (type) {
            case FILE_CREATE:
                return new FileCreateEvent(at, textField(node, "path", lineNumber),
                        longField(node, "size_bytes", lineNumber));
            case JOB:
                return new JobEvent(at, textField(node, "job_id", lineNumber),
                        stringListField(node, "input_paths", lineNumber));
            case READ:
                return new ReadEvent(at, textField(node, "path", lineNumber));
            default:
                throw new TraceFormatException("처리되지 않은 이벤트 타입 '" + typeName + "' (line " + lineNumber + ")");
        }
    }

    private static String textField(JsonNode node, String field, int lineNumber) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new TraceFormatException("필드 '" + field + "' 누락 또는 문자열이 아님 (line " + lineNumber + ")");
        }
        return value.asText();
    }

    private static long longField(JsonNode node, String field, int lineNumber) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new TraceFormatException("필드 '" + field + "' 누락 또는 숫자가 아님 (line " + lineNumber + ")");
        }
        return value.asLong();
    }

    private static List<String> stringListField(JsonNode node, String field, int lineNumber) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            throw new TraceFormatException("필드 '" + field + "' 누락 또는 배열이 아님 (line " + lineNumber + ")");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new TraceFormatException("필드 '" + field + "'의 원소는 문자열이어야 함 (line " + lineNumber + ")");
            }
            result.add(element.asText());
        }
        return result;
    }
}
