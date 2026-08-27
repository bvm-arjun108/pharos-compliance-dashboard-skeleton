package com.pharos.compliance.transaction.service.impl;

import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import com.pharos.compliance.transaction.dto.PeriodTransactionContextResponse;
import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionEvidenceRecordResponse;
import com.pharos.compliance.transaction.dto.TransactionOutcomeBreakdownResponse;
import com.pharos.compliance.transaction.dto.TransactionReportContextResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionStageBreakdownResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import com.pharos.compliance.transaction.repository.TransactionEvidenceCache;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionOutcomeBreakdownProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionReportContextProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionStageBreakdownProjection;
import com.pharos.compliance.transaction.service.TransactionReportService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class TransactionReportServiceImpl implements TransactionReportService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportServiceImpl.class);

  private final TransactionEvidenceCache transactionEvidenceCache;
  private final CountryCatalog countryCatalog;
  private final Scheduler jdbcScheduler;

  public TransactionReportServiceImpl(
      TransactionEvidenceCache transactionEvidenceCache,
      CountryCatalog countryCatalog,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.transactionEvidenceCache = transactionEvidenceCache;
    this.countryCatalog = countryCatalog;
    this.jdbcScheduler = jdbcScheduler;
  }

  @Override
  public Mono<TransactionReportResponse> getTransactionReport(
      int reportGroupId,
      String batchId,
      int sequenceNumber,
      TransactionMetric metric,
      String search,
      TransactionEvidenceSource source,
      TransactionStage stage,
      TransactionOutcome outcome,
      TransactionStatus status,
      TransactionSortDirection sortDirection,
      int page,
      int size) {
    String normalizedSearch = search == null ? "" : search.trim();
    long offset = (long) page * size;

    return onJdbcScheduler(
            () -> {
              TransactionReportContextProjection context =
                  transactionEvidenceCache
                      .findReportContext(reportGroupId, batchId, sequenceNumber)
                      .orElseThrow(
                          () ->
                              new ResourceNotFoundException("Reconciliation batch was not found"));
              List<TransactionEvidenceProjection> evidence =
                  transactionEvidenceCache.findEvidenceRecords(
                      reportGroupId,
                      batchId,
                      metric.name(),
                      normalizedSearch,
                      source.name(),
                      stage.name(),
                      outcome.name(),
                      status.name(),
                      sortDirection.name(),
                      size,
                      offset);
              long matchingCount =
                  transactionEvidenceCache.countEvidenceRecords(
                      reportGroupId,
                      batchId,
                      metric.name(),
                      normalizedSearch,
                      source.name(),
                      stage.name(),
                      outcome.name(),
                      status.name());
              long availableRecordCount =
                  hasDefaultEvidenceFilters(normalizedSearch, source, stage, outcome, status)
                      ? matchingCount
                      : availableRecordCount(reportGroupId, batchId, metric);
              long aggregateCount = aggregateCount(context, metric);
              TransactionEvidenceLevel evidenceLevel =
                  evidenceLevel(aggregateCount, availableRecordCount);
              CountryDefinition country =
                  countryCatalog.getSnapshot().getForReportGroup(reportGroupId);
              TransactionOutcomeBreakdownProjection outcomeBreakdown =
                  transactionEvidenceCache.findOutcomeBreakdown(
                      reportGroupId,
                      batchId,
                      metric.name(),
                      normalizedSearch,
                      source.name(),
                      stage.name());
              List<TransactionStageBreakdownProjection> stageBreakdown =
                  transactionEvidenceCache.findStageBreakdown(
                      reportGroupId,
                      batchId,
                      metric.name(),
                      normalizedSearch,
                      source.name(),
                      outcome.name());

              return new TransactionReportResponse(
                  toContext(context, country),
                  metric,
                  metricLabel(metric),
                  aggregateCount,
                  availableRecordCount,
                  matchingCount,
                  evidenceLevel,
                  evidenceMessage(evidenceLevel, aggregateCount, availableRecordCount),
                  toOutcomeBreakdown(outcomeBreakdown),
                  toStageBreakdown(stageBreakdown),
                  evidence.stream().map(this::toEvidenceRecord).toList(),
                  normalizedSearch,
                  source,
                  stage,
                  outcome,
                  status,
                  sortDirection,
                  page,
                  size);
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Transaction evidence query started reportGroupId={} batchId={} sequenceNumber={} metric={} source={} stage={} outcome={} status={} sortDirection={} search={} page={} size={}",
                    reportGroupId,
                    batchId,
                    sequenceNumber,
                    metric,
                    source,
                    stage,
                    outcome,
                    status,
                    sortDirection,
                    normalizedSearch,
                    page,
                    size))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Transaction evidence query completed aggregateCount={} availableRecords={} evidenceLevel={}",
                    response.aggregateCount(),
                    response.availableRecordCount(),
                    response.evidenceLevel()));
  }

  @Override
  public Mono<PeriodTransactionReportResponse> getPeriodTransactionReport(
      LocalDate fromDate,
      LocalDate toDate,
      String country,
      Integer reportGroupId,
      String search,
      TransactionOutcome outcome,
      TransactionStatus status,
      TransactionSortDirection sortDirection,
      int page,
      int size) {
    if (fromDate.isAfter(toDate)) {
      return Mono.error(new InvalidDateRangeException("fromDate must be on or before toDate"));
    }
    String normalizedSearch = search == null ? "" : search.trim();
    String normalizedCountry = normalizeCountryCode(country);
    boolean filterByReportGroup = reportGroupId != null;
    int reportGroupIdFilter = filterByReportGroup ? reportGroupId : -1;
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    long offset = (long) page * size;

    return onJdbcScheduler(countryCatalog::getSnapshot)
        .flatMap(
            catalog -> {
              CountryFilter countryFilter =
                  resolvePeriodCountryFilter(catalog, normalizedCountry, reportGroupId);
              return onJdbcScheduler(
                  () -> {
                    PeriodAggregateProjection aggregate =
                        transactionEvidenceCache.findPeriodAggregate(
                            fromTimestamp,
                            toTimestampExclusive,
                            countryFilter.enabled(),
                            countryFilter.reportGroupIds(),
                            filterByReportGroup,
                            reportGroupIdFilter);
                    List<TransactionEvidenceProjection> evidence =
                        transactionEvidenceCache.findPeriodEvidenceRecords(
                            fromTimestamp,
                            toTimestampExclusive,
                            countryFilter.enabled(),
                            countryFilter.reportGroupIds(),
                            filterByReportGroup,
                            reportGroupIdFilter,
                            normalizedSearch,
                            outcome.name(),
                            status.name(),
                            sortDirection.name(),
                            size,
                            offset);
                    long matchingCount =
                        transactionEvidenceCache.countPeriodEvidenceRecords(
                            fromTimestamp,
                            toTimestampExclusive,
                            countryFilter.enabled(),
                            countryFilter.reportGroupIds(),
                            filterByReportGroup,
                            reportGroupIdFilter,
                            normalizedSearch,
                            outcome.name(),
                            status.name());
                    // The reconciliation-sourced excluded_txn sum is only a meaningful "aggregate"
                    // to compare record counts against when the user is actually viewing Excluded
                    // evidence — it has no equivalent for Success/Reported/etc, so treating it as
                    // the aggregate for every status would make evidenceLevel/evidenceMessage
                    // compare unrelated numbers (e.g. "25 records for an aggregate of 37" while
                    // viewing Success, where 37 is the unrelated excluded count). For every other
                    // status, the matching record count IS the full picture, so it doubles as its
                    // own aggregate.
                    long aggregateCount =
                        status == TransactionStatus.EXCLUDED
                            ? aggregate.getTotalExcluded()
                            : matchingCount;
                    long availableRecordCount = matchingCount;
                    TransactionEvidenceLevel evidenceLevel =
                        evidenceLevel(aggregateCount, availableRecordCount);
                    CountryDefinition countryDefinition =
                        filterByReportGroup
                            ? catalog.getForReportGroup(reportGroupId)
                            : catalog
                                .findByCode(normalizedCountry)
                                .orElse(new CountryDefinition("ALL", "All countries", Set.of()));

                    return new PeriodTransactionReportResponse(
                        new PeriodTransactionContextResponse(
                            reportGroupId,
                            filterByReportGroup ? aggregate.getReportGroupName() : null,
                            countryDefinition.code(),
                            countryDefinition.name(),
                            fromDate,
                            toDate,
                            aggregate.getBatchCount()),
                        periodMetricLabel(status),
                        aggregateCount,
                        availableRecordCount,
                        matchingCount,
                        evidenceLevel,
                        evidenceMessage(evidenceLevel, aggregateCount, availableRecordCount),
                        evidence.stream().map(this::toEvidenceRecord).toList(),
                        normalizedSearch,
                        outcome,
                        status,
                        sortDirection,
                        page,
                        size);
                  });
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Period transaction evidence query started fromDate={} toDate={} country={} reportGroupId={} search={} outcome={} status={} sortDirection={} page={} size={}",
                    fromDate,
                    toDate,
                    normalizedCountry,
                    reportGroupId,
                    normalizedSearch,
                    outcome,
                    status,
                    sortDirection,
                    page,
                    size))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Period transaction evidence query completed batchCount={} aggregateCount={} matchingRecords={}",
                    response.context().batchCount(),
                    response.aggregateCount(),
                    response.matchingRecordCount()));
  }

  private CountryFilter resolvePeriodCountryFilter(
      CountryCatalogSnapshot catalog, String countryCode, Integer reportGroupId) {
    if (reportGroupId != null || "ALL".equals(countryCode)) {
      return new CountryFilter(false, List.of(-1));
    }
    CountryDefinition definition =
        catalog
            .findByCode(countryCode)
            .orElseThrow(
                () -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(true, definition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private record CountryFilter(boolean enabled, List<Integer> reportGroupIds) {}

  private TransactionReportContextResponse toContext(
      TransactionReportContextProjection context, CountryDefinition country) {
    return new TransactionReportContextResponse(
        context.getReportGroupId(),
        context.getReportGroupName(),
        context.getBatchId(),
        context.getSequenceNumber(),
        country.code(),
        country.name(),
        context.getReportingPeriodFrom(),
        context.getReportingPeriodTo());
  }

  private TransactionEvidenceRecordResponse toEvidenceRecord(TransactionEvidenceProjection row) {
    return new TransactionEvidenceRecordResponse(
        row.getRecordKey(),
        row.getIdentifier(),
        row.getMtcn(),
        row.getBatchId(),
        TransactionEvidenceSource.valueOf(row.getEvidenceSource()),
        row.getStage(),
        row.getStatus(),
        TransactionOutcome.valueOf(row.getOutcome()),
        row.getComments(),
        row.getSkipReason(),
        row.getRuleId(),
        row.getExclusionReason(),
        row.getExclusionStrategy(),
        row.getReportedBatchId(),
        row.getReportingTimestamp(),
        row.getModifiedAt(),
        row.getProcessingComplete(),
        row.getCurrencyAmount(),
        row.getCurrencyCode(),
        row.getTransactionDate(),
        row.getTransactionSide(),
        row.getTxnSource(),
        row.getActivityType(),
        row.getSendDate(),
        row.getGalacticId(),
        row.getBucketId(),
        row.getAttemptId(),
        row.getSenderName(),
        row.getReceiverName(),
        row.getSenderCity(),
        row.getSenderCountry(),
        row.getSenderPhone(),
        row.getSenderDateOfBirth(),
        row.getSenderIdType(),
        row.getSenderIdNumber(),
        row.getReceiverCity(),
        row.getReceiverCountry(),
        row.getReceiverPhone(),
        row.getReceiverDateOfBirth(),
        row.getReceiverIdType(),
        row.getReceiverIdNumber(),
        row.getTransactionStatus(),
        row.getTransactionSubStatus(),
        row.getRuleHitsJson());
  }

  private TransactionOutcomeBreakdownResponse toOutcomeBreakdown(
      TransactionOutcomeBreakdownProjection breakdown) {
    return new TransactionOutcomeBreakdownResponse(
        breakdown.getSuccessCount(),
        breakdown.getErrorCount(),
        breakdown.getPendingCount(),
        breakdown.getExcludedCount(),
        breakdown.getTotalCount());
  }

  private List<TransactionStageBreakdownResponse> toStageBreakdown(
      List<TransactionStageBreakdownProjection> breakdown) {
    return breakdown.stream()
        .map(
            row ->
                new TransactionStageBreakdownResponse(
                    row.getStage(),
                    row.getSuccessCount(),
                    row.getErrorCount(),
                    row.getPendingCount(),
                    row.getExcludedCount(),
                    row.getTotalCount()))
        .toList();
  }

  private long aggregateCount(
      TransactionReportContextProjection context, TransactionMetric metric) {
    return switch (metric) {
      case ALL, SELECTED -> context.getSelectedTransactions();
      case ATTEMPTS_FOUND -> context.getAttemptsFound();
      case MISSING -> context.getMissingAttempts();
      case EXPECTED_ELIGIBLE -> context.getExpectedEligible();
      case ACTUAL_ELIGIBLE -> context.getActualEligible();
      case TRANSFORMED -> context.getTransformed();
      case FAILED -> context.getFailed();
      case EXPECTED_REPORTABLE -> context.getExpectedReportable();
      case ACTUAL_REPORTABLE, TRANSFORMER_OUTPUT -> context.getActualReportable();
      case EXCLUDED -> context.getExcluded();
      case SIMULATED -> context.getSimulated();
      case ALREADY_REPORTED -> context.getAlreadyReported();
      case SOFT_DEDUP -> context.getSoftDedup();
      case FILTRATION_VARIANCE -> context.getFiltrationVariance();
      case RECONCILIATION_VARIANCE -> context.getReconciliationVariance();
    };
  }

  private String metricLabel(TransactionMetric metric) {
    return switch (metric) {
      case ALL -> "Available transaction evidence";
      case SELECTED -> "Selected transactions";
      case ATTEMPTS_FOUND -> "Transaction attempts found";
      case MISSING -> "Missing transaction attempts";
      case EXPECTED_ELIGIBLE -> "Expected transformation eligible";
      case ACTUAL_ELIGIBLE -> "Actual transformation eligible";
      case TRANSFORMED -> "Transformed transactions";
      case FAILED -> "Transformation failures";
      case EXPECTED_REPORTABLE -> "Expected reportable transactions";
      case ACTUAL_REPORTABLE -> "Actual reportable transactions";
      case EXCLUDED -> "Excluded transactions";
      case SIMULATED -> "Simulated (SML) transactions";
      case ALREADY_REPORTED -> "Already reported transactions";
      case SOFT_DEDUP -> "Soft-dedup dropped transactions";
      case FILTRATION_VARIANCE -> "Filtration variance";
      case RECONCILIATION_VARIANCE -> "Reconciliation variance";
      case TRANSFORMER_OUTPUT -> "Transformer output";
    };
  }

  /** Unlike the single-batch report (one fixed metric per page), the period-wide report's only
   *  axis is the status filter — the heading reflects whatever status is currently selected,
   *  since a user can pivot it freely to browse any evidence for the scoped report group/period. */
  private String periodMetricLabel(TransactionStatus status) {
    return switch (status) {
      case ALL -> "All transactions";
      case SUCCESS -> "Successful transactions";
      case FAILED -> "Failed transactions";
      case ERROR -> "Error transactions";
      case EXCLUDED -> "Excluded transactions";
      case NOT_YET_REPORTED -> "Not yet reported transactions";
      case REPORTED -> "Reported transactions";
      case NOT_REPORTED -> "Not reported transactions";
    };
  }

  private TransactionEvidenceLevel evidenceLevel(long aggregateCount, long availableRecords) {
    if (aggregateCount == 0 && availableRecords == 0) {
      return TransactionEvidenceLevel.NO_RECORDS;
    }
    if (availableRecords == 0) {
      return TransactionEvidenceLevel.AGGREGATE_ONLY;
    }
    if (aggregateCount > 0 && availableRecords >= aggregateCount) {
      return TransactionEvidenceLevel.RECORD_LEVEL;
    }
    return TransactionEvidenceLevel.PARTIAL_RECORD_LEVEL;
  }

  private boolean hasDefaultEvidenceFilters(
      String search,
      TransactionEvidenceSource source,
      TransactionStage stage,
      TransactionOutcome outcome,
      TransactionStatus status) {
    return search.isEmpty()
        && source == TransactionEvidenceSource.ALL
        && stage == TransactionStage.ALL
        && outcome == TransactionOutcome.ALL
        && status == TransactionStatus.ALL;
  }

  private long availableRecordCount(int reportGroupId, String batchId, TransactionMetric metric) {
    return transactionEvidenceCache.countEvidenceRecords(
        reportGroupId,
        batchId,
        metric.name(),
        "",
        TransactionEvidenceSource.ALL.name(),
        TransactionStage.ALL.name(),
        TransactionOutcome.ALL.name(),
        TransactionStatus.ALL.name());
  }

  private String evidenceMessage(
      TransactionEvidenceLevel level, long aggregateCount, long availableRecords) {
    return switch (level) {
      case RECORD_LEVEL ->
          "Record-level evidence is available for the full aggregate count in this batch.";
      case PARTIAL_RECORD_LEVEL ->
          availableRecords
              + " latest-state record(s) are available for an aggregate count of "
              + aggregateCount
              + ". Journey is latest-state evidence, not event history.";
      case AGGREGATE_ONLY ->
          "The count of "
              + aggregateCount
              + " is available only as batch reconciliation evidence; Phase 1 has no authoritative transaction rows for this metric.";
      case NO_RECORDS -> "The batch has no transactions for this metric.";
    };
  }

  private <T> Mono<T> onJdbcScheduler(Callable<T> databaseCall) {
    return Mono.fromCallable(databaseCall).subscribeOn(jdbcScheduler);
  }
}
