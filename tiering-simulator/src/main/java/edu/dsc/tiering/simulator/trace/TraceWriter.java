package edu.dsc.tiering.simulator.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/** {@link TraceEvent} 스트림을 jsonl(한 줄 = 이벤트 하나)로 직렬화한다. {@link TraceReader}와 짝. */
public final class TraceWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceWriter() {
    }

    public static void write(Iterable<? extends TraceEvent> events, Writer out) {
        try {
            for (TraceEvent event : events) {
                out.write(MAPPER.writeValueAsString(toNode(event)));
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode toNode(TraceEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", event.type().jsonName());
        node.put("at", event.at());

        if (event instanceof FileCreateEvent) {
            FileCreateEvent e = (FileCreateEvent) event;
            node.put("path", e.path());
            node.put("size_bytes", e.sizeBytes());
        } else if (event instanceof JobEvent) {
            JobEvent e = (JobEvent) event;
            node.put("job_id", e.jobId());
            ArrayNode inputPaths = node.putArray("input_paths");
            for (String path : e.inputPaths()) {
                inputPaths.add(path);
            }
        } else if (event instanceof ReadEvent) {
            ReadEvent e = (ReadEvent) event;
            node.put("path", e.path());
        } else {
            throw new IllegalArgumentException("알 수 없는 TraceEvent 구현체: " + event.getClass());
        }
        return node;
    }
}
