package com.pharos.compliance.batch.dto;

import com.pharos.compliance.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "A batch in the investigation work queue")
public record BatchQueueItemResponse(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber, String countryCode,
    String countryName, String reportingPeriodFrom, String reportingPeriodTo, LocalDateTime startedAt, LocalDateTime completedAt,
    BatchStatus status, long transformationFailures,
    @Schema(description = "The raw report_transformation_reconciliation.activity_transformation_failed value, before correcting it against "
    + "record_transformation_journey evidence. Present so a discrepancy stays visible instead of silently disappearing once "
    + "transformationFailures is corrected -- see transformationFailureMismatch.") long reportedTransformationFailures,
    @Schema(description = "True when transformationFailures and reportedTransformationFailures disagree, meaning the upstream transformer "
    + "job's reconciliation count doesn't match what record_transformation_journey actually recorded for this batch -- a data-quality "
    + "signal pointing at the upstream job, not at this batch's own outcome.") boolean transformationFailureMismatch, long missingAttempts,
    long activityMissing, long filtrationErrors, long reconciliationImbalance, long transformerOutput, long excludedTransactions,
    long duplicateTransactions, long simulatedTransactions, long softDedupTransactions, long totalIssues) {}
