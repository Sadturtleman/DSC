package edu.dsc.tiering.config;

import edu.dsc.tiering.model.Tier;

import java.util.List;
import java.util.Map;

public record AppConfig(
        Database database,
        Hdfs hdfs,
        Scheduler scheduler
) {
    public record Database(
            String url,
            String username,
            String password,
            Pool pool
    ) {
        public record Pool(int maximumPoolSize, int minimumIdle) {}
    }

    public record Hdfs(
            String fsDefaultName,
            String user,
            Map<Tier, String> policyMapping
    ) {}

    public record Scheduler(
            int pollIntervalSeconds,
            List<Window> windows,
            int concurrency,
            int maxRetries
    ) {
        public record Window(
                String name,
                String start,
                String end,
                int batchSize,
                long interBatchWaitMs
        ) {}
    }
}
