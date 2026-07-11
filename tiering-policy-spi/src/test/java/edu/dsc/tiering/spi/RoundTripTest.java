package edu.dsc.tiering.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CLAUDE.md "Policy SPI (v1.0)" 섹션의 JSON 예시(src/test/resources/examples/)가
 * DTO를 거쳐 역직렬화 → 재직렬화되어도 의미상 동일한지 검증한다. (examples/README.md 참고)
 */
class RoundTripTest {

    private static final ObjectMapper MAPPER = SpiObjectMapper.get();

    @Test
    void observationSnapshotRoundTripsSemantically() throws IOException {
        String original = readResource("/examples/observation-snapshot-example.json");

        ObservationSnapshot snapshot = MAPPER.readValue(original, ObservationSnapshot.class);
        String roundTripped = MAPPER.writeValueAsString(snapshot);

        assertJsonEquals(original, roundTripped);
    }

    @Test
    void placementDecisionRoundTripsSemantically() throws IOException {
        String original = readResource("/examples/placement-decision-example.json");

        PlacementDecision decision = MAPPER.readValue(original, PlacementDecision.class);
        String roundTripped = MAPPER.writeValueAsString(decision);

        assertJsonEquals(original, roundTripped);
    }

    private static void assertJsonEquals(String expectedJson, String actualJson) throws IOException {
        JsonNode expected = MAPPER.readTree(expectedJson);
        JsonNode actual = MAPPER.readTree(actualJson);
        assertEquals(expected, actual, () -> "expected:\n" + expected.toPrettyString()
                + "\nactual:\n" + actual.toPrettyString());
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = RoundTripTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " not on test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
