package edu.dsc.tiering.spi.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dsc.tiering.spi.ObservationSnapshot;
import edu.dsc.tiering.spi.PlacementDecision;
import edu.dsc.tiering.spi.SpiObjectMapper;
import edu.dsc.tiering.spi.SpiValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * fixtures/mini-case-01에 대한 B1(RuleWeightedPolicy)의 골든 픽스처 검증.
 * 기대값 산출 근거는 fixtures/README.md의 손계산 표 참고.
 */
class RuleWeightedPolicyFixtureTest {

    private static final ObjectMapper MAPPER = SpiObjectMapper.get();
    private static final RuleWeightedPolicy POLICY = new RuleWeightedPolicy();

    @Test
    void miniCase01MatchesExpectedB1Decision() throws IOException {
        ObservationSnapshot snapshot = readValue(
                "/fixtures/mini-case-01/observation.json", ObservationSnapshot.class);
        SpiValidator.validate(snapshot);

        PlacementDecision actual = POLICY.decide(snapshot);
        SpiValidator.validate(actual);

        String expectedJson = readResource("/fixtures/mini-case-01/expected-B1.json");
        JsonNode expected = MAPPER.readTree(expectedJson);
        JsonNode actualNode = MAPPER.readTree(MAPPER.writeValueAsString(actual));

        assertEquals(expected, actualNode, () -> "expected:\n" + expected.toPrettyString()
                + "\nactual:\n" + actualNode.toPrettyString());
    }

    private static <T> T readValue(String path, Class<T> type) throws IOException {
        return MAPPER.readValue(readResource(path), type);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = RuleWeightedPolicyFixtureTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " not on test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
