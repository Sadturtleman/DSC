package edu.dsc.tiering.tracking;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.hdfs.HdfsPolicyChecker;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Polls DISPATCHED/IN_PROGRESS jobs and marks them COMPLETED or FAILED.
 */
public class CompletionTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CompletionTracker.class);

    private final AppConfig.TrackerSettings cfg;
    private final PendingJobRepository repo;
    private final HdfsPolicyChecker checker;
    private final Semaphore nnSemaphore;

    public CompletionTracker(AppConfig.TrackerSettings cfg,
                             PendingJobRepository repo,
                             HdfsPolicyChecker checker) {
        this.cfg = cfg;
        this.repo = repo;
        this.checker = checker;
        this.nnSemaphore = new Semaphore(cfg.nodenameSemaphore());
    }

    @Override
    public void run() {
        log.info("CompletionTracker started. pollInterval={}s timeout={}min batchSize={}",
                cfg.pollIntervalSeconds(), cfg.timeoutMinutes(), cfg.batchSize());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCycle();
                Thread.sleep(cfg.pollIntervalSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("CompletionTracker stopped (interrupt)");
            } catch (Exception e) {
                log.error("CompletionTracker loop error: {}", e.getMessage(), e);
            }
        }
    }

    void runCycle() {
        try {
            List<PendingJob> jobs = repo.claimTrackableBatch(cfg.batchSize());
            if (jobs.isEmpty()) {
                log.debug("trackable job empty");
                return;
            }
            log.info("tracker cycle jobs={}", jobs.size());

            ExecutorService pool = Executors.newFixedThreadPool(cfg.maxWorkers(), daemonThreadFactory());
            List<Future<Boolean>> futures = jobs.stream()
                    .<Future<Boolean>>map(job -> pool.submit(() -> checkOne(job)))
                    .collect(Collectors.toList());
            pool.shutdown();
            try {
                boolean finished = pool.awaitTermination(
                        cfg.pollIntervalSeconds() * 1000L, TimeUnit.MILLISECONDS);
                if (!finished) {
                    log.warn("Some HDFS checks did not finish within pollInterval; retrying next cycle");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                return;
            }

            for (int i = 0; i < jobs.size(); i++) {
                PendingJob job = jobs.get(i);
                if (job.dispatchedAt() == null) {
                    repo.markFailed(job.jobId());
                    log.warn("trackable job has no dispatched_at id={} path={}",
                            job.jobId(), job.filePath());
                    continue;
                }

                Duration elapsed = Duration.between(
                        job.dispatchedAt(), java.time.OffsetDateTime.now());
                if (elapsed.toMinutes() >= cfg.timeoutMinutes()) {
                    int retryCount = repo.getRetryCount(job.jobId());
                    if (retryCount < cfg.maxRetryCount()) {
                        // 재시도 : pending으로 복귀
                        repo.markFailed(job.jobId());
                        log.warn("TIMEOUT id={} path={} elapsed={}min",
                                job.jobId(), job.filePath(), elapsed.toMinutes());
                    }
                    else{
                        // 재시도 횟수 초과
                        repo.markFailedPermanently(job.jobId());
                        log.error("TIMEOUT→FAILED(permanent) id={} path={} attempts={}",
                                job.jobId(), job.filePath(), retryCount);
                    }
                    continue;
                }

                Future<Boolean> future = futures.get(i);
                if (future.isCancelled()) {
                    repo.touchCheckedAt(job.jobId());
                    log.warn("HDFS check was cancelled id={} path={}", job.jobId(), job.filePath());
                    continue;
                }
                if (!future.isDone()) {
                    future.cancel(true);
                    repo.touchCheckedAt(job.jobId());
                    log.warn("HDFS check timed out id={} path={}", job.jobId(), job.filePath());
                    continue;
                }



                boolean done;
                try {
                    done = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (CancellationException e) {
                    repo.touchCheckedAt(job.jobId());
                    log.warn("HDFS check was cancelled id={} path={}", job.jobId(), job.filePath());
                    continue;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException && cause.getMessage() != null && cause.getMessage().startsWith("FileNotFoundException")) {
                        log.warn("File deleted during tracking id={} path={}", job.jobId(), job.filePath());
                        repo.recordHdfsFailure(job.jobId(), cause.getMessage(), 0);
                        continue;
                    }
                    log.error("HDFS check error id={} path={}: {}",
                            job.jobId(), job.filePath(),
                            cause == null ? e.getMessage() : cause.getMessage());
                    repo.touchCheckedAt(job.jobId());
                    continue;
                }

                if (done) {
                    repo.markCompleted(job.jobId());
                } else {
                    repo.touchCheckedAt(job.jobId());
                    log.debug("move in progress id={} path={}", job.jobId(), job.filePath());
                }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkOne(PendingJob job) throws InterruptedException {
        nnSemaphore.acquire();
        try {
            return checker.isSatisfied(job.filePath(), job.targetTier());
        } finally {
            nnSemaphore.release();
        }
    }

    private ThreadFactory daemonThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "completion-checker");
            thread.setDaemon(true);
            return thread;
        };
    }
}
