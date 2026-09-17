package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A two-way split of transactionOverview.notReported: stalled (processing genuinely finished in a "
    + "terminal non-success state -- a real problem) vs. stillProcessing (still in flight, not yet due for concern). "
    + "stalled + stillProcessing == transactionOverview.notReported exactly")
public record NotReportedBreakdownResponse(@Schema(example = "19") long stalled, @Schema(example = "65") long stillProcessing) {}
