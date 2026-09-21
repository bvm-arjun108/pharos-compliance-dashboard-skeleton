package com.pharos.compliance.dashboard.dto;

import com.pharos.compliance.dashboard.model.TrendGranularity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Batch View dashboard: aggregate batch KPIs, adaptive health trend, and report groups requiring"
    + " attention for an inclusive date range. Carries only the fields Batch View reads -- Transactions Overview has"
    + " its own narrower response, {@link TransactionDashboardResponse}, from a separate endpoint. Scoped to completed"
    + " (reconciled) batches only -- a batch with journey evidence but no report_transformation_reconciliation row yet"
    + " (still at the SELECTION stage) isn't counted here; Phase 1's batch-perspective views only care about batches"
    + " that have actually finished processing.")
public record BatchDashboardResponse(@Schema(example = "30") long batchesRan, @Schema(example = "7") long successfulBatches,
    @Schema(example = "23") long batchesNeedingAttention, @Schema(example = "20") long transformationFailureBatches,
    @Schema(example = "8") long missingAttemptBatches, @Schema(example = "2") long activityMissingBatches,
    @Schema(example = "1") long duplicateTransactionBatches, @Schema(example = "23") long exclusionBatches,
    @Schema(example = "15") long simulatedTransactionBatches, @Schema(example = "0") long softDedupBatches,
    TrendGranularity trendGranularity, List<BatchHealthTrendResponse> batchHealthTrend,
    List<ReportGroupAttentionResponse> reportGroupsRequiringAttention,
    @Schema(example = "2026-08-01") LocalDate fromDate, @Schema(example = "2026-08-31") LocalDate toDate) {}
