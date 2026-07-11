package edu.dsc.tiering.simulator.config;

import edu.dsc.tiering.simulator.model.PhysicalTier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimConstantsLoaderTest {

    @Test
    void loadsCheckedInResourceUsingPlaceholdersForAllTodoValues() throws IOException {
        SimConstants constants = SimConstantsLoader.loadDefault();

        // 체크인된 sim-constants.yaml은 전부 TODO이므로, 로더가 던지지 않고 임시 기본값으로
        // 채워 SimConstants를 완성해야 한다. 값 자체보다 "완성됨(0/누락 없음)"이 검증 대상.
        for (PhysicalTier tier : PhysicalTier.values()) {
            assertTrue(constants.throughputBytesPerSec(tier) > 0);
            assertTrue(constants.pricePerGbHour(tier) > 0);
            assertTrue(constants.tierCapacityBytes(tier) > 0);
        }
        assertTrue(constants.migrationBandwidthBytesPerSec() > 0);
        assertTrue(constants.rateLimitBytesPerSec() > 0);
        assertTrue(constants.migrationPricePerGb() > 0);
    }

    @Test
    void realNumbersOverridePlaceholders() throws IOException {
        String yaml = String.join("\n",
                "throughput_bytes_per_sec:",
                "  SSD: 111",
                "  DISK: 222",
                "  ARCHIVE: 333",
                "migration_bandwidth_bytes_per_sec: 444",
                "rate_limit_bytes_per_sec: 555",
                "price_per_gb_hour:",
                "  SSD: 0.1",
                "  DISK: 0.2",
                "  ARCHIVE: 0.3",
                "migration_price_per_gb: 0.4",
                "tier_capacity_bytes:",
                "  SSD: 666",
                "  DISK: 777",
                "  ARCHIVE: 888"
        );

        SimConstants constants = load(yaml);

        assertEquals(111L, constants.throughputBytesPerSec(PhysicalTier.SSD));
        assertEquals(222L, constants.throughputBytesPerSec(PhysicalTier.DISK));
        assertEquals(333L, constants.throughputBytesPerSec(PhysicalTier.ARCHIVE));
        assertEquals(444L, constants.migrationBandwidthBytesPerSec());
        assertEquals(555L, constants.rateLimitBytesPerSec());
        assertEquals(0.1, constants.pricePerGbHour(PhysicalTier.SSD), 1e-9);
        assertEquals(0.4, constants.migrationPricePerGb(), 1e-9);
        assertEquals(666L, constants.tierCapacityBytes(PhysicalTier.SSD));
    }

    @Test
    void mixOfTodoAndRealNumbersResolvesEachIndependently() throws IOException {
        String yaml = String.join("\n",
                "throughput_bytes_per_sec:",
                "  SSD: 999",
                "  DISK: TODO",
                "  ARCHIVE: TODO",
                "migration_bandwidth_bytes_per_sec: TODO",
                "rate_limit_bytes_per_sec: 42",
                "price_per_gb_hour:",
                "  SSD: TODO",
                "  DISK: TODO",
                "  ARCHIVE: TODO",
                "migration_price_per_gb: TODO",
                "tier_capacity_bytes:",
                "  SSD: TODO",
                "  DISK: TODO",
                "  ARCHIVE: TODO"
        );

        SimConstants constants = load(yaml);

        assertEquals(999L, constants.throughputBytesPerSec(PhysicalTier.SSD));
        assertEquals(42L, constants.rateLimitBytesPerSec());
        assertTrue(constants.throughputBytesPerSec(PhysicalTier.DISK) > 0); // placeholder로 채워짐
    }

    @Test
    void missingFieldThrowsFormatException() {
        String yaml = "rate_limit_bytes_per_sec: 42"; // 나머지 필드 전부 누락

        assertThrows(SimConstantsFormatException.class, () -> load(yaml));
    }

    @Test
    void nonNumericNonTodoValueThrowsFormatException() {
        String yaml = String.join("\n",
                "throughput_bytes_per_sec:",
                "  SSD: not-a-number",
                "  DISK: TODO",
                "  ARCHIVE: TODO",
                "migration_bandwidth_bytes_per_sec: TODO",
                "rate_limit_bytes_per_sec: TODO",
                "price_per_gb_hour:",
                "  SSD: TODO",
                "  DISK: TODO",
                "  ARCHIVE: TODO",
                "migration_price_per_gb: TODO",
                "tier_capacity_bytes:",
                "  SSD: TODO",
                "  DISK: TODO",
                "  ARCHIVE: TODO"
        );

        assertThrows(SimConstantsFormatException.class, () -> load(yaml));
    }

    private static SimConstants load(String yaml) throws IOException {
        try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            return SimConstantsLoader.load(in);
        }
    }
}
