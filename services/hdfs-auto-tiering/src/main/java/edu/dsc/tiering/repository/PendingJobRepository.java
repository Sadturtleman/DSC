package edu.dsc.tiering.repository;

import edu.dsc.tiering.model.JobStatus;
import edu.dsc.tiering.model.PendingJob;
import edu.dsc.tiering.model.Tier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class PendingJobRepository {

    private final DataSource dataSource;

    public PendingJobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 우선순위 상위 {@code batchSize} 개의 PENDING job 을 원자적으로 점유 후 DISPATCHED 로 전이.
     * SKIP LOCKED 패턴으로 멀티 인스턴스 동시 실행 시에도 안전.
     */
    public List<PendingJob> claimBatch(int batchSize) throws SQLException {
        final String sql =
                "UPDATE pending_jobs"
                + "   SET status = 'DISPATCHED',"
                + "       dispatched_at = NOW()"
                + " WHERE job_id IN ("
                + "     SELECT job_id FROM pending_jobs"
                + "      WHERE status = 'PENDING'"
                + "      ORDER BY priority_score ASC, scored_at ASC"
                + "      LIMIT ?"
                + "      FOR UPDATE SKIP LOCKED"
                + " )"
                + " RETURNING job_id, file_path, file_size_bytes,"
                + "           current_tier, target_tier, priority_score, status,"
                + "           scored_at, dispatched_at, completed_at,"
                + "           retry_count, last_error, fsimage_snapshot_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingJob> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        }
    }

    /**
     * Completion tracker 가 확인할 DISPATCHED/IN_PROGRESS job 을 점유한다.
     * DISPATCHED 는 추적 시작을 나타내도록 IN_PROGRESS 로 전이하고,
     * 이미 IN_PROGRESS 인 job 은 다음 확인 사이클에서 다시 반환한다.
     */
    public List<PendingJob> claimTrackableBatch(int batchSize) throws SQLException {
        final String sql =
                "UPDATE pending_jobs"
                + "   SET status = 'IN_PROGRESS'::job_status"
                + " WHERE job_id IN ("
                + "     SELECT job_id FROM pending_jobs"
                + "      WHERE status IN ('DISPATCHED', 'IN_PROGRESS')"
                + "      ORDER BY dispatched_at ASC NULLS LAST, priority_score ASC, scored_at ASC"
                + "      LIMIT ?"
                + "      FOR UPDATE SKIP LOCKED"
                + " )"
                + " RETURNING job_id, file_path, file_size_bytes,"
                + "           current_tier, target_tier, priority_score, status,"
                + "           scored_at, dispatched_at, completed_at,"
                + "           retry_count, last_error, fsimage_snapshot_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                List<PendingJob> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        }
    }

    /**
     * HDFS 호출 동기 실패 시: DISPATCHED → PENDING (retry++) 또는 한도 초과 시 FAILED.
     */
    public void recordHdfsFailure(long jobId, String errorMessage, int maxRetries) throws SQLException {
        final String sql =
                "UPDATE pending_jobs"
                + "   SET status = CASE WHEN retry_count + 1 >= ?"
                + "                     THEN 'FAILED'::job_status"
                + "                     ELSE 'PENDING'::job_status END,"
                + "       retry_count = retry_count + 1,"
                + "       dispatched_at = NULL,"
                + "       last_error = ?"
                + " WHERE job_id = ?"
                + "   AND status IN ('DISPATCHED', 'IN_PROGRESS')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, maxRetries);
            ps.setString(2, truncate(errorMessage, 1000));
            ps.setLong(3, jobId);
            ps.executeUpdate();
        }
    }

    private static PendingJob mapRow(ResultSet rs) throws SQLException {
        return new PendingJob(
                rs.getLong("job_id"),
                rs.getString("file_path"),
                rs.getLong("file_size_bytes"),
                Tier.valueOf(rs.getString("current_tier")),
                Tier.valueOf(rs.getString("target_tier")),
                rs.getDouble("priority_score"),
                JobStatus.valueOf(rs.getString("status")),
                toOdt(rs.getTimestamp("scored_at")),
                toOdt(rs.getTimestamp("dispatched_at")),
                toOdt(rs.getTimestamp("completed_at")),
                rs.getInt("retry_count"),
                rs.getString("last_error"),
                (Long) rs.getObject("fsimage_snapshot_id")
        );
    }

    private static java.time.OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public int insertPendingJobs(List<edu.dsc.tiering.scoring.ScoringEngine.ScoredJob> jobs) throws SQLException {
        if (jobs.isEmpty()) return 0;
        final String sql =
                "INSERT INTO pending_jobs (file_path, file_size_bytes, current_tier, target_tier, priority_score, status, scored_at) " +
                "VALUES (?, ?, ?::tier, ?::tier, ?, 'PENDING'::job_status, NOW()) " +
                "ON CONFLICT (file_path) WHERE status IN ('PENDING', 'DISPATCHED', 'IN_PROGRESS') DO NOTHING";
        int insertedCount = 0;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (var job : jobs) {
                ps.setString(1, job.meta().path());
                ps.setLong(2, job.meta().fileSizeBytes());
                ps.setString(3, job.currentTier().name());
                ps.setString(4, job.targetTier().name());
                ps.setDouble(5, job.priorityScore());
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            for (int r : results) {
                if (r > 0 || r == java.sql.Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                }
            }
        }
        return insertedCount;
    }

    public void markCompleted(long jobId) {
        final String sql =
                "UPDATE pending_jobs"
                + "   SET status = 'COMPLETED'::job_status,"
                + "       completed_at = NOW()"
                + " WHERE job_id = ?"
                + "   AND status IN ('DISPATCHED', 'IN_PROGRESS')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void markFailed(long jobId) {
        final String sql =
                "UPDATE pending_jobs"
                + "   SET status = 'FAILED'::job_status"
                + " WHERE job_id = ?"
                + "   AND status IN ('DISPATCHED', 'IN_PROGRESS')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void touchCheckedAt(long jobId) {
        // There is no checked_at column in the shared schema. Keep dispatched_at
        // unchanged so timeout accounting remains based on the original dispatch.
    }

    /**
     * 타임아웃된 job 을 PENDING 으로 복귀시키고 retry_count 를 증가.
     * BatchScheduler 가 다음 사이클에 다시 집어가도록 한다.
     */
    public void markForRetry(long id) {
        final String sql = """
                UPDATE pending_jobs
                SET status          = 'PENDING',
                    retry_count     = retry_count + 1,
                    last_failed_at  = NOW(),
                    dispatched_at   = NULL,
                    last_checked_at = NULL
                WHERE id = ?
                """;
        update(sql, id);
        log.info("RETRY(→PENDING) id={}", id);
    }

    /**
     * retry_count 가 한도를 초과한 경우 최종 실패로 마킹.
     */
    public void markFailedPermanently(long id) {
        final String sql = """
                UPDATE pending_jobs
                SET status          = 'FAILED',
                    last_checked_at = NOW(),
                    last_failed_at  = NOW()
                WHERE id = ?
                """;
        update(sql, id);
        log.warn("FAILED(permanent) id={}", id);
    }

    /**
     * 특정 job 의 현재 retry_count 조회.
     * CompletionTracker 가 재시도 여부 결정 시 사용.
     */
    public int getRetryCount(long id) {
        final String sql = "SELECT retry_count FROM pending_jobs WHERE id = ?";
        try (Connection conn = ds.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("getRetryCount 실패 id={}: {}", id, e.getMessage());
        }
        return 0;
    }
}
