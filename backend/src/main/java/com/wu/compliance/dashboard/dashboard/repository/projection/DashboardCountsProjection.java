package com.wu.compliance.dashboard.dashboard.repository.projection;

public record DashboardCountsProjection(long batchesRan, long batchesNeedingAttention, long transformationFailureBatches,
    long missingAttemptBatches, long activityMissingBatches, long duplicateTransactionBatches, long exclusionBatches,
    long simulatedTransactionBatches, long softDedupBatches) {}
