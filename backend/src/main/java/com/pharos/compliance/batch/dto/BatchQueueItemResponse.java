package com.pharos.compliance.batch.dto;

import com.pharos.compliance.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "A batch in the investigation work queue")
public record BatchQueueItemResponse(
    int reportGroupId,
    String reportGroupName,
    String batchId,
    int sequenceNumber,
    String countryCode,
    String countryName,
    String reportingPeriodFrom,
    String reportingPeriodTo,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    BatchStatus status,
    long transformationFailures,
    long missingAttempts,
    long activityMissing,
    long filtrationErrors,
    long reconciliationImbalance,
    long transformerOutput,
    long excludedTransactions,
    long duplicateTransactions,
    long simulatedTransactions,
    long softDedupTransactions,
    long totalIssues,
    @Schema(
            description =
                "For NOT_YET_REPORTED batches only: distinct transactions seen in journey evidence so far",
            example = "0")
        long discoveredTransactions) {}
