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
    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    return new BatchFilterOptionsResponse(countryCatalogSnapshot
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
    long operationStartedAtNanos = System.nanoTime();

    LOGGER.debug("Batch queue scope resolved | period={}..{} | country={} | reportGroupId={} | batchFilter={} | status={} | issueType={}"
        + " | metricFocus={} | page={} | size={}", fromDate, toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId,
        normalizedBatchId.isEmpty() ? "ALL" : normalizedBatchId, status, issueType, metricFocus, page, size);

    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolveCountryFilter(countryCatalogSnapshot, normalizedCountryCode);
    BatchSummaryProjection batchSummary = batchExplorerRepository.getBatchSummary(fromTimestamp, toTimestampExclusive, normalizedBatchId,
        reportGroupId, countryFilter.enabled(), countryFilter.reportGroupIds());
    List<BatchQueueProjection> batchQueue = batchExplorerRepository.getBatchQueue(fromTimestamp, toTimestampExclusive, normalizedBatchId,
        reportGroupId, countryFilter.enabled(), countryFilter.reportGroupIds(), status.name(), issueType.name(), metricFocus.name(), size,
        offset);

    BatchExplorerResponse batchExplorerResponse = toExplorerResponse(batchSummary, batchQueue, countryCatalogSnapshot, fromDate, toDate,
        status, issueType, normalizedBatchId, countryFilter.countryCode(), reportGroupId, metricFocus, page, size);

    LOGGER.info("Batch queue ready | period={}..{} | country={} | reportGroupId={} | status={} | issueType={} | metricFocus={}"
        + " | all={} | successful={} | attention={} | matched={} | returned={} | page={} | size={} | duration={}ms", fromDate, toDate,
        countryFilter.countryCode(), reportGroupId == null ? "ALL" : reportGroupId, status, issueType, metricFocus,
        batchSummary.allBatches(), batchSummary.successfulBatches(), batchSummary.attentionBatches(),
        batchExplorerResponse.matchingBatches(), batchExplorerResponse.batches().size(), page, size,
        (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return batchExplorerResponse;
  }

  @Override
  public BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    long operationStartedAtNanos = System.nanoTime();
    LOGGER.debug("Batch details requested | reportGroupId={} | batchId={} | sequence={}", reportGroupId, batchId, sequenceNumber);

    BatchDetailsProjection batchDetails = batchExplorerRepository
      .getBatchDetails(reportGroupId, batchId, sequenceNumber)
      .orElseThrow(() -> new ResourceNotFoundException("Batch was not found for the supplied report group and sequence"));
    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    BatchDetailsResponse batchDetailsResponse = toDetailsResponse(batchDetails, countryCatalogSnapshot);

    LOGGER.info("Batch details ready | reportGroupId={} | reportGroupName={} | batchId={} | sequence={} | status={} | operationalStatus={}"
        + " | issues={} | selectedTransactions={} | transformerOutput={} | excludedTransactions={} | duration={}ms", reportGroupId,
        batchDetailsResponse.reportGroupName(), batchId, sequenceNumber, batchDetailsResponse.status(),
        batchDetailsResponse.operationalStatus(), batchDetailsResponse.totalIssues(), batchDetailsResponse.selectedTransactions(),
        batchDetailsResponse.transformerOutput(), batchDetailsResponse.excludedTransactions(),
        (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    return batchDetailsResponse;
  }

  private BatchExplorerResponse toExplorerResponse(BatchSummaryProjection batchSummary, List<BatchQueueProjection> batchQueue,
      CountryCatalogSnapshot countryCatalogSnapshot, LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType,
      String batchId, String country, Integer reportGroupId, BatchMetricFocus metricFocus, int page, int size) {
    List<BatchQueueItemResponse> batchQueueItems =
        batchQueue
      .stream()
      .map(batchProjection -> toQueueItem(batchProjection, countryCatalogSnapshot))
      .toList();
    long matchingBatchCount = batchQueue.isEmpty() ? 0 : batchQueue.getFirst().matchingCount();
    return new BatchExplorerResponse(new BatchExplorerSummaryResponse(batchSummary.allBatches(), batchSummary.successfulBatches(),
            batchSummary.attentionBatches(), batchSummary.activityMissingBatches(), batchSummary.missingAttemptBatches(),
            batchSummary.transformationBatches(), batchSummary.duplicateTransactionBatches(), batchSummary.exclusionBatches(),
            batchSummary.simulatedTransactionBatches(), batchSummary.softDedupBatches()), batchQueueItems, matchingBatchCount, page, size,
        fromDate, toDate, status, issueType, batchId, country, reportGroupId, reportGroupId == null ? null : batchSummary.reportGroupName(),
        metricFocus);
  }

  private BatchQueueItemResponse toQueueItem(BatchQueueProjection batchProjection, CountryCatalogSnapshot countryCatalogSnapshot) {
    CountryDefinition countryDefinition = countryCatalogSnapshot.getForReportGroup(batchProjection.reportGroupId());
    return new BatchQueueItemResponse(batchProjection.reportGroupId(), batchProjection.reportGroupName(), batchProjection.batchId(),
        batchProjection.sequenceNumber(), countryDefinition.code(), countryDefinition.name(), batchProjection.reportingPeriodFrom(),
        batchProjection.reportingPeriodTo(), batchProjection.startedAt(), batchProjection.completedAt(), queueItemStatus(batchProjection),
        batchProjection.transformationFailures(), batchProjection.reportedTransformationFailures(),
        batchProjection.transformationFailureMismatch(), batchProjection.missingAttempts(), batchProjection.activityMissing(),
        batchProjection.filtrationErrors(), batchProjection.reconciliationImbalance(), batchProjection.transformerOutput(),
        batchProjection.excludedTransactions(), batchProjection.duplicateTransactions(), batchProjection.simulatedTransactions(),
        batchProjection.softDedupTransactions(), batchProjection.totalIssues());
  }

  private BatchStatus queueItemStatus(BatchQueueProjection batch) {
    return batch.totalIssues() == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION;
  }

  private BatchDetailsResponse toDetailsResponse(BatchDetailsProjection batchDetails, CountryCatalogSnapshot countryCatalogSnapshot) {
    CountryDefinition countryDefinition = countryCatalogSnapshot.getForReportGroup(batchDetails.reportGroupId());
    long totalIssues = batchDetails.transformationFailures() + batchDetails.missingAttempts() + batchDetails.activityMissing();
    boolean transformationBalanced =
        batchDetails.actualTransformationAttempts() == batchDetails.transformedActivities() + batchDetails.transformationFailures();
    return new BatchDetailsResponse(batchDetails.reportGroupId(), batchDetails.reportGroupName(), batchDetails.batchId(),
        batchDetails.sequenceNumber(), countryDefinition.code(), countryDefinition.name(), batchDetails.reportingPeriodFrom(),
        batchDetails.reportingPeriodTo(), batchDetails.startedAt(), batchDetails.completedAt(),
        durationSeconds(batchDetails.startedAt(), batchDetails.completedAt()), batchDetails.completedAt() == null ? "RUNNING" : "COMPLETED",
        totalIssues == 0 ? BatchStatus.SUCCESSFUL : BatchStatus.ATTENTION, batchDetails.transformationFailures(),
        batchDetails.reportedTransformationFailures(), batchDetails.transformationFailureMismatch(), batchDetails.missingAttempts(),
        batchDetails.activityMissing(), batchDetails.duplicateTransactions(), batchDetails.filtrationErrors(),
        batchDetails.reconciliationImbalance(), totalIssues, batchDetails.selectedTransactions(), batchDetails.transactionAttemptsFound(),
        batchDetails.expectedReportableTransactions(), batchDetails.actualReportableTransactions(),
        batchDetails.expectedTransformationAttempts(), batchDetails.actualTransformationAttempts(), batchDetails.transformedActivities(),
        transformationBalanced, batchDetails.transformerOutput(), null, batchDetails.excludedTransactions(),
        batchDetails.simulatedTransactions(), batchDetails.alreadyReportedTransactions(), batchDetails.softDedupTransactions(),
        batchDetails.journeyAvailable(), false, batchDetails.exclusionsAvailable(), batchDetails.reportSelectionVersionId(),
        batchDetails.transformerVersionId());
  }

  private CountryFilter resolveCountryFilter(CountryCatalogSnapshot countryCatalogSnapshot, String countryCode) {
    if ("ALL".equals(countryCode)) {
      return new CountryFilter("ALL", false, NO_REPORT_GROUPS);
    }
    CountryDefinition countryDefinition = countryCatalogSnapshot
      .findByCode(countryCode)
      .orElseThrow(() -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(countryCode, true, countryDefinition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private long durationSeconds(LocalDateTime startedAt, LocalDateTime completedAt) {
    return startedAt == null || completedAt == null ? 0 : Math.max(0, Duration.between(startedAt, completedAt).toSeconds());
  }

  private record CountryFilter(String countryCode, boolean enabled, List<Integer> reportGroupIds) {}
}
