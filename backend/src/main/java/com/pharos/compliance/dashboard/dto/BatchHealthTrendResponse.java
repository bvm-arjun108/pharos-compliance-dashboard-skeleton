package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Batch health and issue-driver metrics for one adaptive trend bucket")
public record BatchHealthTrendResponse(
    @Schema(example = "2026-08-01") LocalDate periodStart,
    @Schema(example = "2026-08-01") LocalDate periodEnd,
    @Schema(example = "12") long batchesRan,
    @Schema(example = "9") long successfulBatches,
    @Schema(example = "3") long batchesNeedingAttention,
    @Schema(example = "2") long transformationFailureBatches,
    @Schema(example = "1") long missingAttemptBatches,
    @Schema(example = "1") long activityMissingBatches,
    @Schema(description = "Percentage of batches needing attention", example = "25.0")
        double attentionRate) {}
