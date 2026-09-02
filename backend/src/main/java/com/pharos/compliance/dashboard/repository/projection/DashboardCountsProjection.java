package com.pharos.compliance.dashboard.repository.projection;

public record DashboardCountsProjection(long batchesRan, long batchesNotYetReported, long batchesNeedingAttention,
    long transformationFailureBatches, long missingAttemptBatches, long activityMissingBatches, long duplicateTransactionBatches,
    long exclusionBatches, long simulatedTransactionBatches, long softDedupBatches, long totalReportedTransactions,
    long totalExcludedTransactions) {}
