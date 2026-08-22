package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Metrics for a report group with at least one batch requiring attention")
public record ReportGroupAttentionResponse(
    @Schema(example = "1000000007") int reportGroupId,
    @Schema(example = "PORTUGAL OBJECTIVE") String reportGroupName,
    @Schema(example = "6") long batchesRan,
    @Schema(example = "2") long successfulBatches,
    @Schema(example = "4") long batchesNeedingAttention,
    @Schema(example = "4") long transformationFailureBatches,
    @Schema(example = "1") long missingAttemptBatches,
    @Schema(example = "1") long filtrationFailureBatches,
    @Schema(example = "0") long reconciliationFailureBatches,
    @Schema(example = "5596") long totalReportedTransactions,
    @Schema(example = "10") long totalExcludedTransactions) {}
