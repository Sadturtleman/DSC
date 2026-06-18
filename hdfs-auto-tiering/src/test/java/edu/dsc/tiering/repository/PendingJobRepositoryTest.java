package edu.dsc.tiering.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.dsc.tiering.model.JobStatus;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.model.Tier;
import edu.dsc.tiering.scoring.FileMetadata;
import edu.dsc.tiering.scoring.ScoringEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 PostgreSQL 컨테이너에 대해 repository 쿼리를 검증.
 * <p>
 * 호스트에 Docker 가 떠 있어야 한다 — Docker Desktop / Colima / WSL2 docker engine 등.
 * 컨테이너 부팅 비용 때문에 클래스 전체에서 한 번만 띄운다 (정적 @Container).
 */
@Testcontainers
class PendingJobRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dsc_tiering")
            .withUsername("dsc")
            .withPassword("dsc");

    private HikariDataSource ds;
    private PendingJobRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(PG.getJdbcUrl());
        hc.setUsername(PG.getUsername());
        hc.setPassword(PG.getPassword());
        hc.setMaximumPoolSize(4);
        ds = new HikariDataSource(hc);

        // 테스트 간 격리: 스키마를 매번 재생성
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS pending_jobs");
            s.execute("DROP TYPE  IF EXISTS job_status");
            s.execute("DROP TYPE  IF EXISTS tier");
        }
        TestSchema.apply(ds);
        repo = new PendingJobRepository(ds);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void claimBatchReturnsTopPriorityAndTransitionsToDispatched() throws Exception {
        insertPending("/low",  150.0);
        insertPending("/high", 1.0);
        insertPending("/mid",  100.0);

        List<PendingJob> claimed = repo.claimBatch(2);

        assertEquals(2, claimed.size());
        assertEquals("/high", claimed.get(0).filePath());
        assertEquals("/mid",  claimed.get(1).filePath());
        assertEquals(JobStatus.DISPATCHED, claimed.get(0).status());
        assertEquals(JobStatus.DISPATCHED, claimed.get(1).status());
        // 미선택된 /low 는 PENDING 유지
        assertEquals("PENDING", statusOf("/low"));
    }

    @Test
    void claimBatchReturnsEmptyWhenNoPending() throws Exception {
        assertTrue(repo.claimBatch(10).isEmpty());
    }

    @Test
    void claimBatchOnlyTouchesPendingNotOtherStatuses() throws Exception {
        long completedId = insertPending("/done", 999.0);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE pending_jobs SET status='COMPLETED' WHERE job_id = " + completedId);
        }
        insertPending("/p", 1.0);

        List<PendingJob> claimed = repo.claimBatch(10);

        assertEquals(1, claimed.size());
        assertEquals("/p", claimed.get(0).filePath());
    }

    @Test
    void claimTrackableBatchMovesDispatchedToInProgressAndIgnoresPending() throws Exception {
        insertPending("/waiting", 999.0);
        long dispatchedId = insertPending("/moving", 100.0);
        setDispatched(dispatchedId, 0);

        List<PendingJob> claimed = repo.claimTrackableBatch(10);

        assertEquals(1, claimed.size());
        assertEquals("/moving", claimed.get(0).filePath());
        assertEquals(JobStatus.IN_PROGRESS, claimed.get(0).status());
        assertEquals("PENDING", statusOf("/waiting"));
        assertEquals("IN_PROGRESS", statusOf("/moving"));
    }

    @Test
    void concurrentClaimersNeverOverlap() throws Exception {
        for (int i = 0; i < 20; i++) insertPending("/f" + i, i);

        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<List<PendingJob>> a = ex.submit(() -> repo.claimBatch(10));
            Future<List<PendingJob>> b = ex.submit(() -> repo.claimBatch(10));

            Set<Long> idsA = a.get().stream().map(PendingJob::jobId).collect(Collectors.toSet());
            Set<Long> idsB = b.get().stream().map(PendingJob::jobId).collect(Collectors.toSet());

            Set<Long> overlap = new HashSet<>(idsA);
            overlap.retainAll(idsB);
            assertTrue(overlap.isEmpty(),
                    "SKIP LOCKED 가 깨졌다 — 두 claimer 가 같은 row 점유: " + overlap);
            assertEquals(20, idsA.size() + idsB.size());
        } finally {
            ex.shutdown();
            ex.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void recordHdfsFailureUnderLimitGoesBackToPendingAndClearsDispatchedAt() throws Exception {
        long id = insertPending("/retry", 100.0);
        setDispatched(id, 0);

        repo.recordHdfsFailure(id, "transient", 3);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, retry_count, dispatched_at, last_error " +
                     "FROM pending_jobs WHERE job_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("status"));
                assertEquals(1, rs.getInt("retry_count"));
                assertNull(rs.getTimestamp("dispatched_at"));
                assertEquals("transient", rs.getString("last_error"));
            }
        }
    }

    @Test
    void recordHdfsFailureAtLimitGoesToFailed() throws Exception {
        long id = insertPending("/dead", 100.0);
        setDispatched(id, 2);   // 다음 실패가 3회 → 한도 도달

        repo.recordHdfsFailure(id, "permanent error", 3);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, retry_count FROM pending_jobs WHERE job_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FAILED", rs.getString("status"));
                assertEquals(3, rs.getInt("retry_count"));
            }
        }
    }

    @Test
    void recordHdfsFailureDoesNotReopenCompletedJob() throws Exception {
        long id = insertPending("/completed", 100.0);
        setDispatched(id, 0);
        repo.markCompleted(id);

        repo.recordHdfsFailure(id, "late failure", 3);

        assertEquals("COMPLETED", statusOf("/completed"));
    }

    @Test
    void insertPendingJobsIgnoresExistingActivePath() throws Exception {
        insertPending("/dup", 100.0);
        var job = new ScoringEngine.ScoredJob(
                new FileMetadata("/dup", 1024L, 0L, 0L, 7),
                Tier.HOT,
                Tier.COLD,
                1.0);

        int inserted = repo.insertPendingJobs(List.of(job));

        assertEquals(0, inserted);
    }

    // --- helpers ---

    private long insertPending(String path, double priority) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO pending_jobs " +
                     "(file_path, file_size_bytes, current_tier, target_tier, priority_score, status, scored_at) " +
                     "VALUES (?, 1024, 'HOT', 'COLD', ?, 'PENDING', NOW()) RETURNING job_id")) {
            ps.setString(1, path);
            ps.setDouble(2, priority);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setDispatched(long id, int retryCount) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE pending_jobs SET status='DISPATCHED', dispatched_at=NOW(), retry_count=? " +
                     "WHERE job_id=?")) {
            ps.setInt(1, retryCount);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private String statusOf(String path) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM pending_jobs WHERE file_path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
