package edu.dsc.tiering;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.config.ConfigLoader;
import edu.dsc.tiering.hdfs.FsImageFetcher;
import edu.dsc.tiering.hdfs.HdfsApiCaller;
import edu.dsc.tiering.hdfs.HdfsPolicyChecker;
import edu.dsc.tiering.repository.PendingJobRepository;
import edu.dsc.tiering.scheduler.BatchScheduler;
import edu.dsc.tiering.scoring.PriorityRule;
import edu.dsc.tiering.scoring.ScoringEngine;
import edu.dsc.tiering.tracking.CompletionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppConfig cfg = args.length > 0
                ? ConfigLoader.fromFile(Paths.get(args[0]))
                : ConfigLoader.fromClasspath("application.yaml");
        log.info("loaded config (db={}, namenode={})",
                cfg.database().url(), cfg.hdfs().fsDefaultName());

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.database().url());
        hc.setUsername(cfg.database().username());
        hc.setPassword(cfg.database().password());
        hc.setMaximumPoolSize(cfg.database().pool().maximumPoolSize());
        hc.setMinimumIdle(cfg.database().pool().minimumIdle());
        hc.setPoolName("dsc-tiering-pool");

        try (HikariDataSource ds = new HikariDataSource(hc);
             HdfsApiCaller hdfs = new HdfsApiCaller(cfg.hdfs());
             HdfsPolicyChecker checker = new HdfsPolicyChecker(
                     cfg.hdfs().fsDefaultName(), System.getenv("HADOOP_CONF_DIR"),
                     cfg.tracker().completionRatio())) {
            PendingJobRepository repo = new PendingJobRepository(ds);
            try (BatchScheduler scheduler = new BatchScheduler(repo, hdfs, cfg.scheduler())) {
                Thread scoringThread = startScoringIfEnabled(cfg, repo);
                Thread trackerThread = new Thread(
                        new CompletionTracker(cfg.tracker(), repo, checker),
                        "completion-tracker");
                trackerThread.setDaemon(true);
                trackerThread.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("shutdown signal received");
                    scheduler.stop();
                    interrupt(scoringThread);
                    trackerThread.interrupt();
                }, "shutdown-hook"));

                try {
                    scheduler.run();
                } finally {
                    stop(scoringThread);
                    stop(trackerThread);
                }
            }
        }
    }

    private static Thread startScoringIfEnabled(AppConfig cfg, PendingJobRepository repo) {
        AppConfig.Scoring scoring = cfg.scoring();
        if (!scoring.enabled()) {
            log.info("ScoringEngine disabled");
            return null;
        }

        Thread thread = new Thread(() -> {
            String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
            try (FsImageFetcher fetcher = new FsImageFetcher(hadoopConfDir, scoring.localFsimageDir())) {
                PriorityRule rule = new PriorityRule(
                        scoring.weightAccessTime(), scoring.weightFileSize());
                long intervalMs = scoring.intervalSeconds() * 1000L;
                new ScoringEngine(fetcher, rule, repo, intervalMs).run();
            } catch (Exception e) {
                log.error("ScoringEngine stopped", e);
            }
        }, "scoring-engine");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stop(Thread thread) throws InterruptedException {
        if (thread == null) return;
        thread.interrupt();
        thread.join(5000);
    }

    private static void interrupt(Thread thread) {
        if (thread != null) thread.interrupt();
    }
}
