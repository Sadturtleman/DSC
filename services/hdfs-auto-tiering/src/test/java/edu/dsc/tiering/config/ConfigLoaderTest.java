package edu.dsc.tiering.config;

import edu.dsc.tiering.model.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigLoaderTest {

    @Test
    void loadsCompleteConfigFromClasspath() throws Exception {
        AppConfig cfg = ConfigLoader.fromClasspath("test-application.yaml");
        assertNotNull(cfg);

        // database
        assertEquals("jdbc:postgresql://test:5432/test", cfg.database().url());
        assertEquals("tu", cfg.database().username());
        assertEquals("tp", cfg.database().password());
        assertEquals(5, cfg.database().pool().maximumPoolSize());
        assertEquals(1, cfg.database().pool().minimumIdle());

        // hdfs + tier mapping (kebab-case → camelCase, enum keys)
        assertEquals("hdfs://nn:8020", cfg.hdfs().fsDefaultName());
        assertEquals("hdfs", cfg.hdfs().user());
        assertEquals("ALL_SSD", cfg.hdfs().policyMapping().get(Tier.HOT));
        assertEquals("ONE_SSD", cfg.hdfs().policyMapping().get(Tier.WARM));
        assertEquals("COLD", cfg.hdfs().policyMapping().get(Tier.COLD));

        // scheduler + windows
        assertEquals(5, cfg.scheduler().pollIntervalSeconds());
        assertEquals(4, cfg.scheduler().concurrency());
        assertEquals(3, cfg.scheduler().maxRetries());
        assertEquals(2, cfg.scheduler().windows().size());

        var day = cfg.scheduler().windows().get(0);
        assertEquals("daytime", day.name());
        assertEquals("09:00", day.start());
        assertEquals("18:00", day.end());
        assertEquals(50, day.batchSize());
        assertEquals(2000L, day.interBatchWaitMs());

        var night = cfg.scheduler().windows().get(1);
        assertEquals("nighttime", night.name());
        assertEquals(500, night.batchSize());
        assertEquals(500L, night.interBatchWaitMs());
    }
}
