package edu.dsc.tiering.scoring;

import edu.dsc.tiering.hdfs.FsImageFetcher;
import edu.dsc.tiering.model.Tier;
import edu.dsc.tiering.repository.PendingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * FSImage를 주기적으로 fetch·파싱하여 이동 대상 파일을 {@code pending_jobs}에 삽입하는
 * Producer Worker (README §3: Scoring Worker).
 *
 * <p>전체 파이프라인에서의 위치:
 * <pre>
 *   ScoringEngine  → PENDING   (본 클래스)
 *   BatchScheduler → DISPATCHED
 *   CompletionTracker → COMPLETED | FAILED
 * </pre>
 *
 * <p>HDFS storagePolicy 코드 → {@link Tier} 매핑 (기본값, YAML 오버라이드 가능):
 * <pre>
 *   0  = WARM (미지정 시 보수적으로 WARM 취급)
 *   2  = COLD
 *   5  = WARM
 *   7  = WARM (HDFS default Hot = DISK)
 *   10 = WARM
 *   12 = HOT
 * </pre>
 *
 * <p>배치 INSERT 전략:
 * {@code ON CONFLICT DO NOTHING} 으로 동일 경로의 활성 job이 이미 존재하면 스킵.
 * 한 번에 {@code BATCH_INSERT_SIZE}개씩 나눠서 INSERT해 트랜잭션 크기를 제한한다.
 */
