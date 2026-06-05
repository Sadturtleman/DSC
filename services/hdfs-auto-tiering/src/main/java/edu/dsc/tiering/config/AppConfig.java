package edu.dsc.tiering.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.dsc.tiering.model.Tier;

import java.util.List;
import java.util.Map;

public final class AppConfig {

    private final Database database;
    private final Hdfs hdfs;
    private final Scoring scoring;
    private final Scheduler scheduler;
    private final TrackerSettings tracker;

    @JsonCreator
    public AppConfig(@JsonProperty("database") Database database,
                     @JsonProperty("hdfs") Hdfs hdfs,
                     @JsonProperty("scoring") Scoring scoring,
                     @JsonProperty("scheduler") Scheduler scheduler,
                     @JsonProperty("tracker") TrackerSettings tracker) {
        this.database = database;
        this.hdfs = hdfs;
        this.scoring = scoring == null ? Scoring.disabledDefaults() : scoring;
        this.scheduler = scheduler == null ? Scheduler.defaults() : scheduler;
        this.tracker = tracker == null ? TrackerSettings.defaults() : tracker;
    }

    public Database database() { return database; }
    public Hdfs hdfs() { return hdfs; }
    public Scoring scoring() { return scoring; }
    public Scheduler scheduler() { return scheduler; }
    public TrackerSettings tracker() { return tracker; }

    // ── Database ──────────────────────────────────────────────────────────

    public static final class Database {
        private final String url;
        private final String username;
        private final String password;
        private final Pool pool;

        @JsonCreator
        public Database(@JsonProperty("url") String url,
                        @JsonProperty("username") String username,
                        @JsonProperty("password") String password,
                        @JsonProperty("pool") Pool pool) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.pool = pool == null ? Pool.defaults() : pool;
        }

        public String url() { return url; }
        public String username() { return username; }
        public String password() { return password; }
        public Pool pool() { return pool; }

        public static final class Pool {
            private final int maximumPoolSize;
            private final int minimumIdle;

            @JsonCreator
            public Pool(@JsonProperty("maximumPoolSize")
                        @JsonAlias("maximum-pool-size") Integer maximumPoolSize,
                        @JsonProperty("minimumIdle")
                        @JsonAlias("minimum-idle") Integer minimumIdle) {
                this.maximumPoolSize = maximumPoolSize == null ? 8 : maximumPoolSize;
                this.minimumIdle = minimumIdle == null ? 2 : minimumIdle;
            }

            static Pool defaults() {
                return new Pool(8, 2);
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

        @JsonCreator
        public Hdfs(@JsonProperty("fsDefaultName")
                    @JsonAlias("fs-default-name") String fsDefaultName,
                    @JsonProperty("user") String user,
                    @JsonProperty("policyMapping")
                    @JsonAlias("policy-mapping") Map<Tier, String> policyMapping) {
            this.fsDefaultName = fsDefaultName;
            this.user = user == null ? "" : user;
            this.policyMapping = (policyMapping == null || policyMapping.isEmpty())
                    ? Map.of(Tier.HOT, "ALL_SSD", Tier.WARM, "ONE_SSD", Tier.COLD, "COLD")
                    : policyMapping;
        }

        public String fsDefaultName() { return fsDefaultName; }
        public String user() { return user; }
        public Map<Tier, String> policyMapping() { return policyMapping; }
    }

    // -- Scoring -----------------------------------------------------------

    public static final class Scoring {
        private final boolean enabled;
        private final long intervalSeconds;
        private final double weightAccessTime;
        private final double weightFileSize;
        private final String localFsimageDir;

        @JsonCreator
        public Scoring(@JsonProperty("enabled") Boolean enabled,
                       @JsonProperty("intervalSeconds")
                       @JsonAlias("interval-seconds") Long intervalSeconds,
                       @JsonProperty("weightAccessTime")
                       @JsonAlias("weight-access-time") Double weightAccessTime,
                       @JsonProperty("weightFileSize")
                       @JsonAlias("weight-file-size") Double weightFileSize,
                       @JsonProperty("localFsimageDir")
                       @JsonAlias("local-fsimage-dir") String localFsimageDir) {
            this.enabled = enabled != null && enabled;
            this.intervalSeconds = intervalSeconds == null ? 86400L : intervalSeconds;
            this.weightAccessTime = weightAccessTime == null ? 0.5 : weightAccessTime;
            this.weightFileSize = weightFileSize == null ? 0.5 : weightFileSize;
            this.localFsimageDir = (localFsimageDir == null || localFsimageDir.isBlank())
                    ? "/tmp/hdfs-auto-tiering-fsimage"
                    : localFsimageDir;
        }

