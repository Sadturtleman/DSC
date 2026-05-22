package edu.dsc.tiering.scheduler;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.hdfs.HdfsApiCaller;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BatchScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

    private final PendingJobRepository repo;
    private final HdfsApiCaller hdfs;
    private final AppConfig.Scheduler cfg;
    private final WindowSelector windowSelector;
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BatchScheduler(PendingJobRepository repo,
                          HdfsApiCaller hdfs,
                          AppConfig.Scheduler cfg) {
        this.repo = repo;
        this.hdfs = hdfs;
        this.cfg = cfg;
        this.windowSelector = new WindowSelector(cfg.windows());
        this.workers = Executors.newFixedThreadPool(cfg.concurrency());
    }

    public void run() throws InterruptedException {
        running.set(true);
        log.info("BatchScheduler started (concurrency={}, max-retries={})",
                cfg.concurrency(), cfg.maxRetries());

        while (running.get()) {
            AppConfig.Scheduler.Window w = windowSelector.currentWindow();
            try {
                List<PendingJob> claimed = repo.claimBatch(w.batchSize());
                if (claimed.isEmpty()) {
                    Thread.sleep(cfg.pollIntervalSeconds() * 1000L);
                    continue;
                }
                log.info("window={} claimed={} files", w.name(), claimed.size());
                dispatch(claimed);
                Thread.sleep(w.interBatchWaitMs());
            } catch (SQLException e) {
                log.error("DB error in scheduler loop, backing off", e);
                Thread.sleep(cfg.pollIntervalSeconds() * 1000L);
            }
        }
        log.info("BatchScheduler stopped");
    }

    void dispatch(List<PendingJob> batch) {
        if (batch.isEmpty()) return;
        CountDownLatch latch = new CountDownLatch(batch.size());
        for (PendingJob job : batch) {
            workers.submit(() -> {
                try {
                    hdfs.applyTier(job.filePath(), job.targetTier());
                } catch (java.io.FileNotFoundException e) {
                    log.warn("File deleted before tiering jobId={} path={}", job.jobId(), job.filePath());
                    try {
                        repo.recordHdfsFailure(job.jobId(), "FileNotFoundException: " + e.getMessage(), 0);
                    } catch (SQLException dbex) {
                        log.error("Failed to record permanent failure for jobId={}", job.jobId(), dbex);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("HDFS call failed jobId={} path={}: {}",
                            job.jobId(), job.filePath(), e.toString());
                    try {
                        repo.recordHdfsFailure(job.jobId(), e.toString(), cfg.maxRetries());
                    } catch (SQLException dbex) {
                        log.error("Failed to record HDFS failure for jobId={}", job.jobId(), dbex);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
