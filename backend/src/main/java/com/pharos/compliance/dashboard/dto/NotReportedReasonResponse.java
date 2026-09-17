package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One bucket of transactionOverview.notReported, split out by why -- counts across every bucket "
    + "sum to exactly transactionOverview.notReported")
public record NotReportedReasonResponse(@Schema(example = "Batch report generation failed") String reason,
    @Schema(example = "14") long count) {}
