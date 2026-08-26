package com.pharos.compliance.dashboard.dto;

import com.pharos.compliance.dashboard.model.TrendGranularity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Phase 1 compliance operations dashboard response")
public record DashboardDetailsResponse(
    @Schema(example = "30") long batchesRan,
    @Schema(example = "7") long successfulBatches,
    @Schema(
            description =
                "Batches with journey evidence but no report_transformation_reconciliation row yet"
                    + " (still at the SELECTION stage) — not yet reported",
            example = "2")
        long batchesNotYetReported,
    @Schema(example = "23") long batchesNeedingAttention,
    @Schema(example = "20") long transformationFailureBatches,
    @Schema(example = "8") long missingAttemptBatches,
    @Schema(example = "2") long activityMissingBatches,
    @Schema(example = "1") long duplicateTransactionBatches,
    @Schema(example = "23") long exclusionBatches,
    @Schema(example = "15") long simulatedTransactionBatches,
    @Schema(example = "0") long softDedupBatches,
    @Schema(
            description = "Transformer output, not final downstream reporting confirmation",
            example = "26840")
        long totalReportedTransactions,
    @Schema(example = "27") long totalExcludedTransactions,
    TrendGranularity trendGranularity,
    List<BatchHealthTrendResponse> batchHealthTrend,
    List<ReportGroupAttentionResponse> reportGroupsRequiringAttention,
    @Schema(example = "2026-08-01") LocalDate fromDate,
    @Schema(example = "2026-08-31") LocalDate toDate) {}
