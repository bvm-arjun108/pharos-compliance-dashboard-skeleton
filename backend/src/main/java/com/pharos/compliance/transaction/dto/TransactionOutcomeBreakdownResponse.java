package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    description =
        "Outcome counts across all evidence matching the current source/search/metric selection")
public record TransactionOutcomeBreakdownResponse(
    long successCount, long errorCount, long pendingCount, long excludedCount, long totalCount) {}
