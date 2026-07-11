package edu.dsc.tiering.simulator.trace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceReaderTest {

    @Test
    void parsesAllThreeEventTypesInOrder() {
        String jsonl = String.join("\n",
                "{\"type\":\"file_create\",\"at\":1000,\"path\":\"/a\",\"size_bytes\":2048}",
                "{\"type\":\"read\",\"at\":2000,\"path\":\"/a\"}",
                "{\"type\":\"job\",\"at\":3000,\"job_id\":\"j1\",\"input_paths\":[\"/a\",\"/b\"]}"
        );

        List<TraceEvent> events = TraceReader.readAll(new StringReader(jsonl));

        assertEquals(3, events.size());

        assertTrue(events.get(0) instanceof FileCreateEvent);
        FileCreateEvent fc = (FileCreateEvent) events.get(0);
        assertEquals(1000L, fc.at());
        assertEquals("/a", fc.path());
        assertEquals(2048L, fc.sizeBytes());

        assertTrue(events.get(1) instanceof ReadEvent);
        ReadEvent r = (ReadEvent) events.get(1);
        assertEquals(2000L, r.at());
        assertEquals("/a", r.path());

        assertTrue(events.get(2) instanceof JobEvent);
        JobEvent j = (JobEvent) events.get(2);
        assertEquals(3000L, j.at());
        assertEquals("j1", j.jobId());
        assertEquals(List.of("/a", "/b"), j.inputPaths());
    }

    @Test
    void skipsBlankLines() {
        String jsonl = "{\"type\":\"read\",\"at\":1,\"path\":\"/a\"}\n"
                + "\n"
                + "   \n"
                + "{\"type\":\"read\",\"at\":2,\"path\":\"/b\"}\n";

        List<TraceEvent> events = TraceReader.readAll(new StringReader(jsonl));

        assertEquals(2, events.size());
    }

    @Test
    void emptyInputProducesNoEvents() {
        List<TraceEvent> events = TraceReader.readAll(new StringReader(""));
        assertTrue(events.isEmpty());
    }

    @Test
    void timeRegressionThrowsImmediately() {
        String jsonl = String.join("\n",
                "{\"type\":\"read\",\"at\":2000,\"path\":\"/a\"}",
                "{\"type\":\"read\",\"at\":1000,\"path\":\"/b\"}"
        );

        try (TraceReader reader = new TraceReader(new StringReader(jsonl))) {
            assertTrue(reader.hasNext());
            reader.next(); // 첫 이벤트는 정상 소비됨

            TraceOrderException ex = assertThrows(TraceOrderException.class, reader::hasNext);
            assertTrue(ex.getMessage().contains("1000"));
            assertTrue(ex.getMessage().contains("2000"));
        }
    }

    @Test
    void equalTimestampsAreAllowed() {
        String jsonl = String.join("\n",
                "{\"type\":\"read\",\"at\":1000,\"path\":\"/a\"}",
                "{\"type\":\"read\",\"at\":1000,\"path\":\"/b\"}"
        );

        List<TraceEvent> events = TraceReader.readAll(new StringReader(jsonl));
        assertEquals(2, events.size());
    }

    @Test
    void unknownEventTypeThrowsFormatException() {
        String jsonl = "{\"type\":\"delete\",\"at\":1000,\"path\":\"/a\"}";

        assertThrows(TraceFormatException.class, () -> TraceReader.readAll(new StringReader(jsonl)));
    }

    @Test
    void missingRequiredFieldThrowsFormatException() {
        String jsonl = "{\"type\":\"file_create\",\"at\":1000,\"path\":\"/a\"}"; // size_bytes 누락

        assertThrows(TraceFormatException.class, () -> TraceReader.readAll(new StringReader(jsonl)));
    }

    @Test
    void malformedJsonThrowsFormatException() {
        String jsonl = "{not valid json";

        assertThrows(TraceFormatException.class, () -> TraceReader.readAll(new StringReader(jsonl)));
    }

    @Test
    void hasNextIsFalseAfterExhaustion() {
        try (TraceReader reader = new TraceReader(new StringReader("{\"type\":\"read\",\"at\":1,\"path\":\"/a\"}"))) {
            assertTrue(reader.hasNext());
            reader.next();
            assertFalse(reader.hasNext());
        }
    }
}
