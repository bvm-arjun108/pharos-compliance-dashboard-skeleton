package com.pharos.compliance.dashboard.service.impl;

import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.dashboard.dto.BatchDashboardResponse;
import com.pharos.compliance.dashboard.dto.BatchHealthTrendResponse;
import com.pharos.compliance.dashboard.dto.ExclusionReasonResponse;
import com.pharos.compliance.dashboard.dto.NotReportedReasonResponse;
import com.pharos.compliance.dashboard.dto.ReportGroupAttentionResponse;
import com.pharos.compliance.dashboard.dto.TransactionDashboardResponse;
import com.pharos.compliance.dashboard.dto.TransactionOverviewResponse;
import com.pharos.compliance.dashboard.dto.TransactionVolumeTrendResponse;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.repository.DashboardRepository;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ExclusionReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.NotReportedReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionOverviewProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionVolumeTrendProjection;
import com.pharos.compliance.dashboard.service.DashboardService;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
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
 * -- see each frontend component's own response interface). The seven {@link DashboardRepository}
 * queries are always independent of each other (no shared join or merge), so the split below is a
 * pure "call only the queries this page needs" change: {@link #getBatchDashboard} runs the three
 * Batch View queries ({@link DashboardRepository#getBatchHealthTrend} among them), {@link
 * #getTransactionDashboard} runs the four Transactions Overview queries ({@link
 * DashboardRepository#getTransactionVolumeTrend} among them). Those last two used to be one method
 * ({@code getBatchHealthTrend} returning every field either page might want), but since the two
 * pages read disjoint fields off it (batches ran/needing-attention for Batch View,
 * reported/excluded transaction totals for Transactions Overview) and neither page ever calls the
 * other's query in the same request, splitting them costs neither page an extra round trip while
 * sparing each one from aggregating columns its own request will never read -- see each
 * repository method's own Javadoc.
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
    RequestScope scope = resolveScope(fromDate, toDate, batchId, country, reportGroupId);
    long startedAt = System.nanoTime();

    DashboardCountsProjection counts = dashboardRepository.getDashboardCounts(scope.fromTimestamp(), scope.toTimestampExclusive(),
        scope.normalizedBatchId(), scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(),
        scope.reportGroupIdFilter());

    List<BatchHealthTrendResponse> trend = dashboardRepository
      .getBatchHealthTrend(scope.fromTimestamp(), scope.toTimestampExclusive(), fromDate, toDate, scope.trendGranularity().name(),
          scope.normalizedBatchId(), scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(),
          scope.reportGroupIdFilter())
      .stream()
      .map(period -> toBatchHealthTrendResponse(period, fromDate, toDate, scope.trendGranularity()))
      .toList();

    List<ReportGroupAttentionResponse> reportGroups = dashboardRepository
      .getReportGroupsRequiringAttention(scope.fromTimestamp(), scope.toTimestampExclusive(), scope.normalizedBatchId(),
          scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(), scope.reportGroupIdFilter())
      .stream()
      .map(this::toReportGroupResponse)
      .toList();

    BatchDashboardResponse response = new BatchDashboardResponse(counts.batchesRan(),
        counts.batchesRan() - counts.batchesNeedingAttention() - counts.batchesNotYetReported(), counts.batchesNotYetReported(),
        counts.batchesNeedingAttention(), counts.transformationFailureBatches(), counts.missingAttemptBatches(),
        counts.activityMissingBatches(), counts.duplicateTransactionBatches(), counts.exclusionBatches(),
        counts.simulatedTransactionBatches(), counts.softDedupBatches(), scope.trendGranularity(), trend, reportGroups, fromDate, toDate);

    LOGGER.info("Batch dashboard snapshot ready | period={}..{} | country={} | reportGroupId={} | batchesRan={} | successful={}"
        + " | attention={} | notYetReported={} | issueReportGroups={} | trendBuckets={} | duration={}ms", fromDate, toDate,
        scope.normalizedCountryCode(), reportGroupId == null ? "ALL" : reportGroupId, response.batchesRan(), response.successfulBatches(),
        response.batchesNeedingAttention(), response.batchesNotYetReported(), response.reportGroupsRequiringAttention().size(),
        response.batchHealthTrend().size(), (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  @Override
  public TransactionDashboardResponse getTransactionDashboard(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId) {
    RequestScope scope = resolveScope(fromDate, toDate, "", country, reportGroupId);
    long startedAt = System.nanoTime();

    TransactionOverviewProjection transactionOverview = dashboardRepository.getTransactionOverview(scope.fromTimestamp(),
        scope.toTimestampExclusive(), scope.normalizedBatchId(), scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(),
        scope.filterByReportGroup(), scope.reportGroupIdFilter());

    List<ExclusionReasonResponse> topExclusionReasons = dashboardRepository
      .getTopExclusionReasons(scope.fromTimestamp(), scope.toTimestampExclusive(), scope.normalizedBatchId(),
          scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(), scope.reportGroupIdFilter())
      .stream()
      .map(this::toExclusionReasonResponse)
      .toList();

    List<NotReportedReasonResponse> notReportedReasons = dashboardRepository
      .getNotReportedReasons(scope.fromTimestamp(), scope.toTimestampExclusive(), scope.normalizedBatchId(), scope
            .countryFilter()
            .enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(), scope.reportGroupIdFilter())
      .stream()
      .map(this::toNotReportedReasonResponse)
      .toList();

    List<TransactionVolumeTrendResponse> trend = dashboardRepository
      .getTransactionVolumeTrend(scope.fromTimestamp(), scope.toTimestampExclusive(), fromDate, toDate, scope.trendGranularity().name(),
          scope.normalizedBatchId(), scope.countryFilter().enabled(), scope.countryFilter().reportGroupIds(), scope.filterByReportGroup(),
          scope.reportGroupIdFilter())
      .stream()
      .map(period -> toTransactionVolumeTrendResponse(period, fromDate, toDate, scope.trendGranularity()))
      .toList();

    TransactionDashboardResponse response = new TransactionDashboardResponse(new TransactionOverviewResponse(transactionOverview.selected(),
            transactionOverview.expected(), transactionOverview.excluded(), transactionOverview.notReported()), topExclusionReasons,
        notReportedReasons, scope.trendGranularity(), trend, fromDate, toDate);

    LOGGER.info("Transaction dashboard snapshot ready | period={}..{} | country={} | reportGroupId={} | txnSelected={} | txnExpected={}"
        + " | txnExcluded={} | txnNotReported={} | exclusionReasons={} | notReportedReasons={} | trendBuckets={} | duration={}ms", fromDate,
        toDate, scope.normalizedCountryCode(), reportGroupId == null ? "ALL" : reportGroupId, response.transactionOverview().selected(),
        response.transactionOverview().expected(), response.transactionOverview().excluded(), response.transactionOverview().notReported(),
        response.topExclusionReasons().size(), response.notReportedReasons().size(), response.batchHealthTrend().size(),
        (System.nanoTime() - startedAt) / 1_000_000);
    return response;
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

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);

    return new RequestScope(normalizedBatchId, normalizedCountryCode, filterByReportGroup, reportGroupIdFilter, trendGranularity,
        fromTimestamp, toTimestampExclusive, countryFilter);
  }

  private ExclusionReasonResponse toExclusionReasonResponse(ExclusionReasonProjection reason) {
    return new ExclusionReasonResponse(reason.reason(), reason.count());
  }

  private NotReportedReasonResponse toNotReportedReasonResponse(NotReportedReasonProjection reason) {
    return new NotReportedReasonResponse(reason.reason(), reason.count());
  }

  private BatchHealthTrendResponse toBatchHealthTrendResponse(BatchHealthTrendProjection period, LocalDate requestedFromDate,
      LocalDate requestedToDate, TrendGranularity granularity) {
    return new BatchHealthTrendResponse(period.periodStart().isBefore(requestedFromDate) ? requestedFromDate : period.periodStart(),
        periodEnd(period.periodStart(), requestedToDate, granularity), period.batchesRan(), period.successfulBatches(),
        period.batchesNeedingAttention());
  }

  private TransactionVolumeTrendResponse toTransactionVolumeTrendResponse(TransactionVolumeTrendProjection period,
      LocalDate requestedFromDate, LocalDate requestedToDate, TrendGranularity granularity) {
    return new TransactionVolumeTrendResponse(period.periodStart().isBefore(requestedFromDate) ? requestedFromDate : period.periodStart(),
        periodEnd(period.periodStart(), requestedToDate, granularity), period.totalReportedTransactions(),
        period.totalExcludedTransactions());
  }

  private ReportGroupAttentionResponse toReportGroupResponse(ReportGroupMetricsProjection group) {
    return new ReportGroupAttentionResponse(group.reportGroupId(), group.reportGroupName(), group.batchesRan(), group.successfulBatches(),
        group.batchesNeedingAttention(), group.transformationFailureBatches(), group.missingAttemptBatches(), group.activityMissingBatches(),
        group.totalReportedTransactions(), group.totalExcludedTransactions());
  }

  private LocalDate periodEnd(LocalDate periodStart, LocalDate requestedToDate, TrendGranularity granularity) {
    LocalDate calculatedEnd = switch (granularity) {
      case DAILY -> periodStart;
      case WEEKLY -> periodStart.plusDays(6);
      case MONTHLY -> periodStart.with(TemporalAdjusters.lastDayOfMonth());
    };
    return calculatedEnd.isAfter(requestedToDate) ? requestedToDate : calculatedEnd;
  }

  private CountryFilter resolveCountryFilter(CountryCatalogSnapshot catalog, String countryCode) {
    if ("ALL".equals(countryCode)) {
      return new CountryFilter("ALL", false, List.of(-1));
    }
    CountryDefinition definition =
        catalog
      .findByCode(countryCode)
      .orElseThrow(() -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(countryCode, true, definition.reportGroupIds().stream().toList());
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
