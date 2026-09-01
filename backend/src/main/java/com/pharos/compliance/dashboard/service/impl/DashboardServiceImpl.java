package com.pharos.compliance.dashboard.service.impl;

import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.dashboard.dto.BatchHealthTrendResponse;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import com.pharos.compliance.dashboard.dto.ReportGroupAttentionResponse;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.repository.DashboardRepository;
import com.pharos.compliance.dashboard.repository.DashboardRepository.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.DashboardRepository.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.DashboardRepository.ReportGroupMetricsProjection;
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
  public DashboardDetailsResponse getDashboardDetails(
      LocalDate fromDate, LocalDate toDate, String batchId, String country, Integer reportGroupId) {
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

    LOGGER.info(
        "Dashboard calculation started fromDate={} toDate={} country={} batchId={} reportGroupId={}",
        fromDate,
        toDate,
        normalizedCountryCode,
        normalizedBatchId,
        reportGroupId);

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);

    DashboardCountsProjection counts =
        dashboardRepository.getDashboardCounts(
            fromTimestamp,
            toTimestampExclusive,
            normalizedBatchId,
            countryFilter.enabled(),
            countryFilter.reportGroupIds(),
            filterByReportGroup,
            reportGroupIdFilter);

    List<BatchHealthTrendResponse> trend =
        dashboardRepository
            .getBatchHealthTrend(
                fromTimestamp,
                toTimestampExclusive,
                fromDate,
                toDate,
                trendGranularity.name(),
                normalizedBatchId,
                countryFilter.enabled(),
                countryFilter.reportGroupIds(),
                filterByReportGroup,
                reportGroupIdFilter)
            .stream()
            .map(period -> toTrendResponse(period, fromDate, toDate, trendGranularity))
            .toList();

    List<ReportGroupAttentionResponse> reportGroups =
        dashboardRepository
            .getReportGroupsRequiringAttention(
                fromTimestamp,
                toTimestampExclusive,
                normalizedBatchId,
                countryFilter.enabled(),
                countryFilter.reportGroupIds(),
                filterByReportGroup,
                reportGroupIdFilter)
            .stream()
            .map(this::toReportGroupResponse)
            .toList();

    DashboardDetailsResponse response =
        toDashboardResponse(counts, trendGranularity, trend, reportGroups, fromDate, toDate);

    LOGGER.info(
        "Dashboard calculation completed fromDate={} toDate={} batchesRan={} batchesNeedingAttention={}",
        fromDate,
        toDate,
        response.batchesRan(),
        response.batchesNeedingAttention());
    return response;
  }

  private DashboardDetailsResponse toDashboardResponse(
      DashboardCountsProjection counts,
      TrendGranularity trendGranularity,
      List<BatchHealthTrendResponse> trend,
      List<ReportGroupAttentionResponse> reportGroups,
      LocalDate fromDate,
      LocalDate toDate) {
    return new DashboardDetailsResponse(
        counts.getBatchesRan(),
        counts.getBatchesRan()
            - counts.getBatchesNeedingAttention()
            - counts.getBatchesNotYetReported(),
        counts.getBatchesNotYetReported(),
        counts.getBatchesNeedingAttention(),
        counts.getTransformationFailureBatches(),
        counts.getMissingAttemptBatches(),
        counts.getActivityMissingBatches(),
        counts.getDuplicateTransactionBatches(),
        counts.getExclusionBatches(),
        counts.getSimulatedTransactionBatches(),
        counts.getSoftDedupBatches(),
        counts.getTotalReportedTransactions(),
        counts.getTotalExcludedTransactions(),
        trendGranularity,
        trend,
        reportGroups,
        fromDate,
        toDate);
  }

  private BatchHealthTrendResponse toTrendResponse(
      BatchHealthTrendProjection period,
      LocalDate requestedFromDate,
      LocalDate requestedToDate,
      TrendGranularity granularity) {
    return new BatchHealthTrendResponse(
        period.getPeriodStart().isBefore(requestedFromDate)
            ? requestedFromDate
            : period.getPeriodStart(),
        periodEnd(period.getPeriodStart(), requestedToDate, granularity),
        period.getBatchesRan(),
        period.getSuccessfulBatches(),
        period.getBatchesNeedingAttention(),
        period.getTransformationFailureBatches(),
        period.getMissingAttemptBatches(),
        period.getActivityMissingBatches(),
        period.getBatchesRan() == 0
            ? 0.0
            : (period.getBatchesNeedingAttention() * 100.0) / period.getBatchesRan(),
        period.getTotalReportedTransactions(),
        period.getTotalExcludedTransactions());
  }

  private ReportGroupAttentionResponse toReportGroupResponse(ReportGroupMetricsProjection group) {
    return new ReportGroupAttentionResponse(
        group.getReportGroupId(),
        group.getReportGroupName(),
        group.getBatchesRan(),
        group.getSuccessfulBatches(),
        group.getBatchesNeedingAttention(),
        group.getTransformationFailureBatches(),
        group.getMissingAttemptBatches(),
        group.getActivityMissingBatches(),
        group.getTotalReportedTransactions(),
        group.getTotalExcludedTransactions());
  }

  private LocalDate periodEnd(
      LocalDate periodStart, LocalDate requestedToDate, TrendGranularity granularity) {
    LocalDate calculatedEnd =
        switch (granularity) {
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
            .orElseThrow(
                () -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(countryCode, true, definition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private record CountryFilter(String countryCode, boolean enabled, List<Integer> reportGroupIds) {}
}
