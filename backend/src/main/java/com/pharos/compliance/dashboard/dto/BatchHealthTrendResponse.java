package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Batch health for one adaptive trend bucket on Batch View's Daily Batch Health chart. Carries only"
    + " the fields that chart reads -- Transactions Overview's own trend heatmap/line charts read the same underlying"
    + " query result shaped into {@link TransactionVolumeTrendResponse} instead.")
public record BatchHealthTrendResponse(@Schema(example = "2026-08-01") LocalDate periodStart,
    @Schema(example = "2026-08-01") LocalDate periodEnd, @Schema(example = "12") long batchesRan,
    @Schema(example = "9") long successfulBatches, @Schema(example = "3") long batchesNeedingAttention) {}
