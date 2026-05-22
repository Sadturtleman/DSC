package edu.dsc.tiering.config;

import edu.dsc.tiering.model.Tier;

import java.util.List;
import java.util.Map;

public final class AppConfig {

    private final Database database;
    private final Hdfs hdfs;
    private final Scheduler scheduler;
    private final TrackerSettings tracker;

    public AppConfig(Database database, Hdfs hdfs, Scheduler scheduler, TrackerSettings tracker) {
        this.database = database;
        this.hdfs = hdfs;
        this.scheduler = scheduler;
        this.tracker = tracker;
    }

    public Database database() { return database; }
    public Hdfs hdfs() { return hdfs; }
    public Scheduler scheduler() { return scheduler; }
    public TrackerSettings tracker() { return tracker; }

    // ── Database ──────────────────────────────────────────────────────────

    public static final class Database {
        private final String url;
        private final String username;
        private final String password;
        private final Pool pool;

        public Database(String url, String username, String password, Pool pool) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.pool = pool;
        }

        public String url() { return url; }
        public String username() { return username; }
        public String password() { return password; }
        public Pool pool() { return pool; }

        public static final class Pool {
            private final int maximumPoolSize;
            private final int minimumIdle;

            public Pool(int maximumPoolSize, int minimumIdle) {
                this.maximumPoolSize = maximumPoolSize;
                this.minimumIdle = minimumIdle;
            }

            public int maximumPoolSize() { return maximumPoolSize; }
            public int minimumIdle() { return minimumIdle; }
        }
    }

    // ── Hdfs ──────────────────────────────────────────────────────────────

    public static final class Hdfs {
        private final String fsDefaultName;
        private final String user;
        private final Map<Tier, String> policyMapping;

        public Hdfs(String fsDefaultName, String user, Map<Tier, String> policyMapping) {
            this.fsDefaultName = fsDefaultName;
            this.user = user;
            this.policyMapping = policyMapping;
        }

        public String fsDefaultName() { return fsDefaultName; }
        public String user() { return user; }
        public Map<Tier, String> policyMapping() { return policyMapping; }
    }

    // ── Scheduler ─────────────────────────────────────────────────────────

    public static final class Scheduler {
        private final int pollIntervalSeconds;
        private final List<Window> windows;
        private final int concurrency;
        private final int maxRetries;

        public Scheduler(int pollIntervalSeconds, List<Window> windows, int concurrency, int maxRetries) {
            this.pollIntervalSeconds = pollIntervalSeconds;
            this.windows = windows;
            this.concurrency = concurrency;
            this.maxRetries = maxRetries;
        }

        public int pollIntervalSeconds() { return pollIntervalSeconds; }
        public List<Window> windows() { return windows; }
        public int concurrency() { return concurrency; }
        public int maxRetries() { return maxRetries; }

        public static final class Window {
            private final String name;
            private final String start;
            private final String end;
            private final int batchSize;
            private final long interBatchWaitMs;

            public Window(String name, String start, String end, int batchSize, long interBatchWaitMs) {
                this.name = name;
                this.start = start;
                this.end = end;
                this.batchSize = batchSize;
                this.interBatchWaitMs = interBatchWaitMs;
            }

            public String name() { return name; }
            public String start() { return start; }
            public String end() { return end; }
            public int batchSize() { return batchSize; }
            public long interBatchWaitMs() { return interBatchWaitMs; }
        }
    }

    // ── TrackerSettings ───────────────────────────────────────────────────

    public static final class TrackerSettings {
        private final int pollIntervalSeconds;
        private final int timeoutMinutes;
        private final int batchSize;
        private final double completionRatio;
        private final int maxWorkers;
        private final int nodenameSemaphore;

        public TrackerSettings(int pollIntervalSeconds, int timeoutMinutes, int batchSize,
                               double completionRatio, int maxWorkers, int nodenameSemaphore) {
            this.pollIntervalSeconds = pollIntervalSeconds;
            this.timeoutMinutes = timeoutMinutes;
            this.batchSize = batchSize;
            this.completionRatio = completionRatio;
            this.maxWorkers = maxWorkers;
            this.nodenameSemaphore = nodenameSemaphore;
        }

        public int pollIntervalSeconds() { return pollIntervalSeconds; }
        public int timeoutMinutes() { return timeoutMinutes; }
        public int batchSize() { return batchSize; }
        public double completionRatio() { return completionRatio; }
        public int maxWorkers() { return maxWorkers; }
        public int nodenameSemaphore() { return nodenameSemaphore; }
    }
}
