package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome counts for one pipeline stage, within the current outcome filter")
public record TransactionStageBreakdownResponse(
    String stage,
    long successCount,
    long errorCount,
    long pendingCount,
    long excludedCount,
    long totalCount) {}
