package com.pharos.compliance.transaction.service.impl;

import com.pharos.compliance.common.exception.ResourceNotFoundException;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
import com.pharos.compliance.transaction.dto.TransactionEvidenceRecordResponse;
import com.pharos.compliance.transaction.dto.TransactionReportContextResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.repository.TransactionReportRepository;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionReportContextProjection;
import com.pharos.compliance.transaction.service.TransactionReportService;
import java.util.List;
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

  private final TransactionReportRepository transactionReportRepository;
  private final CountryCatalog countryCatalog;
  private final Scheduler jdbcScheduler;

  public TransactionReportServiceImpl(
      TransactionReportRepository transactionReportRepository,
      CountryCatalog countryCatalog,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.transactionReportRepository = transactionReportRepository;
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
      TransactionOutcome outcome,
      int page,
      int size) {
    String normalizedSearch = search == null ? "" : search.trim();
    long offset = (long) page * size;

    return onJdbcScheduler(
            () -> {
              TransactionReportContextProjection context =
                  transactionReportRepository
                      .findReportContext(reportGroupId, batchId, sequenceNumber)
                      .orElseThrow(
                          () ->
                              new ResourceNotFoundException("Reconciliation batch was not found"));
              List<TransactionEvidenceProjection> evidence =
                  transactionReportRepository.findEvidenceRecords(
                      reportGroupId,
                      batchId,
                      metric.name(),
                      normalizedSearch,
                      source.name(),
                      outcome.name(),
                      size,
                      offset);
              long matchingCount = evidence.isEmpty() ? 0 : evidence.getFirst().getMatchingCount();
              long availableRecordCount =
                  hasDefaultEvidenceFilters(normalizedSearch, source, outcome)
                      ? matchingCount
                      : availableRecordCount(reportGroupId, batchId, metric);
              long aggregateCount = aggregateCount(context, metric);
              TransactionEvidenceLevel evidenceLevel =
                  evidenceLevel(aggregateCount, availableRecordCount);
              CountryDefinition country =
                  countryCatalog.getSnapshot().getForReportGroup(reportGroupId);

              return new TransactionReportResponse(
                  toContext(context, country),
                  metric,
                  metricLabel(metric),
                  aggregateCount,
                  availableRecordCount,
                  matchingCount,
                  evidenceLevel,
                  evidenceMessage(evidenceLevel, aggregateCount, availableRecordCount),
                  evidence.stream().map(this::toEvidenceRecord).toList(),
                  normalizedSearch,
                  source,
                  outcome,
                  page,
                  size);
            })
        .doOnSubscribe(
            ignored ->
                LOGGER.info(
                    "Transaction evidence query started reportGroupId={} batchId={} sequenceNumber={} metric={} source={} outcome={} search={} page={} size={}",
                    reportGroupId,
                    batchId,
                    sequenceNumber,
                    metric,
                    source,
                    outcome,
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
        row.getProcessingComplete());
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
      case FILTRATION_VARIANCE -> "Filtration variance";
      case RECONCILIATION_VARIANCE -> "Reconciliation variance";
      case TRANSFORMER_OUTPUT -> "Transformer output";
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
      String search, TransactionEvidenceSource source, TransactionOutcome outcome) {
    return search.isEmpty()
        && source == TransactionEvidenceSource.ALL
        && outcome == TransactionOutcome.ALL;
  }

  private long availableRecordCount(int reportGroupId, String batchId, TransactionMetric metric) {
    List<TransactionEvidenceProjection> records =
        transactionReportRepository.findEvidenceRecords(
            reportGroupId,
            batchId,
            metric.name(),
            "",
            TransactionEvidenceSource.ALL.name(),
            TransactionOutcome.ALL.name(),
            1,
            0);
    return records.isEmpty() ? 0 : records.getFirst().getMatchingCount();
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
