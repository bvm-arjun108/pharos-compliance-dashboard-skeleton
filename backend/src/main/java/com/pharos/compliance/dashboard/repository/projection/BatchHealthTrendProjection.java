package com.pharos.compliance.dashboard.repository.projection;

import java.time.LocalDate;

public record BatchHealthTrendProjection(LocalDate periodStart, long batchesRan, long successfulBatches, long batchesNeedingAttention,
    long transformationFailureBatches, long missingAttemptBatches, long activityMissingBatches, long totalReportedTransactions,
    long totalExcludedTransactions) {}
