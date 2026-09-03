package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transaction-level (not batch-level) Reported/Not Reported/Excluded partition, derived from"
    + " each transaction's full journey event history")
public record TransactionOverviewResponse(@Schema(example = "3629227") long selected, @Schema(example = "3340446") long expected,
    @Schema(example = "288781") long excluded, @Schema(example = "1789") long notReported) {}
