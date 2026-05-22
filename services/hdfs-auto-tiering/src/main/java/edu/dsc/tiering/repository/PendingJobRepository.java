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
        final String sql = """
                UPDATE pending_jobs
                   SET status = 'DISPATCHED',
                       dispatched_at = NOW()
                 WHERE job_id IN (
                     SELECT job_id FROM pending_jobs
                      WHERE status = 'PENDING'
                      ORDER BY priority_score DESC, scored_at ASC
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED
                 )
                 RETURNING job_id, file_path, file_size_bytes,
                           current_tier, target_tier, priority_score, status,
                           scored_at, dispatched_at, completed_at,
                           retry_count, last_error, fsimage_snapshot_id
                """;
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
        final String sql = """
                UPDATE pending_jobs
                   SET status = CASE WHEN retry_count + 1 >= ?
                                     THEN 'FAILED'::job_status
                                     ELSE 'PENDING'::job_status END,
                       retry_count = retry_count + 1,
                       dispatched_at = NULL,
                       last_error = ?
                 WHERE job_id = ?
                """;
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
}
