package edu.dsc.tiering.tracking;

import edu.dsc.tiering.config.AppConfig;
import edu.dsc.tiering.hdfs.HdfsPolicyChecker;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * DISPATCHED 상태 job 을 주기적으로 폴링해 블록 이동 완료 여부를 검증하고
 * {@code COMPLETED} 또는 {@code FAILED} 로 상태를 전이시키는 Worker 스레드.
 *
 * <p>전체 시스템에서의 위치:
 * <pre>
 *   ScoringEngine  → PENDING   job 생성
 *   BatchScheduler → PENDING   → DISPATCHED  (HdfsApiCaller 호출 후)
 *   CompletionTracker → DISPATCHED → COMPLETED | FAILED  ← 본 클래스
 * </pre>
 *
 * <p>트랜잭션 설계:
 * <ol>
 *   <li>{@code claimBatch} — FOR UPDATE SKIP LOCKED 로 행 점유 후 즉시 커밋 (락 해제)</li>
 *   <li>HDFS 병렬 검사 — 락 없는 상태에서 수행 (느린 RPC 동안 PG 락 점유 방지)</li>
 *   <li>결과 UPDATE   — 짧은 개별 트랜잭션으로 반영</li>
 * </ol>
 *
 * <p>NameNode 부하 제어:
 * {@link Semaphore} 로 동시 {@code getBlockLocations} 호출 수를 {@code namenode-semaphore}
 * 설정값으로 제한한다. BatchScheduler 의 {@code concurrency} 와 독립적으로 조정 가능.
 */
public class CompletionTracker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CompletionTracker.class);

    private final AppConfig.TrackerSettings cfg;
    private final PendingJobRepository      repo;
    private final HdfsPolicyChecker         checker;
    private final Semaphore                 nnSemaphore;

    public CompletionTracker(AppConfig.TrackerSettings cfg,
                             PendingJobRepository repo,
                             HdfsPolicyChecker checker) {
        this.cfg         = cfg;
        this.repo        = repo;
        this.checker     = checker;
        this.nnSemaphore = new Semaphore(cfg.nodenameSemaphore());
    }

    // ── Runnable ────────────────────────────────────────────────────────

    /**
     * 무한 루프. {@code Main} 에서 별도 스레드로 기동하며
     * {@link Thread#interrupt()} 로 종료한다.
     */
    @Override
    public void run() {
        log.info("CompletionTracker 시작. pollInterval={}s timeout={}min batchSize={}",
                cfg.pollIntervalSeconds(), cfg.timeoutMinutes(), cfg.batchSize());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCycle();
                Thread.sleep(cfg.pollIntervalSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("CompletionTracker 종료 (interrupt)");
            } catch (Exception e) {
                // 루프 자체는 유지 — 다음 사이클에서 재시도
                log.error("CompletionTracker 루프 오류: {}", e.getMessage(), e);
            }
        }
    }

    // ── 한 사이클 ────────────────────────────────────────────────────────

    /**
     * 한 폴링 사이클을 실행한다.
     * {@code package-private} 으로 노출해 {@link CompletionTrackerTest} 에서 직접 호출한다.
     */
    void runCycle() {
        try {
            // Step 1: DISPATCHED 행 점유 → 즉시 커밋 (락 해제)
            List<PendingJob> jobs = repo.claimBatch(cfg.batchSize());
            if (jobs.isEmpty()) {
                log.debug("DISPATCHED job 없음");
                return;
            }
            log.info("사이클 대상: {}개", jobs.size());

            // Step 2: HDFS 병렬 검사
            ExecutorService pool = Executors.newFixedThreadPool(cfg.maxWorkers());
            List<Future<Boolean>> futures = jobs.stream()
                    .<Future<Boolean>>map(job -> pool.submit(() -> checkOne(job)))
                    .collect(Collectors.toList());
            pool.shutdown();
            try {
                // 최대 pollInterval 만큼 대기 — 초과 시 다음 사이클에서 재처리
                pool.awaitTermination(cfg.pollIntervalSeconds() * 1000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Step 3: 결과 DB 반영
            for (int i = 0; i < jobs.size(); i++) {
                PendingJob job    = jobs.get(i);
                Duration      elapsed = Duration.between(job.dispatchedAt(), java.time.OffsetDateTime.now());

                // 타임아웃 판정 — HDFS 결과보다 우선
                if (elapsed.toMinutes() >= cfg.timeoutMinutes()) {
                    repo.markFailed(job.jobId());
                    log.warn("TIMEOUT id={} path={} elapsed={}min",
                            job.jobId(), job.filePath(), elapsed.toMinutes());
                    continue;
                }

                boolean done;
                try {
                    done = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    // HDFS 오류: DISPATCHED 유지, 다음 사이클에서 재시도
                    log.error("HDFS 검사 오류 id={} path={}: {}",
                            job.jobId(), job.filePath(), e.getCause().getMessage());
                    repo.touchCheckedAt(job.jobId());
                    continue;
                }

                if (done) {
                    repo.markCompleted(job.jobId());
                } else {
                    repo.touchCheckedAt(job.jobId());
                    log.debug("이동 중 id={} path={}", job.jobId(), job.filePath());
                }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── 단일 파일 검사 ─────────────────────────────────────────────────

    private boolean checkOne(PendingJob job) {
        // Semaphore 로 NameNode 동시 RPC 수 제한
        nnSemaphore.acquireUninterruptibly();
        try {
            return checker.isSatisfied(job.filePath(), job.targetTier());
        } finally {
            nnSemaphore.release();
        }
    }
}
