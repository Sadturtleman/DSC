package edu.dsc.tiering.config;

import edu.dsc.tiering.model.Tier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        // scoring
        assertEquals(false, cfg.scoring().enabled());
        assertEquals(86400L, cfg.scoring().intervalSeconds());
        assertEquals(0.5, cfg.scoring().weightAccessTime());
        assertEquals(0.5, cfg.scoring().weightFileSize());
        assertEquals(List.of("/test/auto-tiering-e2e", "/test/scenario_e2e"),
                cfg.scoring().targetDirectories());

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

    @Test
    void alsoLoadsCamelCaseConfigFromFile() throws Exception {
        Path config = Files.createTempFile("hdfs-auto-tiering", ".yaml");
        Files.writeString(config,
                "database:\n" +
                "  url: jdbc:postgresql://test:5432/test\n" +
                "  username: tu\n" +
                "  password: tp\n" +
                "  pool:\n" +
                "    maximumPoolSize: 5\n" +
                "    minimumIdle: 1\n" +
                "hdfs:\n" +
                "  fsDefaultName: hdfs://nn:8020\n" +
                "  user: hdfs\n" +
                "  policyMapping:\n" +
                "    HOT: ALL_SSD\n" +
                "    WARM: ONE_SSD\n" +
                "    COLD: COLD\n" +
                "scoring:\n" +
                "  enabled: true\n" +
                "  intervalSeconds: 3600\n" +
                "  weightAccessTime: 0.6\n" +
                "  weightFileSize: 0.4\n" +
                "  localFsimageDir: /tmp/fsimage\n" +
                "  targetDirectories:\n" +
                "    - /test/auto-tiering-e2e\n" +
                "    - /test/scenario_e2e/\n" +
                "scheduler:\n" +
                "  pollIntervalSeconds: 5\n" +
                "  windows:\n" +
                "    - name: allday\n" +
                "      start: \"00:00\"\n" +
                "      end: \"23:59\"\n" +
                "      batchSize: 50\n" +
                "      interBatchWaitMs: 500\n" +
                "  concurrency: 4\n" +
                "  maxRetries: 3\n" +
                "tracker:\n" +
                "  pollIntervalSeconds: 45\n" +
                "  timeoutMinutes: 60\n" +
                "  batchSize: 20\n" +
                "  completionRatio: 0.95\n" +
                "  maxWorkers: 5\n" +
                "  nodenameSemaphore: 3\n");
        try {
            AppConfig cfg = ConfigLoader.fromFile(config);

            assertEquals("hdfs://nn:8020", cfg.hdfs().fsDefaultName());
            assertEquals(5, cfg.database().pool().maximumPoolSize());
            assertEquals(true, cfg.scoring().enabled());
            assertEquals(3600L, cfg.scoring().intervalSeconds());
            assertEquals(List.of("/test/auto-tiering-e2e", "/test/scenario_e2e"),
                    cfg.scoring().targetDirectories());
            assertEquals(500L, cfg.scheduler().windows().get(0).interBatchWaitMs());
            assertEquals(5, cfg.tracker().maxWorkers());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void appliesDefaultsForOptionalRuntimeSections() throws Exception {
        Path config = Files.createTempFile("hdfs-auto-tiering-minimal", ".yaml");
        Files.writeString(config,
                "database:\n" +
                "  url: jdbc:postgresql://test:5432/test\n" +
                "  username: tu\n" +
                "  password: tp\n" +
                "hdfs:\n" +
                "  fs-default-name: hdfs://nn:8020\n");
        try {
            AppConfig cfg = ConfigLoader.fromFile(config);

            assertEquals(8, cfg.database().pool().maximumPoolSize());
            assertEquals(2, cfg.database().pool().minimumIdle());
            assertEquals("", cfg.hdfs().user());
            assertEquals("ALL_SSD", cfg.hdfs().policyMapping().get(Tier.HOT));
            assertEquals(false, cfg.scoring().enabled());
            assertEquals(List.of(), cfg.scoring().targetDirectories());
            assertEquals(10, cfg.scheduler().pollIntervalSeconds());
            assertEquals(1, cfg.scheduler().windows().size());
            assertEquals(0.95, cfg.tracker().completionRatio());
            assertEquals(3, cfg.tracker().nodenameSemaphore());
        } finally {
            Files.deleteIfExists(config);
        }
    }
}
