package com.pharos.compliance.dashboard.dto;

import com.pharos.compliance.dashboard.model.TrendGranularity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Transactions Overview dashboard: transaction-level Selected/Expected/Excluded/Not Reported"
    + " totals, their reason breakdowns, and the adaptive health trend for an inclusive date range. Carries only the"
    + " fields Transactions Overview reads -- Batch View has its own narrower response, {@link BatchDashboardResponse},"
    + " from a separate endpoint.")
public record TransactionDashboardResponse(TransactionOverviewResponse transactionOverview,
    List<ExclusionReasonResponse> topExclusionReasons, List<NotReportedReasonResponse> notReportedReasons, TrendGranularity trendGranularity,
    List<BatchHealthTrendResponse> batchHealthTrend, @Schema(example = "2026-08-01") LocalDate fromDate,
    @Schema(example = "2026-08-31") LocalDate toDate) {}
