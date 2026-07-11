package edu.dsc.tiering.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson 역직렬화 단계에서 잡혀야 하는 v1.0 규약 위반:
 * Placement 5종 외의 값, 알 수 없는 JSON 필드.
 */
class DeserializationFailureTest {

    private static final ObjectMapper MAPPER = SpiObjectMapper.get();

    private static final String VALID_SNAPSHOT =
            "{\"schema_version\":\"1.0\","
            + "\"observed_at\":\"2026-07-01T00:00:00Z\","
            + "\"decided_at\":\"2026-07-01T00:00:00Z\","
            + "\"observation_mode\":\"fsimage\","
            + "\"cluster\":{\"tiers\":[]},"
            + "\"files\":[{\"path\":\"/a\",\"size_bytes\":1,\"current_placement\":\"HOT\","
            + "\"atime\":\"2026-06-01T00:00:00Z\",\"mtime\":\"2026-06-01T00:00:00Z\",\"access_stats\":null}],"
            + "\"events\":[],"
            + "\"params\":{\"aggressiveness\":0.5,\"access_horizon_days\":30}}";

    @Test
    void unknownPlacementInFileStateFailsWithClearMessage() {
        String badJson = VALID_SNAPSHOT.replace("\"current_placement\":\"HOT\"",
                "\"current_placement\":\"BOGUS\"");

        JsonProcessingException ex = assertThrows(JsonProcessingException.class,
                () -> MAPPER.readValue(badJson, ObservationSnapshot.class));

        assertTrue(ex.getMessage().contains("BOGUS"), "메시지에 잘못된 값이 포함되어야 함: " + ex.getMessage());
    }

    @Test
    void unknownPlacementInDecisionFailsWithClearMessage() {
        String badJson = "{\"schema_version\":\"1.0\",\"policy_id\":\"test\","
                + "\"decisions\":[{\"path\":\"/a\",\"target_placement\":\"BOGUS\","
                + "\"priority\":0.5,\"reason\":\"x\"}]}";

        JsonProcessingException ex = assertThrows(JsonProcessingException.class,
                () -> MAPPER.readValue(badJson, PlacementDecision.class));

        assertTrue(ex.getMessage().contains("BOGUS"), "메시지에 잘못된 값이 포함되어야 함: " + ex.getMessage());
    }

    @Test
    void unknownTopLevelFieldFails() {
        String badJson = VALID_SNAPSHOT.substring(0, VALID_SNAPSHOT.length() - 1)
                + ",\"extra_field\":true}";

        assertThrows(JsonProcessingException.class,
                () -> MAPPER.readValue(badJson, ObservationSnapshot.class));
    }
}
