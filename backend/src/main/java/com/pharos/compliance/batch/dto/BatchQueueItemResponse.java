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
    long filtrationErrors,
    long reconciliationImbalance,
    long reportedTransactions,
    long excludedTransactions,
    long totalIssues) {}
