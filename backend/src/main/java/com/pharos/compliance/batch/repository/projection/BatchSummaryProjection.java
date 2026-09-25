package com.pharos.compliance.batch.repository.projection;

public record BatchSummaryProjection(long allBatches, long successfulBatches, long attentionBatches, long activityMissingBatches,
    long missingAttemptBatches, long transformationBatches, long duplicateTransactionBatches, long exclusionBatches,
    long simulatedTransactionBatches, long softDedupBatches, String reportGroupName) {}
