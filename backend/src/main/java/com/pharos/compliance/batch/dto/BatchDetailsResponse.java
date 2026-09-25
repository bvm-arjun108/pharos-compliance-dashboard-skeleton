package com.pharos.compliance.batch.dto;

import com.pharos.compliance.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Diagnostic preview for a selected reconciliation batch")
public record BatchDetailsResponse(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber, String countryCode,
    String countryName, String reportingPeriodFrom, String reportingPeriodTo, LocalDateTime startedAt, LocalDateTime completedAt,
    long durationSeconds, String operationalStatus, BatchStatus status, long transformationFailures,
    @Schema(description = "The raw report_transformation_reconciliation.activity_transformation_failed value, before correcting it against "
    + "record_transformation_journey evidence. See transformationFailureMismatch.") long reportedTransformationFailures,
    @Schema(description = "True when transformationFailures and reportedTransformationFailures disagree -- the upstream transformer job's "
    + "reconciliation count doesn't match what record_transformation_journey actually recorded for this batch.") boolean transformationFailureMismatch,
    long missingAttempts, long activityMissing, long duplicateTransactions, long filtrationErrors, long reconciliationImbalance,
    long totalIssues, long selectedTransactions, long transactionAttemptsFound, long expectedReportableTransactions,
    long actualReportableTransactions, long expectedTransformationAttempts, long actualTransformationAttempts, long transformedActivities,
    boolean transformationBalanced, long transformerOutput, Long finalDownstreamReported, long excludedTransactions,
    long simulatedTransactions, long alreadyReportedTransactions, long softDedupTransactions, boolean journeyAvailable,
    boolean ruleHitsAvailable, boolean exclusionsAvailable,
    @Schema(description = "The report_group_config version this batch actually ran under (from its own report_batch_info row), for "
    + "looking up that exact configuration instead of whatever the report group's current/latest version happens to be. Null if no "
    + "report_batch_info row exists for this batch", example = "104") Integer reportSelectionVersionId,
    @Schema(description = "Paired with reportSelectionVersionId to form the report_group_config composite key", example = "load-4.0") String transformerVersionId) {}
