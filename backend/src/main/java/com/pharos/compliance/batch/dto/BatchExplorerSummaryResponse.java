package com.pharos.compliance.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Batch counts for the current date, country, and search context")
public record BatchExplorerSummaryResponse(
    @Schema(example = "10") long allBatches,
    @Schema(example = "2") long successfulBatches,
    @Schema(example = "8") long attentionBatches) {}
