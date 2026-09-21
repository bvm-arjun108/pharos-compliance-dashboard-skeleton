package com.pharos.compliance.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "Reported/excluded transaction volume for one adaptive trend bucket on Transactions Overview's"
    + " trend heatmap and line charts. Carries only the fields those charts read -- Batch View's own Daily Batch"
    + " Health chart reads the same underlying query result shaped into {@link BatchHealthTrendResponse} instead.")
public record TransactionVolumeTrendResponse(@Schema(example = "2026-08-01") LocalDate periodStart,
    @Schema(example = "2026-08-01") LocalDate periodEnd, @Schema(example = "2450") long totalReportedTransactions,
    @Schema(example = "6") long totalExcludedTransactions) {}