        static Scoring disabledDefaults() {
            return new Scoring(false, 86400L, 0.5, 0.5, "/tmp/hdfs-auto-tiering-fsimage");
        }

        public boolean enabled() { return enabled; }
        public long intervalSeconds() { return intervalSeconds; }
        public double weightAccessTime() { return weightAccessTime; }
        public double weightFileSize() { return weightFileSize; }
        public String localFsimageDir() { return localFsimageDir; }
    }

    public static final class Scheduler {
        private final int pollIntervalSeconds;
        private final List<Window> windows;
        private final int concurrency;
        private final int maxRetries;

        @JsonCreator
        public Scheduler(@JsonProperty("pollIntervalSeconds")
                         @JsonAlias("poll-interval-seconds") Integer pollIntervalSeconds,
                         @JsonProperty("windows") List<Window> windows,
                         @JsonProperty("concurrency") Integer concurrency,
                         @JsonProperty("maxRetries")
                         @JsonAlias("max-retries") Integer maxRetries) {
            this.pollIntervalSeconds = pollIntervalSeconds == null ? 10 : pollIntervalSeconds;
            this.windows = (windows == null || windows.isEmpty())
                    ? List.of(new Window("allday", "00:00", "23:59", 50, 1000L))
                    : windows;
            this.concurrency = concurrency == null ? 8 : concurrency;
            this.maxRetries = maxRetries == null ? 3 : maxRetries;
        }

        static Scheduler defaults() {
            return new Scheduler(10, null, 8, 3);
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

            @JsonCreator
            public Window(@JsonProperty("name") String name,
                          @JsonProperty("start") String start,
                          @JsonProperty("end") String end,
                          @JsonProperty("batchSize")
                          @JsonAlias("batch-size") Integer batchSize,
                          @JsonProperty("interBatchWaitMs")
                          @JsonAlias("inter-batch-wait-ms") Long interBatchWaitMs) {
                this.name = (name == null || name.isBlank()) ? "allday" : name;
                this.start = (start == null || start.isBlank()) ? "00:00" : start;
                this.end = (end == null || end.isBlank()) ? "23:59" : end;
                this.batchSize = batchSize == null ? 50 : batchSize;
                this.interBatchWaitMs = interBatchWaitMs == null ? 1000L : interBatchWaitMs;
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
        private final int maxRetryCount;

        @JsonCreator
        public TrackerSettings(@JsonProperty("pollIntervalSeconds")
                               @JsonAlias("poll-interval-seconds") Integer pollIntervalSeconds,
                               @JsonProperty("timeoutMinutes")
                               @JsonAlias("timeout-minutes") Integer timeoutMinutes,
                               @JsonProperty("batchSize")
                               @JsonAlias("batch-size") Integer batchSize,
                               @JsonProperty("completionRatio")
                               @JsonAlias("completion-ratio") Double completionRatio,
                               @JsonProperty("maxWorkers")
                               @JsonAlias("max-workers") Integer maxWorkers,
                               @JsonProperty("nodenameSemaphore")
                               @JsonAlias({"nodename-semaphore", "namenode-semaphore", "namenodeSemaphore"}) Integer nodenameSemaphore,
                               @JsonProperty("maxRetryCount")
                               @JsonAlias("max-retry-count") Integer maxRetryCount) {
            this.pollIntervalSeconds = pollIntervalSeconds == null ? 45 : pollIntervalSeconds;
            this.timeoutMinutes = timeoutMinutes == null ? 60 : timeoutMinutes;
            this.batchSize = batchSize == null ? 20 : batchSize;
            this.completionRatio = completionRatio == null ? 0.95 : completionRatio;
            this.maxWorkers = maxWorkers == null ? 5 : maxWorkers;
            this.nodenameSemaphore = nodenameSemaphore == null ? 3 : nodenameSemaphore;
            this.maxRetryCount = maxRetryCount == null ? 5 : maxRetryCount;
        }

        static TrackerSettings defaults() {
            return new TrackerSettings(45, 60, 20, 0.95, 5, 3, 5);
        }

        public int pollIntervalSeconds() { return pollIntervalSeconds; }
        public int timeoutMinutes() { return timeoutMinutes; }
        public int batchSize() { return batchSize; }
        public double completionRatio() { return completionRatio; }
        public int maxWorkers() { return maxWorkers; }
        public int nodenameSemaphore() { return nodenameSemaphore; }
        public int maxRetryCount() { return maxRetryCount; }
    }
}
