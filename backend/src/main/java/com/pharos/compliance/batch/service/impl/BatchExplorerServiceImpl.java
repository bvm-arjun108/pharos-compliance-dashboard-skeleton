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
import com.pharos.compliance.batch.repository.projection.BatchDetailsProjection;
import com.pharos.compliance.batch.repository.projection.BatchQueueProjection;
import com.pharos.compliance.batch.repository.projection.BatchSummaryProjection;
import com.pharos.compliance.batch.repository.projection.NotYetReportedBatchDetailsProjection;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BatchExplorerServiceImpl implements BatchExplorerService {
  private static final Logger LOGGER = LoggerFactory.getLogger(BatchExplorerServiceImpl.class);
  private static final List<Integer> NO_REPORT_GROUPS = List.of(-1);
  private final BatchExplorerRepository batchExplorerRepository;
  private final CountryCatalog countryCatalog;

  public BatchExplorerServiceImpl(BatchExplorerRepository batchExplorerRepository, CountryCatalog countryCatalog) {
    this.batchExplorerRepository = batchExplorerRepository;
    this.countryCatalog = countryCatalog;
  }

  @Override
  public BatchFilterOptionsResponse getFilterOptions() {
    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    return new BatchFilterOptionsResponse(catalog
      .countries()
      .stream()
      .map(country -> new CountryOptionResponse(country.code(), country.name()))
      .toList());
  }

  @Override
  public BatchExplorerResponse getBatches(LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId,
      String country, Integer reportGroupId, BatchMetricFocus metricFocus, int page, int size) {
    if (fromDate.isAfter(toDate)) {
      throw new InvalidDateRangeException("fromDate must be on or before toDate");
    }

    String normalizedBatchId = batchId == null ? "" : batchId.trim();
    String normalizedCountryCode = normalizeCountryCode(country);
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    long offset = (long) page * size;
    long startedAt = System.nanoTime();

    LOGGER.debug("Batch queue scope resolved | period={}..{} | country={} | reportGroupId={} | batchFilter={} | status={} | issueType={}"
        + " | metricFocus={} | page={} | size={}", fromDate, toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId,
        normalizedBatchId.isEmpty() ? "ALL" : normalizedBatchId, status, issueType, metricFocus, page, size);

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(catalog, normalizedCountryCode);
    BatchSummaryProjection summary = batchExplorerRepository.getBatchSummary(fromTimestamp, toTimestampExclusive, normalizedBatchId,
        reportGroupId, countryFilter.enabled(), countryFilter.reportGroupIds());
    List<BatchQueueProjection> queue = batchExplorerRepository.getBatchQueue(fromTimestamp, toTimestampExclusive, normalizedBatchId,
        reportGroupId, countryFilter.enabled(), countryFilter.reportGroupIds(), status.name(), issueType.name(), metricFocus.name(), size,
        offset);

    BatchExplorerResponse response = toExplorerResponse(summary, queue, catalog, fromDate, toDate, status, issueType, normalizedBatchId,
        countryFilter.countryCode(), reportGroupId, metricFocus, page, size);

    LOGGER.info("Batch queue ready | period={}..{} | country={} | reportGroupId={} | status={} | issueType={} | metricFocus={}"
        + " | all={} | successful={} | attention={} | notYetReported={} | matched={} | returned={} | page={} | size={} | duration={}ms",
        fromDate, toDate, countryFilter.countryCode(), reportGroupId == null ? "ALL" : reportGroupId, status, issueType, metricFocus,
        summary.allBatches(), summary.successfulBatches(), summary.attentionBatches(), summary.notYetReportedBatches(),
        response.matchingBatches(), response.batches().size(), page, size, (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  @Override
  public BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    long startedAt = System.nanoTime();
    LOGGER.debug("Batch details requested | reportGroupId={} | batchId={} | sequence={}", reportGroupId, batchId, sequenceNumber);

    BatchDetailsResponse response = sequenceNumber == 0
        ? getNotYetReportedBatchDetails(reportGroupId, batchId)
        : getReconciledBatchDetails(reportGroupId, batchId, sequenceNumber);

    LOGGER.info("Batch details ready | reportGroupId={} | reportGroupName={} | batchId={} | sequence={} | status={} | operationalStatus={}"
        + " | issues={} | selectedTransactions={} | transformerOutput={} | excludedTransactions={} | duration={}ms", reportGroupId,
        response.reportGroupName(), batchId, sequenceNumber, response.status(), response.operationalStatus(), response.totalIssues(),
        response.selectedTransactions(), response.transformerOutput(), response.excludedTransactions(),
        (System.nanoTime() - startedAt) / 1_000_000);
    return response;
  }

  private BatchDetailsResponse getReconciledBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    BatchDetailsProjection details = batchExplorerRepository
      .getBatchDetails(reportGroupId, batchId, sequenceNumber)
      .orElseThrow(() -> new ResourceNotFoundException("Batch was not found for the supplied report group and sequence"));
    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    return toDetailsResponse(details, catalog);
  }

  private BatchDetailsResponse getNotYetReportedBatchDetails(int reportGroupId, String batchId) {
    NotYetReportedBatchDetailsProjection details = batchExplorerRepository
      .getNotYetReportedBatchDetails(reportGroupId, batchId)
      .orElseThrow(() -> new ResourceNotFoundException("Batch was not found for the supplied report group and batch id"));
    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    return toDetailsResponseNotYetReported(details, catalog);
  }

  private BatchExplorerResponse toExplorerResponse(BatchSummaryProjection summary, List<BatchQueueProjection> queue,
      CountryCatalogSnapshot catalog, LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId,
      String country, Integer reportGroupId, BatchMetricFocus metricFocus, int page, int size) {
    List<BatchQueueItemResponse> batches = queue
      .stream()
      .map(batch -> toQueueItem(batch, catalog))
      .toList();
    long matchingBatches = queue.isEmpty() ? 0 : queue.getFirst().matchingCount();
    return new BatchExplorerResponse(new BatchExplorerSummaryResponse(summary.allBatches(), summary.successfulBatches(),
            summary.attentionBatches(), summary.notYetReportedBatches()), batches, matchingBatches, page, size, fromDate, toDate, status,
        issueType, batchId, country, reportGroupId, reportGroupId == null ? null : summary.reportGroupName(), metricFocus);
  }

  private BatchQueueItemResponse toQueueItem(BatchQueueProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.reportGroupId());
    return new BatchQueueItemResponse(batch.reportGroupId(), batch.reportGroupName(), batch.batchId(), batch.sequenceNumber(),
        country.code(), country.name(), batch.reportingPeriodFrom(), batch.reportingPeriodTo(), batch.startedAt(), batch.completedAt(),
        queueItemStatus(batch), batch.transformationFailures(), batch.missingAttempts(), batch.activityMissing(), batch.filtrationErrors(),
        batch.reconciliationImbalance(), batch.transformerOutput(), batch.excludedTransactions(), batch.duplicateTransactions(),
        batch.simulatedTransactions(), batch.softDedupTransactions(), batch.totalIssues(), batch.discoveredTransactions());
  }

  private BatchStatus queueItemStatus(BatchQueueProjection batch) {
    if ("NOT_YET_REPORTED".equals(batch.statusBucket())) {
      return BatchStatus.NOT_YET_REPORTED;
    }
    return batch.totalIssues() == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION;
  }

  private BatchDetailsResponse toDetailsResponse(BatchDetailsProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.reportGroupId());
    long totalIssues = batch.transformationFailures() + batch.missingAttempts() + batch.activityMissing();
    boolean transformationBalanced = batch.actualTransformationAttempts() == batch.transformedActivities() + batch.transformationFailures();
    return new BatchDetailsResponse(batch.reportGroupId(), batch.reportGroupName(), batch.batchId(), batch.sequenceNumber(), country.code(),
        country.name(), batch.reportingPeriodFrom(), batch.reportingPeriodTo(), batch.startedAt(), batch.completedAt(),
        durationSeconds(batch.startedAt(), batch.completedAt()), batch.completedAt() == null ? "RUNNING" : "COMPLETED",
        totalIssues == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION, batch.transformationFailures(), batch.missingAttempts(),
        batch.activityMissing(), batch.duplicateTransactions(), batch.filtrationErrors(), batch.reconciliationImbalance(), totalIssues,
        batch.selectedTransactions(), batch.transactionAttemptsFound(), batch.expectedReportableTransactions(),
        batch.actualReportableTransactions(), batch.expectedTransformationAttempts(), batch.actualTransformationAttempts(),
        batch.transformedActivities(), transformationBalanced, batch.transformerOutput(), null, batch.excludedTransactions(),
        batch.simulatedTransactions(), batch.alreadyReportedTransactions(), batch.softDedupTransactions(), batch.journeyAvailable(), false,
        batch.exclusionsAvailable(), 0, 0);
  }

  private BatchDetailsResponse toDetailsResponseNotYetReported(NotYetReportedBatchDetailsProjection batch, CountryCatalogSnapshot catalog) {
    CountryDefinition country = catalog.getForReportGroup(batch.reportGroupId());
    return new BatchDetailsResponse(batch.reportGroupId(), batch.reportGroupName(), batch.batchId(), 0, country.code(), country.name(), null,
        null, batch.startedAt(), null, 0, "IN_PROGRESS", BatchStatus.NOT_YET_REPORTED, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0,
        null, 0, 0, 0, 0, batch.journeyAvailable(), false, batch.exclusionsAvailable(), batch.discoveredTransactions(),
        batch.stalledTransactions());
  }

  private CountryFilter resolveCountryFilter(CountryCatalogSnapshot catalog, String countryCode) {
    if ("ALL".equals(countryCode)) {
      return new CountryFilter("ALL", false, NO_REPORT_GROUPS);
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

  private long durationSeconds(LocalDateTime startedAt, LocalDateTime completedAt) {
    return startedAt == null || completedAt == null ? 0 : Math.max(0, Duration.between(startedAt, completedAt).toSeconds());
  }

  private record CountryFilter(String countryCode, boolean enabled, List<Integer> reportGroupIds) {}
}
