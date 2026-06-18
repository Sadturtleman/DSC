package edu.dsc.tiering.model;

import java.time.OffsetDateTime;

public final class PendingJob {

    private final long jobId;
    private final String filePath;
    private final long fileSizeBytes;
    private final Tier currentTier;
    private final Tier targetTier;
    private final double priorityScore;
    private final JobStatus status;
    private final OffsetDateTime scoredAt;
    private final OffsetDateTime dispatchedAt;
    private final OffsetDateTime completedAt;
    private final int retryCount;
    private final String lastError;
    private final Long fsimageSnapshotId;

    public PendingJob(long jobId, String filePath, long fileSizeBytes,
                      Tier currentTier, Tier targetTier, double priorityScore,
                      JobStatus status, OffsetDateTime scoredAt, OffsetDateTime dispatchedAt,
                      OffsetDateTime completedAt, int retryCount, String lastError,
                      Long fsimageSnapshotId) {
        this.jobId = jobId;
        this.filePath = filePath;
        this.fileSizeBytes = fileSizeBytes;
        this.currentTier = currentTier;
        this.targetTier = targetTier;
        this.priorityScore = priorityScore;
        this.status = status;
        this.scoredAt = scoredAt;
        this.dispatchedAt = dispatchedAt;
        this.completedAt = completedAt;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.fsimageSnapshotId = fsimageSnapshotId;
    }

    public long jobId() { return jobId; }
    public String filePath() { return filePath; }
    public long fileSizeBytes() { return fileSizeBytes; }
    public Tier currentTier() { return currentTier; }
    public Tier targetTier() { return targetTier; }
    public double priorityScore() { return priorityScore; }
    public JobStatus status() { return status; }
    public OffsetDateTime scoredAt() { return scoredAt; }
    public OffsetDateTime dispatchedAt() { return dispatchedAt; }
    public OffsetDateTime completedAt() { return completedAt; }
    public int retryCount() { return retryCount; }
    public String lastError() { return lastError; }
    public Long fsimageSnapshotId() { return fsimageSnapshotId; }
}
