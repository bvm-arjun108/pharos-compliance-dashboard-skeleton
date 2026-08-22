package com.pharos.compliance.batch.service.impl;

import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchExplorerSummaryResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.dto.BatchQueueItemResponse;
import com.pharos.compliance.batch.dto.CountryOptionResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import com.pharos.compliance.batch.repository.BatchExplorerRepository;
import com.pharos.compliance.batch.repository.BatchExplorerRepository.BatchDetailsProjection;
import com.pharos.compliance.batch.repository.BatchExplorerRepository.BatchQueueProjection;
import com.pharos.compliance.batch.repository.BatchExplorerRepository.BatchSummaryProjection;
import com.pharos.compliance.batch.repository.BatchExplorerRepository.NotYetReportedBatchDetailsProjection;
import com.pharos.compliance.batch.service.BatchExplorerService;
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class BatchExplorerServiceImpl implements BatchExplorerService {

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchExplorerServiceImpl.class);
  private static final List<Integer> NO_REPORT_GROUPS = List.of(-1);

  private final BatchExplorerRepository batchExplorerRepository;
  private final CountryCatalog countryCatalog;
  private final Scheduler jdbcScheduler;

  public BatchExplorerServiceImpl(
      BatchExplorerRepository batchExplorerRepository,
      CountryCatalog countryCatalog,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.batchExplorerRepository = batchExplorerRepository;
    this.countryCatalog = countryCatalog;
    this.jdbcScheduler = jdbcScheduler;
  }

  @Override
  public Mono<BatchFilterOptionsResponse> getFilterOptions() {
    return onJdbcScheduler(countryCatalog::getSnapshot)
        .map(
            catalog ->
                new BatchFilterOptionsResponse(
                    catalog.countries().stream()
                        .map(country -> new CountryOptionResponse(country.code(), country.name()))
                        .toList()));
  }

  @Override
  public Mono<BatchExplorerResponse> getBatches(
      LocalDate fromDate,
      LocalDate toDate,
      BatchStatus status,
      BatchIssueType issueType,
      String batchId,
      String country,
      Integer reportGroupId,
      BatchMetricFocus metricFocus,
      int page,
      int size) {
    if (fromDate.isAfter(toDate)) {
      return Mono.error(new InvalidDateRangeException("fromDate must be on or before toDate"));
    }

    String normalizedBatchId = batchId == null ? "" : batchId.trim();
    String normalizedCountryCode = normalizeCountryCode(country);
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    long offset = (long) page * size;

    return onJdbcScheduler(countryCatalog::getSnapshot)
        .flatMap(
            catalog -> {
              CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);
              Mono<BatchSummaryProjection> summary =
                  onJdbcScheduler(
                      () ->
                          batchExplorerRepository.getBatchSummary(
                              fromTimestamp,
                              toTimestampExclusive,
                              normalizedBatchId,
                              reportGroupId,
                              countryFilter.enabled(),
                              countryFilter.reportGroupIds()));
              Mono<List<BatchQueueProjection>> queue =
                  onJdbcScheduler(
                      () ->
                          batchExplorerRepository.getBatchQueue(
                              fromTimestamp,
                              toTimestampExclusive,
                              normalizedBatchId,
                              reportGroupId,
                              countryFilter.enabled(),
                              countryFilter.reportGroupIds(),
                              status.name(),
                              issueType.name(),
                              metricFocus.name(),
                              size,
                              offset));

              return Mono.zip(summary, queue)
                  .map(
                      tuple ->
                          toExplorerResponse(
                              tuple.getT1(),
                              tuple.getT2(),
                              catalog,
                              fromDate,
                              toDate,
                              status,
                              issueType,
                              normalizedBatchId,
                              countryFilter.countryCode(),
                              reportGroupId,
                              metricFocus,
                              page,
                              size));
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Batch Explorer query started fromDate={} toDate={} status={} issueType={} country={} batchId={} reportGroupId={} metricFocus={} page={} size={}",
                    fromDate,
                    toDate,
                    status,
                    issueType,
                    normalizedCountryCode,
                    normalizedBatchId,
                    reportGroupId,
                    metricFocus,
                    page,
                    size))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Batch Explorer query completed matchingBatches={} returnedBatches={}",
                    response.matchingBatches(),
                    response.batches().size()));
  }

  @Override
  public Mono<BatchDetailsResponse> getBatchDetails(
      int reportGroupId, String batchId, int sequenceNumber) {
    Mono<BatchDetailsResponse> details =
        sequenceNumber == 0
            ? getNotYetReportedBatchDetails(reportGroupId, batchId)
            : getReconciledBatchDetails(reportGroupId, batchId, sequenceNumber);

    return details
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Batch preview query started reportGroupId={} batchId={} sequenceNumber={}",
                    reportGroupId,
                    batchId,
                    sequenceNumber))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "Batch preview query completed reportGroupId={} batchId={} sequenceNumber={} totalIssues={}",
                    reportGroupId,
                    batchId,
                    sequenceNumber,
                    response.totalIssues()));
  }

  private Mono<BatchDetailsResponse> getReconciledBatchDetails(
      int reportGroupId, String batchId, int sequenceNumber) {
    Mono<BatchDetailsProjection> details =
        onJdbcScheduler(
            () ->
                batchExplorerRepository
                    .getBatchDetails(reportGroupId, batchId, sequenceNumber)
                    .orElseThrow(
                        () ->
                            new ResourceNotFoundException(
                                "Batch was not found for the supplied report group and sequence")));
    Mono<CountryCatalogSnapshot> catalog = onJdbcScheduler(countryCatalog::getSnapshot);
    return Mono.zip(details, catalog).map(tuple -> toDetailsResponse(tuple.getT1(), tuple.getT2()));
  }

  private Mono<BatchDetailsResponse> getNotYetReportedBatchDetails(
      int reportGroupId, String batchId) {
    Mono<NotYetReportedBatchDetailsProjection> details =
        onJdbcScheduler(
            () ->
                batchExplorerRepository
                    .getNotYetReportedBatchDetails(reportGroupId, batchId)
                    .orElseThrow(
                        () ->
                            new ResourceNotFoundException(
                                "Batch was not found for the supplied report group and batch id")));
    Mono<CountryCatalogSnapshot> catalog = onJdbcScheduler(countryCatalog::getSnapshot);
    return Mono.zip(details, catalog)
        .map(tuple -> toDetailsResponseNotYetReported(tuple.getT1(), tuple.getT2()));
  }

  private BatchExplorerResponse toExplorerResponse(
      BatchSummaryProjection summary,
      List<BatchQueueProjection> queue,
      CountryCatalogSnapshot catalog,
      LocalDate fromDate,
      LocalDate toDate,
      BatchStatus status,
      BatchIssueType issueType,
      String batchId,
      String country,
      Integer reportGroupId,
      BatchMetricFocus metricFocus,
      int page,
      int size) {
    List<BatchQueueItemResponse> batches =
        queue.stream().map(batch -> toQueueItem(batch, catalog)).toList();
    long matchingBatches = queue.isEmpty() ? 0 : queue.getFirst().getMatchingCount();
    return new BatchExplorerResponse(
        new BatchExplorerSummaryResponse(
            summary.getAllBatches(),
            summary.getSuccessfulBatches(),
            summary.getAttentionBatches(),
            summary.getNotYetReportedBatches()),
        batches,
        matchingBatches,
        page,
        size,
        fromDate,
        toDate,
        status,
        issueType,
        batchId,
        country,
        reportGroupId,
        reportGroupId == null ? null : summary.getReportGroupName(),
        metricFocus);
  }

  private BatchQueueItemResponse toQueueItem(
      BatchQueueProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.getReportGroupId());
    return new BatchQueueItemResponse(
        batch.getReportGroupId(),
        batch.getReportGroupName(),
        batch.getBatchId(),
        batch.getSequenceNumber(),
        country.code(),
        country.name(),
        batch.getReportingPeriodFrom(),
        batch.getReportingPeriodTo(),
        batch.getStartedAt(),
        batch.getCompletedAt(),
        queueItemStatus(batch),
        batch.getTransformationFailures(),
        batch.getMissingAttempts(),
        batch.getFiltrationErrors(),
        batch.getReconciliationImbalance(),
        batch.getReportedTransactions(),
        batch.getExcludedTransactions(),
        batch.getTotalIssues(),
        batch.getDiscoveredTransactions());
  }

  private BatchStatus queueItemStatus(BatchQueueProjection batch) {
    if ("NOT_YET_REPORTED".equals(batch.getStatusBucket())) {
      return BatchStatus.NOT_YET_REPORTED;
    }
    return batch.getTotalIssues() == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION;
  }

  private BatchDetailsResponse toDetailsResponse(
      BatchDetailsProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.getReportGroupId());
    long totalIssues =
        batch.getTransformationFailures()
            + batch.getMissingAttempts()
            + batch.getFiltrationErrors()
            + batch.getReconciliationImbalance();
    boolean transformationBalanced =
        batch.getActualTransformationAttempts()
            == batch.getTransformedActivities() + batch.getTransformationFailures();
    return new BatchDetailsResponse(
        batch.getReportGroupId(),
        batch.getReportGroupName(),
        batch.getBatchId(),
        batch.getSequenceNumber(),
        country.code(),
        country.name(),
        batch.getReportingPeriodFrom(),
        batch.getReportingPeriodTo(),
        batch.getStartedAt(),
        batch.getCompletedAt(),
        durationSeconds(batch.getStartedAt(), batch.getCompletedAt()),
        batch.getCompletedAt() == null ? "RUNNING" : "COMPLETED",
        totalIssues == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION,
        batch.getTransformationFailures(),
        batch.getMissingAttempts(),
        batch.getFiltrationErrors(),
        batch.getReconciliationImbalance(),
        totalIssues,
        batch.getSelectedTransactions(),
        batch.getTransactionAttemptsFound(),
        batch.getExpectedReportableTransactions(),
        batch.getActualReportableTransactions(),
        batch.getExpectedTransformationAttempts(),
        batch.getActualTransformationAttempts(),
        batch.getTransformedActivities(),
        transformationBalanced,
        batch.getTransformerOutput(),
        null,
        batch.getExcludedTransactions(),
        batch.getJourneyAvailable(),
        false,
        batch.getExclusionsAvailable(),
        0,
        0);
  }

  private BatchDetailsResponse toDetailsResponseNotYetReported(
      NotYetReportedBatchDetailsProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.getReportGroupId());
    return new BatchDetailsResponse(
        batch.getReportGroupId(),
        batch.getReportGroupName(),
        batch.getBatchId(),
        0,
        country.code(),
        country.name(),
        null,
        null,
        batch.getStartedAt(),
        null,
        0,
        "IN_PROGRESS",
        BatchStatus.NOT_YET_REPORTED,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        null,
        0,
        batch.getJourneyAvailable(),
        false,
        batch.getExclusionsAvailable(),
        batch.getDiscoveredTransactions(),
        batch.getStalledTransactions());
  }

  private CountryFilter resolveCountryFilter(CountryCatalogSnapshot catalog, String countryCode) {
    if ("ALL".equals(countryCode)) {
      return new CountryFilter("ALL", false, NO_REPORT_GROUPS);
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

  private long durationSeconds(LocalDateTime startedAt, LocalDateTime completedAt) {
    return startedAt == null || completedAt == null
        ? 0
        : Math.max(0, Duration.between(startedAt, completedAt).toSeconds());
  }

  private <T> Mono<T> onJdbcScheduler(Callable<T> databaseCall) {
    return Mono.fromCallable(databaseCall).subscribeOn(jdbcScheduler);
  }

  private record CountryFilter(String countryCode, boolean enabled, List<Integer> reportGroupIds) {}
}
