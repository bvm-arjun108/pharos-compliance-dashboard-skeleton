package com.wu.compliance.dashboard.dashboard.service.impl;

import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.dashboard.dto.BatchDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.BatchHealthTrendResponse;
import com.wu.compliance.dashboard.dashboard.dto.ExclusionReasonResponse;
import com.wu.compliance.dashboard.dashboard.dto.NotReportedReasonResponse;
import com.wu.compliance.dashboard.dashboard.dto.ReportGroupAttentionResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionOverviewResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionVolumeTrendResponse;
import com.wu.compliance.dashboard.dashboard.model.TrendGranularity;
import com.wu.compliance.dashboard.dashboard.repository.DashboardRepository;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchHealthTrendProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.DashboardCountsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ExclusionReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.NotReportedReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionOverviewProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionVolumeTrendProjection;
import com.wu.compliance.dashboard.dashboard.service.DashboardService;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.reportgroup.service.CountryCatalog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Batch View and Transactions Overview used to share one {@code /dashboardDetails} endpoint and
 * response -- each page threw away roughly half the payload and paid for query work the other
 * page owned (Batch View never reads {@code topExclusionReasons}/{@code notReportedReasons};
 * Transactions Overview never reads the batch KPI counts or {@code reportGroupsRequiringAttention}
 * -- see each frontend component's own response interface). Each endpoint now executes two focused
 * repository statements: one shared snapshot for sections derived from the same scoped rows, and
 * one page-specific time-series query. Batch View pairs its snapshot with {@link
 * DashboardRepository#getBatchHealthTrend}; Transactions Overview pairs its snapshot with {@link
 * DashboardRepository#getTransactionVolumeTrend}. This keeps unrelated page work separated while
 * ensuring each expensive shared scope is evaluated only once per request.
 */
@Service
public class DashboardServiceImpl implements DashboardService {
  private static final Logger LOGGER = LoggerFactory.getLogger(DashboardServiceImpl.class);
  private final DashboardRepository dashboardRepository;
  private final CountryCatalog countryCatalog;

  public DashboardServiceImpl(DashboardRepository dashboardRepository, CountryCatalog countryCatalog) {
    this.dashboardRepository = dashboardRepository;
    this.countryCatalog = countryCatalog;
  }

  @Override
  public BatchDashboardResponse getBatchDashboard(LocalDate fromDate, LocalDate toDate, String batchId, String country,
      Integer reportGroupId) {
    RequestScope dashboardRequestScope = resolveScope(fromDate, toDate, batchId, country, reportGroupId);
    long operationStartedAtNanos = System.nanoTime();

    BatchDashboardSnapshotProjection batchDashboardSnapshot = dashboardRepository.getBatchDashboardSnapshot(dashboardRequestScope.fromTimestamp(),
        dashboardRequestScope.toTimestampExclusive(), dashboardRequestScope.normalizedBatchId(),
        dashboardRequestScope.countryFilter().enabled(), dashboardRequestScope.countryFilter().reportGroupIds(),
        dashboardRequestScope.filterByReportGroup(), dashboardRequestScope.reportGroupIdFilter());
    DashboardCountsProjection dashboardCounts = batchDashboardSnapshot.counts();

    List<BatchHealthTrendResponse> batchHealthTrend = dashboardRepository
      .getBatchHealthTrend(dashboardRequestScope.fromTimestamp(), dashboardRequestScope.toTimestampExclusive(), fromDate, toDate,
          dashboardRequestScope.trendGranularity().name(), dashboardRequestScope.normalizedBatchId(),
          dashboardRequestScope.countryFilter().enabled(), dashboardRequestScope.countryFilter().reportGroupIds(),
          dashboardRequestScope.filterByReportGroup(), dashboardRequestScope.reportGroupIdFilter())
      .stream()
      .map(trendPeriod -> toBatchHealthTrendResponse(trendPeriod, fromDate, toDate, dashboardRequestScope.trendGranularity()))
      .toList();

    List<ReportGroupAttentionResponse> reportGroupsRequiringAttention =
        batchDashboardSnapshot.reportGroups().stream().map(this::toReportGroupResponse).toList();

    BatchDashboardResponse batchDashboardResponse = new BatchDashboardResponse(dashboardCounts.batchesRan(),
        dashboardCounts.batchesRan() - dashboardCounts.batchesNeedingAttention(), dashboardCounts.batchesNeedingAttention(),
        dashboardCounts.transformationFailureBatches(), dashboardCounts.missingAttemptBatches(), dashboardCounts.activityMissingBatches(),
        dashboardCounts.duplicateTransactionBatches(), dashboardCounts.exclusionBatches(), dashboardCounts.simulatedTransactionBatches(),
        dashboardCounts.softDedupBatches(), dashboardRequestScope.trendGranularity(), batchHealthTrend, reportGroupsRequiringAttention,
        fromDate, toDate);

    LOGGER.info("Batch dashboard snapshot ready | period={}..{} | country={} | reportGroupId={} | batchesRan={} | successful={}"
        + " | attention={} | issueReportGroups={} | trendBuckets={} | duration={}ms", fromDate, toDate,
        dashboardRequestScope.normalizedCountryCode(), reportGroupId == null ? "ALL" : reportGroupId, batchDashboardResponse.batchesRan(),
        batchDashboardResponse.successfulBatches(), batchDashboardResponse.batchesNeedingAttention(),
        batchDashboardResponse.reportGroupsRequiringAttention().size(), batchDashboardResponse.batchHealthTrend().size(),
        (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return batchDashboardResponse;
  }

  @Override
  public TransactionDashboardResponse getTransactionDashboard(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId) {
    RequestScope dashboardRequestScope = resolveScope(fromDate, toDate, "", country, reportGroupId);
    long operationStartedAtNanos = System.nanoTime();

    TransactionDashboardSnapshotProjection transactionDashboardSnapshot = dashboardRepository.getTransactionDashboardSnapshot(dashboardRequestScope.fromTimestamp(),
        dashboardRequestScope.toTimestampExclusive(), dashboardRequestScope.normalizedBatchId(),
        dashboardRequestScope.countryFilter().enabled(), dashboardRequestScope.countryFilter().reportGroupIds(),
        dashboardRequestScope.filterByReportGroup(), dashboardRequestScope.reportGroupIdFilter());
    TransactionOverviewProjection transactionOverview = transactionDashboardSnapshot.overview();

    List<ExclusionReasonResponse> topExclusionReasons =
        transactionDashboardSnapshot.exclusionReasons().stream().map(this::toExclusionReasonResponse).toList();

    List<NotReportedReasonResponse> notReportedReasons =
        transactionDashboardSnapshot.notReportedReasons().stream().map(this::toNotReportedReasonResponse).toList();

    List<TransactionVolumeTrendResponse> transactionVolumeTrend = dashboardRepository
      .getTransactionVolumeTrend(dashboardRequestScope.fromTimestamp(), dashboardRequestScope.toTimestampExclusive(), fromDate, toDate,
          dashboardRequestScope.trendGranularity().name(), dashboardRequestScope.normalizedBatchId(),
          dashboardRequestScope.countryFilter().enabled(), dashboardRequestScope.countryFilter().reportGroupIds(),
          dashboardRequestScope.filterByReportGroup(), dashboardRequestScope.reportGroupIdFilter())
      .stream()
      .map(trendPeriod -> toTransactionVolumeTrendResponse(trendPeriod, fromDate, toDate, dashboardRequestScope.trendGranularity()))
      .toList();

    TransactionDashboardResponse transactionDashboardResponse = new TransactionDashboardResponse(new TransactionOverviewResponse(transactionOverview.selected(),
            transactionOverview.expected(), transactionOverview.excluded(), transactionOverview.notReported()), topExclusionReasons,
        notReportedReasons, dashboardRequestScope.trendGranularity(), transactionVolumeTrend, fromDate, toDate);

    LOGGER.info("Transaction dashboard snapshot ready | period={}..{} | country={} | reportGroupId={} | txnSelected={} | txnExpected={}"
        + " | txnExcluded={} | txnNotReported={} | exclusionReasons={} | notReportedReasons={} | trendBuckets={} | duration={}ms", fromDate,
        toDate, dashboardRequestScope.normalizedCountryCode(), reportGroupId == null ? "ALL" : reportGroupId,
        transactionDashboardResponse.transactionOverview().selected(), transactionDashboardResponse.transactionOverview().expected(),
        transactionDashboardResponse.transactionOverview().excluded(), transactionDashboardResponse.transactionOverview().notReported(),
        transactionDashboardResponse.topExclusionReasons().size(), transactionDashboardResponse.notReportedReasons().size(),
        transactionDashboardResponse.batchHealthTrend().size(), (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return transactionDashboardResponse;
  }

  private RequestScope resolveScope(LocalDate fromDate, LocalDate toDate, String batchId, String country, Integer reportGroupId) {
    if (fromDate.isAfter(toDate)) {
      throw new InvalidDateRangeException("fromDate must be on or before toDate");
    }

    String normalizedBatchId = batchId == null ? "" : batchId.trim();
    String normalizedCountryCode = normalizeCountryCode(country);
    boolean filterByReportGroup = reportGroupId != null;
    int reportGroupIdFilter = filterByReportGroup ? reportGroupId : -1;
    TrendGranularity trendGranularity = TrendGranularity.forPeriod(fromDate, toDate);
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();

    LOGGER.debug("Dashboard scope resolved | period={}..{} | country={} | reportGroupId={} | batchFilter={} | trendGranularity={}", fromDate,
        toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId,
        normalizedBatchId.isEmpty() ? "ALL" : normalizedBatchId, trendGranularity);

    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(countryCatalogSnapshot, normalizedCountryCode);

    return new RequestScope(normalizedBatchId, normalizedCountryCode, filterByReportGroup, reportGroupIdFilter, trendGranularity,
        fromTimestamp, toTimestampExclusive, countryFilter);
  }

  private ExclusionReasonResponse toExclusionReasonResponse(ExclusionReasonProjection exclusionReason) {
    return new ExclusionReasonResponse(exclusionReason.reason(), exclusionReason.count());
  }

  private NotReportedReasonResponse toNotReportedReasonResponse(NotReportedReasonProjection notReportedReason) {
    return new NotReportedReasonResponse(notReportedReason.reason(), notReportedReason.count());
  }

  private BatchHealthTrendResponse toBatchHealthTrendResponse(BatchHealthTrendProjection trendPeriod, LocalDate requestedFromDate,
      LocalDate requestedToDate, TrendGranularity granularity) {
    return new BatchHealthTrendResponse(trendPeriod.periodStart().isBefore(requestedFromDate)
        ? requestedFromDate
        : trendPeriod.periodStart(), periodEnd(trendPeriod.periodStart(), requestedToDate, granularity), trendPeriod.batchesRan(),
        trendPeriod.successfulBatches(), trendPeriod.batchesNeedingAttention());
  }

  private TransactionVolumeTrendResponse toTransactionVolumeTrendResponse(TransactionVolumeTrendProjection trendPeriod,
      LocalDate requestedFromDate, LocalDate requestedToDate, TrendGranularity granularity) {
    return new TransactionVolumeTrendResponse(trendPeriod.periodStart().isBefore(requestedFromDate)
        ? requestedFromDate
        : trendPeriod.periodStart(), periodEnd(trendPeriod.periodStart(), requestedToDate, granularity),
        trendPeriod.totalReportedTransactions(), trendPeriod.totalExcludedTransactions());
  }

  private ReportGroupAttentionResponse toReportGroupResponse(ReportGroupMetricsProjection reportGroupMetrics) {
    return new ReportGroupAttentionResponse(reportGroupMetrics.reportGroupId(), reportGroupMetrics.reportGroupName(),
        reportGroupMetrics.batchesRan(), reportGroupMetrics.successfulBatches(), reportGroupMetrics.batchesNeedingAttention(),
        reportGroupMetrics.transformationFailureBatches(), reportGroupMetrics.missingAttemptBatches(),
        reportGroupMetrics.activityMissingBatches(), reportGroupMetrics.totalReportedTransactions(),
        reportGroupMetrics.totalExcludedTransactions());
  }

  private LocalDate periodEnd(LocalDate periodStart, LocalDate requestedToDate, TrendGranularity granularity) {
    LocalDate calculatedEnd = switch (granularity) {
      case DAILY -> periodStart;
      case WEEKLY -> periodStart.plusDays(6);
      case MONTHLY -> periodStart.with(TemporalAdjusters.lastDayOfMonth());
    };
    return calculatedEnd.isAfter(requestedToDate) ? requestedToDate : calculatedEnd;
  }

  private CountryFilter resolveCountryFilter(CountryCatalogSnapshot countryCatalogSnapshot, String countryCode) {
    if ("ALL".equals(countryCode)) {
      return new CountryFilter("ALL", false, List.of(-1));
    }
    CountryDefinition countryDefinition = countryCatalogSnapshot
      .findByCode(countryCode)
      .orElseThrow(() -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(countryCode, true, countryDefinition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private record CountryFilter(String countryCode, boolean enabled, List<Integer> reportGroupIds) {}

  /**
   * Everything both {@link #getBatchDashboard} and {@link #getTransactionDashboard} derive from
   * the request's raw filter parameters before touching a repository query -- factored out so the
   * date-range validation, country-to-report-group resolution, and timestamp bounds are computed
   * identically (and only once) regardless of which dashboard the caller asked for.
   */
  private record RequestScope(String normalizedBatchId, String normalizedCountryCode, boolean filterByReportGroup, int reportGroupIdFilter,
      TrendGranularity trendGranularity, LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, CountryFilter countryFilter) {}
}
