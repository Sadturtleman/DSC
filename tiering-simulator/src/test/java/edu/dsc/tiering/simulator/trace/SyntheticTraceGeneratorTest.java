package edu.dsc.tiering.simulator.trace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticTraceGeneratorTest {

    private static SyntheticSpec spec(long seed) {
        SizeDistribution sizes = new SizeDistribution(
                new SizeBucket(0.5, 1_000_000L, 10_000_000L),
                new SizeBucket(0.3, 10_000_000L, 1_000_000_000L),
                new SizeBucket(0.2, 1_000_000_000L, 50_000_000_000L));
        AccessPatternMix mix = new AccessPatternMix(0.3, 0.3, 0.4);
        RepeatingJobSpec jobSpec = new RepeatingJobSpec(0, 7, 5, 3, "etl");
        return new SyntheticSpec(seed, 40, 90, sizes, mix, jobSpec);
    }

    private static String toJsonl(List<TraceEvent> events) {
        StringWriter sw = new StringWriter();
        TraceWriter.write(events, sw);
        return sw.toString();
    }

    @Test
    void sameSeedProducesByteIdenticalOutput() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();

        List<TraceEvent> first = generator.generate(spec(42L));
        List<TraceEvent> second = generator.generate(spec(42L));

        assertEquals(toJsonl(first), toJsonl(second));
    }

    @Test
    void differentSeedProducesDifferentOutput() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();

        List<TraceEvent> a = generator.generate(spec(1L));
        List<TraceEvent> b = generator.generate(spec(2L));

        assertNotEquals(toJsonl(a), toJsonl(b));
    }

    @Test
    void eventsAreSortedByTimeAscending() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();
        List<TraceEvent> events = generator.generate(spec(7L));

        assertFalse(events.isEmpty());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i - 1).at() <= events.get(i).at(),
                    () -> "정렬 위반: index " + events.indexOf(events.get(i - 1)));
        }
    }

    @Test
    void containsExactlyOneFileCreatePerFile() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();
        SyntheticSpec s = spec(7L);
        List<TraceEvent> events = generator.generate(s);

        long fileCreateCount = events.stream().filter(e -> e instanceof FileCreateEvent).count();
        assertEquals(s.fileCount(), fileCreateCount);
    }

    @Test
    void repeatingJobEventsShareTheSameInputPaths() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();
        List<TraceEvent> events = generator.generate(spec(7L));

        List<JobEvent> jobs = events.stream()
                .filter(e -> e instanceof JobEvent)
                .map(e -> (JobEvent) e)
                .collect(Collectors.toList());

        assertEquals(5, jobs.size());
        List<String> firstInputs = jobs.get(0).inputPaths();
        for (JobEvent job : jobs) {
            assertEquals(firstInputs, job.inputPaths());
        }
    }

    @Test
    void nullRepeatingJobSpecProducesNoJobEvents() {
        SizeDistribution sizes = new SizeDistribution(
                new SizeBucket(1.0, 1_000_000L, 2_000_000L),
                new SizeBucket(0.0, 2_000_000L, 2_000_000L),
                new SizeBucket(0.0, 2_000_000L, 2_000_000L));
        AccessPatternMix mix = new AccessPatternMix(1.0, 0.0, 0.0);
        SyntheticSpec s = new SyntheticSpec(1L, 5, 30, sizes, mix, null);

        List<TraceEvent> events = new SyntheticTraceGenerator().generate(s);

        assertTrue(events.stream().noneMatch(e -> e instanceof JobEvent));
    }

    @Test
    void generatorOutputRoundTripsThroughTraceReader() {
        SyntheticTraceGenerator generator = new SyntheticTraceGenerator();
        List<TraceEvent> generated = generator.generate(spec(99L));

        String written = toJsonl(generated);
        List<TraceEvent> readBack = TraceReader.readAll(new StringReader(written));
        String rewritten = toJsonl(readBack);

        assertEquals(written, rewritten);
    }
}
