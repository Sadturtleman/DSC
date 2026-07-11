package edu.dsc.tiering.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * mini-case-01/observation.json이 스키마상 유효한지에 대한 최소 sanity check.
 * 정책별 기대 결정(expected-*.json) 검증은 3번(B1 어댑터) 구현 시 추가된다.
 */
class GoldenFixtureTest {

    private static final ObjectMapper MAPPER = SpiObjectMapper.get();

    @Test
    void miniCase01ParsesAndValidates() throws IOException {
        String json = readResource("/fixtures/mini-case-01/observation.json");

        ObservationSnapshot snapshot = MAPPER.readValue(json, ObservationSnapshot.class);
        assertDoesNotThrow(() -> SpiValidator.validate(snapshot));

        assertEquals(3, snapshot.files().size());
        assertEquals(ObservationMode.FSIMAGE, snapshot.observationMode());
        assertEquals(0, snapshot.events().size());
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = GoldenFixtureTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " not on test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
