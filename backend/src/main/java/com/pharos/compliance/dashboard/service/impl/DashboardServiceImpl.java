package com.pharos.compliance.dashboard.service.impl;

import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.dashboard.dto.BatchHealthTrendResponse;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import com.pharos.compliance.dashboard.dto.ReportGroupAttentionResponse;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.repository.DashboardRepository;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
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
  public DashboardDetailsResponse getDashboardDetails(LocalDate fromDate, LocalDate toDate, String batchId, String country,
      Integer reportGroupId) {
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
    long startedAt = System.nanoTime();

    LOGGER.debug("Dashboard scope resolved | period={}..{} | country={} | reportGroupId={} | batchFilter={} | trendGranularity={}", fromDate,
        toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId,
        normalizedBatchId.isEmpty() ? "ALL" : normalizedBatchId, trendGranularity);

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);

    DashboardCountsProjection counts = dashboardRepository.getDashboardCounts(fromTimestamp, toTimestampExclusive, normalizedBatchId,
        countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter);

    List<BatchHealthTrendResponse> trend = dashboardRepository
      .getBatchHealthTrend(fromTimestamp, toTimestampExclusive, fromDate, toDate, trendGranularity.name(), normalizedBatchId,
          countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter)
      .stream()
      .map(period -> toTrendResponse(period, fromDate, toDate, trendGranularity))
      .toList();

    List<ReportGroupAttentionResponse> reportGroups = dashboardRepository
      .getReportGroupsRequiringAttention(fromTimestamp, toTimestampExclusive, normalizedBatchId, countryFilter.enabled(),
          countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter)
      .stream()
      .map(this::toReportGroupResponse)
      .toList();

    DashboardDetailsResponse response = toDashboardResponse(counts, trendGranularity, trend, reportGroups, fromDate, toDate);

    LOGGER.info("Dashboard snapshot ready | period={}..{} | country={} | reportGroupId={} | batchesRan={} | successful={} | attention={}"
        + " | notYetReported={} | reportedTransactions={} | excludedTransactions={} | issueReportGroups={} | trendBuckets={}"
        + " | duration={}ms", fromDate, toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId, response.batchesRan(),
        response.successfulBatches(), response.batchesNeedingAttention(), response.batchesNotYetReported(),
        response.totalReportedTransactions(), response.totalExcludedTransactions(), response.reportGroupsRequiringAttention().size(),
        response.batchHealthTrend().size(), (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  private DashboardDetailsResponse toDashboardResponse(DashboardCountsProjection counts, TrendGranularity trendGranularity,
      List<BatchHealthTrendResponse> trend, List<ReportGroupAttentionResponse> reportGroups, LocalDate fromDate, LocalDate toDate) {
    return new DashboardDetailsResponse(counts.batchesRan(),
        counts.batchesRan() - counts.batchesNeedingAttention() - counts.batchesNotYetReported(), counts.batchesNotYetReported(),
        counts.batchesNeedingAttention(), counts.transformationFailureBatches(), counts.missingAttemptBatches(),
        counts.activityMissingBatches(), counts.duplicateTransactionBatches(), counts.exclusionBatches(),
        counts.simulatedTransactionBatches(), counts.softDedupBatches(), counts.totalReportedTransactions(),
        counts.totalExcludedTransactions(), trendGranularity, trend, reportGroups, fromDate, toDate);
  }

  private BatchHealthTrendResponse toTrendResponse(BatchHealthTrendProjection period, LocalDate requestedFromDate, LocalDate requestedToDate,
      TrendGranularity granularity) {
    return new BatchHealthTrendResponse(period.periodStart().isBefore(requestedFromDate) ? requestedFromDate : period.periodStart(),
        periodEnd(period.periodStart(), requestedToDate, granularity), period.batchesRan(), period.successfulBatches(),
        period.batchesNeedingAttention(), period.transformationFailureBatches(), period.missingAttemptBatches(),
        period.activityMissingBatches(), period.batchesRan() == 0 ? 0.0 : (period.batchesNeedingAttention() * 100.0) / period.batchesRan(),
        period.totalReportedTransactions(), period.totalExcludedTransactions());
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
}
