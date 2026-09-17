package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One bucket of transactionOverview.excluded, split out by reason -- counts across every bucket "
    + "sum to exactly transactionOverview.excluded")
public record ExclusionReasonResponse(@Schema(example = "Already Reported In Prior Batch") String reason,
    @Schema(example = "12") long count) {}
