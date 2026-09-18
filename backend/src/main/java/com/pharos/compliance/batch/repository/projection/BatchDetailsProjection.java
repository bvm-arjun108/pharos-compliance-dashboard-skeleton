package com.pharos.compliance.batch.repository.projection;

import java.time.LocalDateTime;

public record BatchDetailsProjection(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber,
    String reportingPeriodFrom, String reportingPeriodTo, LocalDateTime startedAt, LocalDateTime completedAt, long transformationFailures,
    long missingAttempts, long activityMissing, long duplicateTransactions, long filtrationErrors, long reconciliationImbalance,
    long selectedTransactions, long transactionAttemptsFound, long expectedReportableTransactions, long actualReportableTransactions,
    long expectedTransformationAttempts, long actualTransformationAttempts, long transformedActivities, long transformerOutput,
    long excludedTransactions, long simulatedTransactions, long alreadyReportedTransactions, long softDedupTransactions,
    boolean journeyAvailable, boolean exclusionsAvailable,
    // From this batch's own report_batch_info row -- the exact report_group_config version this
    // batch was actually processed under, not whatever the latest/current config happens to be.
    // Null only if report_batch_info has no matching row (shouldn't happen for a reconciled batch,
    // but the join is LEFT so a data gap here degrades to "no version known" rather than 404s).
    Integer reportSelectionVersionId, String transformerVersionId) {}
