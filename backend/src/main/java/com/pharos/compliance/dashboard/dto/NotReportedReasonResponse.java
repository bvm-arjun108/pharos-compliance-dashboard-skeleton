package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One bucket of transactionOverview.notReported, split out by why -- the top 3 reasons by count "
    + "plus a final \"Other\" bucket for the rest; counts across every bucket sum to exactly transactionOverview.notReported")
public record NotReportedReasonResponse(@Schema(example = "Expected reportable activity was not found") String reason,
    @Schema(example = "14") long count) {}
