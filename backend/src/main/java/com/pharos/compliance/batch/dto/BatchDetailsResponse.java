package com.pharos.compliance.batch.dto;

import com.pharos.compliance.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Diagnostic preview for a selected reconciliation batch")
public record BatchDetailsResponse(
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
    long durationSeconds,
    String operationalStatus,
    BatchStatus status,
    long transformationFailures,
    long missingAttempts,
    long filtrationErrors,
    long reconciliationImbalance,
    long totalIssues,
    long selectedTransactions,
    long transactionAttemptsFound,
    long expectedReportableTransactions,
    long actualReportableTransactions,
    long expectedTransformationAttempts,
    long actualTransformationAttempts,
    long transformedActivities,
    boolean transformationBalanced,
    long transformerOutput,
    Long finalDownstreamReported,
    long excludedTransactions,
    boolean journeyAvailable,
    boolean ruleHitsAvailable,
    boolean exclusionsAvailable) {}
