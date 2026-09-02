package com.pharos.compliance.batch.repository.projection;

import java.time.LocalDateTime;

public record BatchDetailsProjection(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber,
    String reportingPeriodFrom, String reportingPeriodTo, LocalDateTime startedAt, LocalDateTime completedAt, long transformationFailures,
    long missingAttempts, long activityMissing, long duplicateTransactions, long filtrationErrors, long reconciliationImbalance,
    long selectedTransactions, long transactionAttemptsFound, long expectedReportableTransactions, long actualReportableTransactions,
    long expectedTransformationAttempts, long actualTransformationAttempts, long transformedActivities, long transformerOutput,
    long excludedTransactions, long simulatedTransactions, long alreadyReportedTransactions, long softDedupTransactions,
    boolean journeyAvailable, boolean exclusionsAvailable) {}
