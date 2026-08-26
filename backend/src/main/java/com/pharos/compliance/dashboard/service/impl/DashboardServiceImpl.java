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
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class DashboardServiceImpl implements DashboardService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DashboardServiceImpl.class);

  private final DashboardRepository dashboardRepository;
  private final CountryCatalog countryCatalog;
  private final Scheduler jdbcScheduler;

  public DashboardServiceImpl(
      DashboardRepository dashboardRepository,
      CountryCatalog countryCatalog,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.dashboardRepository = dashboardRepository;
    this.countryCatalog = countryCatalog;
    this.jdbcScheduler = jdbcScheduler;
  }

  @Override
  public Mono<DashboardDetailsResponse> getDashboardDetails(
      LocalDate fromDate, LocalDate toDate, String batchId, String country) {
    if (fromDate.isAfter(toDate)) {
      return Mono.error(new InvalidDateRangeException("fromDate must be on or before toDate"));
    }

    String normalizedBatchId = batchId == null ? "" : batchId.trim();
    String normalizedCountryCode = normalizeCountryCode(country);
    TrendGranularity trendGranularity = TrendGranularity.forPeriod(fromDate, toDate);
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    return onJdbcScheduler(countryCatalog::getSnapshot)
        .flatMap(
            catalog -> {
              CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);
              Mono<DashboardCountsProjection> counts =
                  onJdbcScheduler(
                      () ->
                          dashboardRepository.getDashboardCounts(
                              fromTimestamp,
                              toTimestampExclusive,
                              normalizedBatchId,
                              countryFilter.enabled(),
                              countryFilter.reportGroupIds()));
              Mono<List<BatchHealthTrendResponse>> trend =
                  onJdbcScheduler(
                          () ->
                              dashboardRepository.getBatchHealthTrend(
                                  fromTimestamp,
                                  toTimestampExclusive,
                                  fromDate,
                                  toDate,
                                  trendGranularity.name(),
                                  normalizedBatchId,
                                  countryFilter.enabled(),
                                  countryFilter.reportGroupIds()))
                      .map(
                          periods ->
                              periods.stream()
                                  .map(
                                      period ->
                                          toTrendResponse(
                                              period, fromDate, toDate, trendGranularity))
                                  .toList());
              Mono<List<ReportGroupAttentionResponse>> reportGroups =
                  onJdbcScheduler(
                          () ->
                              dashboardRepository.getReportGroupsRequiringAttention(
                                  fromTimestamp,
                                  toTimestampExclusive,
                                  normalizedBatchId,
                                  countryFilter.enabled(),
                                  countryFilter.reportGroupIds()))
                      .map(groups -> groups.stream().map(this::toReportGroupResponse).toList());

              return Mono.zip(counts, trend, reportGroups)
                  .map(
                      tuple ->
                          toDashboardResponse(
                              tuple.getT1(),
                              trendGranularity,
                              tuple.getT2(),
                              tuple.getT3(),
                              fromDate,
                              toDate));
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Dashboard calculation started fromDate={} toDate={} country={} batchId={}",
                    fromDate,
                    toDate,
                    normalizedCountryCode,
                    normalizedBatchId))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Dashboard calculation completed fromDate={} toDate={} batchesRan={} batchesNeedingAttention={}",
                    fromDate,
                    toDate,
                    response.batchesRan(),
                    response.batchesNeedingAttention()));
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
            : (period.getBatchesNeedingAttention() * 100.0) / period.getBatchesRan());
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

  private <T> Mono<T> onJdbcScheduler(Callable<T> databaseCall) {
    return Mono.fromCallable(databaseCall).subscribeOn(jdbcScheduler);
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