public class ScoringEngine implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScoringEngine.class);

    /** 한 트랜잭션당 INSERT 행 수 */
    private static final int BATCH_INSERT_SIZE = 500;

    /**
     * storagePolicy 코드 → Tier 매핑.
     * Hadoop 3.x 기본값 기준; 클러스터 커스텀 정책이 있다면 YAML로 오버라이드할 것.
     */
    private static final Map<Integer, Tier> POLICY_TO_TIER = Map.of(
            0,  Tier.WARM,   // unspecified → 보수적으로 WARM 취급
            2,  Tier.COLD,   // Cold
            5,  Tier.WARM,   // Warm
            7,  Tier.WARM,   // HDFS Hot(default DISK) -> service WARM
            10, Tier.WARM,   // One_SSD
            12, Tier.HOT     // All_SSD
    );

    private final FsImageFetcher       fetcher;
    private final PriorityRule         rule;
    private final PendingJobRepository repo;
    private final long                 intervalMs;

    /**
     * @param fetcher     FSImage 다운로드·파싱 담당
     * @param rule        우선순위 계산·티어 결정 담당
     * @param repo        DB 접근 담당
     * @param intervalMs  실행 주기 (ms). 예: 86_400_000L = 24h
     */
    public ScoringEngine(FsImageFetcher fetcher,
                         PriorityRule rule,
                         PendingJobRepository repo,
                         long intervalMs) {
        this.fetcher     = fetcher;
        this.rule        = rule;
        this.repo        = repo;
        this.intervalMs  = intervalMs;
    }

    // ── Runnable ────────────────────────────────────────────────────────

    /**
     * 무한 루프로 실행. {@link Thread#interrupt()}로 종료한다.
     * 한 사이클이 완료되면 {@code intervalMs} 대기 후 재실행한다.
     */
    @Override
    public void run() {
        log.info("ScoringEngine 시작. interval={}s", intervalMs / 1000);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                runCycle();
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("ScoringEngine 종료 (interrupt)");
            } catch (Exception e) {
                log.error("ScoringEngine 사이클 오류 — 다음 주기에 재시도: {}", e.getMessage(), e);
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ── 한 사이클 ────────────────────────────────────────────────────────

    /**
     * FSImage 한 번 fetch → 파싱 → 스코어링 → DB INSERT.
     * {@code package-private}으로 노출해 테스트에서 직접 호출 가능.
     */
    void runCycle() throws IOException {
        Instant start = Instant.now();
        log.info("=== ScoringEngine 사이클 시작 ===");

        // 1. FSImage fetch & 파싱
        List<FileMetadata> allFiles = fetcher.fetchAndParse();
        log.info("FSImage 파싱 완료. 전체 파일 수={}", allFiles.size());

        // 2. 스코어링: 이동 대상 필터링 + 순위 기반 우선순위 계산
        List<ScoredJob> jobs = score(allFiles);
        log.info("이동 대상 파일 수={} / 전체={}", jobs.size(), allFiles.size());
        for (ScoredJob job : jobs) {
            log.info("  이동 대상: path={} {}→{} priority={}",
                    job.meta().path(), job.currentTier(), job.targetTier(), job.priorityScore());
        }

        // 3. DB 배치 INSERT
        int inserted = batchInsert(jobs);

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("=== ScoringEngine 사이클 완료. inserted={} elapsed={}s ===",
                inserted, elapsed.toSeconds());
    }

    // ── 스코어링 ──────────────────────────────────────────────────────────

    List<ScoredJob> score(List<FileMetadata> files) {
        List<Candidate> candidates = new ArrayList<>();
        for (FileMetadata meta : files) {
            Tier currentTier = resolveTier(meta.storagePolicy());
            Tier targetTier  = rule.targetTier(meta, currentTier);
            if (targetTier == null) continue; // 이동 불필요

            candidates.add(new Candidate(meta, currentTier, targetTier));
        }

        rankByAccessTime(candidates);
        rankByFileSize(candidates);

        List<ScoredJob> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(new ScoredJob(
                    candidate.meta,
                    candidate.currentTier,
                    candidate.targetTier,
                    rule.rankScore(candidate.accessRank, candidate.sizeRank)));
        }
        return result;
    }

    /**
     * HDFS storagePolicy 코드를 {@link Tier}로 변환한다.
     * 알 수 없는 코드는 WARM으로 보수적 처리.
     */
    private static Tier resolveTier(int policyCode) {
        return POLICY_TO_TIER.getOrDefault(policyCode, Tier.WARM);
    }

    private static void rankByAccessTime(List<Candidate> candidates) {
        candidates.sort(Comparator
                .comparingLong((Candidate c) -> accessRankKey(c.meta.accessTimeMs()))
                .thenComparing(c -> c.meta.path()));
        assignAccessRanks(candidates);
    }

    private static void rankByFileSize(List<Candidate> candidates) {
        candidates.sort(Comparator
                .comparingLong((Candidate c) -> c.meta.fileSizeBytes()).reversed()
                .thenComparing(c -> c.meta.path()));
        assignSizeRanks(candidates);
    }

    private static long accessRankKey(long accessTimeMs) {
        return accessTimeMs == 0L ? Long.MIN_VALUE : accessTimeMs;
    }

    private static void assignAccessRanks(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).accessRank = i + 1;
        }
    }

    private static void assignSizeRanks(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).sizeRank = i + 1;
        }
    }

    // ── DB INSERT ────────────────────────────────────────────────────────

    private int batchInsert(List<ScoredJob> jobs) {
        int total = 0;
        for (int i = 0; i < jobs.size(); i += BATCH_INSERT_SIZE) {
            List<ScoredJob> chunk = jobs.subList(i,
                    Math.min(i + BATCH_INSERT_SIZE, jobs.size()));
            try {
                int n = repo.insertPendingJobs(chunk);
                total += n;
                log.debug("INSERT chunk [{}-{}]: {}건 삽입", i, i + chunk.size() - 1, n);
            } catch (Exception e) {
                log.error("INSERT 실패 (chunk offset={}): {}", i, e.getMessage(), e);
                // 실패한 청크는 스킵하고 계속 진행 (부분 성공 허용)
            }
        }
        return total;
    }

    // ── 내부 DTO ─────────────────────────────────────────────────────────

    /**
     * 스코어링 결과를 DB INSERT 전까지 임시로 담는 DTO.
     * {@code PendingJobRepository.insertPendingJobs}가 이 타입을 받는다.
     */
    public static final class ScoredJob {
        private final FileMetadata meta;
        private final Tier currentTier;
        private final Tier targetTier;
        private final double priorityScore;

        public ScoredJob(FileMetadata meta, Tier currentTier, Tier targetTier, double priorityScore) {
            this.meta = meta;
            this.currentTier = currentTier;
            this.targetTier = targetTier;
            this.priorityScore = priorityScore;
        }

        public FileMetadata meta() { return meta; }
        public Tier currentTier() { return currentTier; }
        public Tier targetTier() { return targetTier; }
        public double priorityScore() { return priorityScore; }
    }

    private static final class Candidate {
        private final FileMetadata meta;
        private final Tier currentTier;
        private final Tier targetTier;
        private int accessRank;
        private int sizeRank;

        private Candidate(FileMetadata meta, Tier currentTier, Tier targetTier) {
            this.meta = meta;
            this.currentTier = currentTier;
            this.targetTier = targetTier;
        }
    }
}
