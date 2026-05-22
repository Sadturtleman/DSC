package edu.dsc.tiering.model;

import java.time.OffsetDateTime;

public record PendingJob(
        long jobId,
        String filePath,
        long fileSizeBytes,
        Tier currentTier,
        Tier targetTier,
        double priorityScore,
        JobStatus status,
        OffsetDateTime scoredAt,
        OffsetDateTime dispatchedAt,
        OffsetDateTime completedAt,
        int retryCount,
        String lastError,
        Long fsimageSnapshotId
) {}
