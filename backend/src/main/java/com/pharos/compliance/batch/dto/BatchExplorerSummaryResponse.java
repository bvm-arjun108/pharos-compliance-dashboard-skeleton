package com.pharos.compliance.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Batch counts for the current date, country, and search context")
public record BatchExplorerSummaryResponse(@Schema(example = "10") long allBatches, @Schema(example = "2") long successfulBatches,
    @Schema(example = "8") long attentionBatches, @Schema(example = "2") long activityMissingBatches,
    @Schema(example = "3") long missingAttemptBatches, @Schema(example = "4") long transformationBatches,
    @Schema(example = "2") long duplicateTransactionBatches, @Schema(example = "5") long exclusionBatches,
    @Schema(example = "1") long simulatedTransactionBatches, @Schema(example = "1") long softDedupBatches) {}
