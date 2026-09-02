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
import com.pharos.compliance.transaction.dto.TransactionReportContextResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import com.pharos.compliance.transaction.repository.TransactionEvidenceCache;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import com.pharos.compliance.transaction.service.TransactionReportService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionReportServiceImpl implements TransactionReportService {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportServiceImpl.class);
  private final TransactionEvidenceCache transactionEvidenceCache;
  private final CountryCatalog countryCatalog;

  public TransactionReportServiceImpl(TransactionEvidenceCache transactionEvidenceCache, CountryCatalog countryCatalog) {
    this.transactionEvidenceCache = transactionEvidenceCache;
    this.countryCatalog = countryCatalog;
  }

  @Override
  public TransactionReportResponse getTransactionReport(int reportGroupId, String batchId, int sequenceNumber, TransactionMetric metric,
      String search, TransactionEvidenceSource source, TransactionStage stage, TransactionOutcome outcome, TransactionStatus status,
      TransactionSortDirection sortDirection, int page, int size) {
    String normalizedSearch = search == null ? "" : search.trim();
    long offset = (long) page * size;

    return logOperation("Transaction evidence report",
        () -> LOGGER.debug("Transaction evidence scope resolved | reportGroupId={} | batchId={} | sequence={} | metric={} | source={}"
            + " | stage={} | outcome={} | status={} | sortDirection={} | searchApplied={} | page={} | size={}", reportGroupId, batchId,
            sequenceNumber, metric, source, stage, outcome, status, sortDirection, !normalizedSearch.isEmpty(), page, size),
        () -> {
          TransactionReportContextProjection context = transactionEvidenceCache
            .findReportContext(reportGroupId, batchId, sequenceNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Reconciliation batch was not found"));
          List<TransactionEvidenceProjection> evidence = transactionEvidenceCache.findEvidenceRecords(reportGroupId, batchId, metric.name(),
              normalizedSearch, source.name(), stage.name(), outcome.name(), status.name(), sortDirection.name(), size, offset);
          long matchingCount = transactionEvidenceCache.countEvidenceRecords(reportGroupId, batchId, metric.name(), normalizedSearch,
              source.name(), stage.name(), outcome.name(), status.name());
          long availableRecordCount = hasDefaultEvidenceFilters(normalizedSearch, source, stage, outcome, status)
          ? matchingCount
          : availableRecordCount(reportGroupId, batchId, metric);
          long aggregateCount = aggregateCount(context, metric);
          TransactionEvidenceLevel evidenceLevel = evidenceLevel(aggregateCount, availableRecordCount);
          CountryDefinition country = countryCatalog.getSnapshot().getForReportGroup(reportGroupId);

          return new TransactionReportResponse(toContext(context, country), metric, metricLabel(metric), aggregateCount,
              availableRecordCount, matchingCount, evidenceLevel, evidenceMessage(evidenceLevel, aggregateCount, availableRecordCount),
              evidence.stream().map(this::toEvidenceRecord).toList(), normalizedSearch, source, stage, outcome, status, sortDirection, page,
              size);
        },
        response -> "reportGroupId=" + reportGroupId + " | batchId=" + batchId + " | metric=" + metric + " | source=" + source + " | stage="
        + stage + " | outcome=" + outcome + " | status=" + status + " | aggregate=" + response.aggregateCount() + " | available="
        + response.availableRecordCount() + " | matched=" + response.matchingRecordCount() + " | returned=" + response
              .transactions()
              .size() + " | evidenceLevel=" + response.evidenceLevel() + " | page=" + page + " | size=" + size);
  }

  /**
   * One summary line per service operation, emitted on completion with its total duration. The
   * request access log records HTTP status and duration, while the jOOQ execution listener records
   * each individual database statement and its duration at DEBUG.
   *
   * <p>The full filter set goes to DEBUG rather than INFO: at production request volume a
   * twelve-field parameter dump on every call is noise, and the values that matter for triage are
   * repeated on the INFO summary. Note the search term itself is deliberately never logged, only
   * whether a search was supplied — operators search by MTCN and transaction identifier, so the
   * raw term is customer transaction data that does not belong in application logs.
   *
   * <p>If {@code query} throws, it propagates immediately; the global exception handler and
   * request-level access log record the failure once with the same trace identifiers.
   */
  private <T> T logOperation(String label, Runnable logFilters, Supplier<T> query, Function<T, String> summarize) {
    long startedAt = System.nanoTime();
    if (LOGGER.isDebugEnabled()) {
      logFilters.run();
    }
    T response = query.get();
    if (response != null) {
      LOGGER.info("{} ready | {} | duration={}ms", label, summarize.apply(response), (System.nanoTime() - startedAt) / 1_000_000);
    }
    return response;
  }

  @Override
  public PeriodTransactionReportResponse getPeriodTransactionReport(LocalDate fromDate, LocalDate toDate, String country,
      Integer reportGroupId, String search, TransactionOutcome outcome, TransactionStatus status, TransactionSortDirection sortDirection,
      int page, int size) {
    if (fromDate.isAfter(toDate)) {
      throw new InvalidDateRangeException("fromDate must be on or before toDate");
    }
    String normalizedSearch = search == null ? "" : search.trim();
    String normalizedCountry = normalizeCountryCode(country);
    boolean filterByReportGroup = reportGroupId != null;
    int reportGroupIdFilter = filterByReportGroup ? reportGroupId : -1;
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    long offset = (long) page * size;

    CountryCatalogSnapshot catalog = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolvePeriodCountryFilter(catalog, normalizedCountry, reportGroupId);

    return logOperation("Period transaction evidence report",
        () -> LOGGER.debug("Period transaction evidence scope resolved | period={}..{} | country={} | reportGroupId={} | outcome={}"
            + " | status={} | sortDirection={} | searchApplied={} | page={} | size={}", fromDate, toDate, normalizedCountry,
            reportGroupId == null ? "ALL" : reportGroupId, outcome, status, sortDirection, !normalizedSearch.isEmpty(), page, size),
        () -> {
          PeriodAggregateProjection aggregate = transactionEvidenceCache.findPeriodAggregate(fromTimestamp, toTimestampExclusive,
              countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter);
          List<TransactionEvidenceProjection> evidence = transactionEvidenceCache.findPeriodEvidenceRecords(fromTimestamp,
              toTimestampExclusive, countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter,
              normalizedSearch, outcome.name(), status.name(), sortDirection.name(), size, offset);
          long matchingCount = transactionEvidenceCache.countPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive,
              countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter, normalizedSearch,
              outcome.name(), status.name());
          // The reconciliation-sourced excluded_txn sum is only a meaningful "aggregate"
          // to compare record counts against when the user is actually viewing Excluded
          // evidence — it has no equivalent for Success/Reported/etc, so treating it as
          // the aggregate for every status would make evidenceLevel/evidenceMessage
          // compare unrelated numbers (e.g. "25 records for an aggregate of 37" while
          // viewing Success, where 37 is the unrelated excluded count). For every other
          // status, the matching record count IS the full picture, so it doubles as its
          // own aggregate.
          long aggregateCount = status == TransactionStatus.EXCLUDED ? aggregate.totalExcluded() : matchingCount;
          long availableRecordCount = matchingCount;
          TransactionEvidenceLevel evidenceLevel = evidenceLevel(aggregateCount, availableRecordCount);
          CountryDefinition countryDefinition = filterByReportGroup
          ? catalog.getForReportGroup(reportGroupId)
          : catalog.findByCode(normalizedCountry).orElse(new CountryDefinition("ALL", "All countries", Set.of()));

          return new PeriodTransactionReportResponse(new PeriodTransactionContextResponse(reportGroupId,
                  filterByReportGroup ? aggregate.reportGroupName() : null, countryDefinition.code(), countryDefinition.name(), fromDate,
                  toDate, aggregate.batchCount()), periodMetricLabel(status), aggregateCount, availableRecordCount, matchingCount,
              evidenceLevel, evidenceMessage(evidenceLevel, aggregateCount, availableRecordCount),
              evidence.stream().map(this::toEvidenceRecord).toList(), normalizedSearch, outcome, status, sortDirection, page, size);
        },
        response -> "period=" + fromDate + ".." + toDate + " | country=" + normalizedCountry + " | reportGroupId="
        + (reportGroupId == null ? "ALL" : reportGroupId) + " | outcome=" + outcome + " | status=" + status + " | batches="
        + response.context().batchCount() + " | aggregate=" + response.aggregateCount() + " | available=" + response.availableRecordCount()
        + " | matched=" + response.matchingRecordCount() + " | returned=" + response.transactions().size() + " | evidenceLevel="
        + response.evidenceLevel() + " | page=" + page + " | size=" + size);
  }

  private CountryFilter resolvePeriodCountryFilter(CountryCatalogSnapshot catalog, String countryCode, Integer reportGroupId) {
    if (reportGroupId != null || "ALL".equals(countryCode)) {
      return new CountryFilter(false, List.of(-1));
    }
    CountryDefinition definition =
        catalog
      .findByCode(countryCode)
      .orElseThrow(() -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(true, definition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private record CountryFilter(boolean enabled, List<Integer> reportGroupIds) {}

  private TransactionReportContextResponse toContext(TransactionReportContextProjection context, CountryDefinition country) {
    return new TransactionReportContextResponse(context.reportGroupId(), context.reportGroupName(), context.batchId(),
        context.sequenceNumber(), country.code(), country.name(), context.reportingPeriodFrom(), context.reportingPeriodTo());
  }

  private TransactionEvidenceRecordResponse toEvidenceRecord(TransactionEvidenceProjection row) {
    return new TransactionEvidenceRecordResponse(row.recordKey(), row.identifier(), row.mtcn(), row.batchId(),
        TransactionEvidenceSource.valueOf(row.evidenceSource()), row.stage(), row.status(), TransactionOutcome.valueOf(row.outcome()),
        row.comments(), row.skipReason(), row.ruleId(), row.exclusionReason(), row.exclusionStrategy(), row.reportedBatchId(),
        row.reportingTimestamp(), row.modifiedAt(), row.processingComplete(), row.currencyAmount(), row.currencyCode(),
        row.transactionDate(), row.transactionSide(), row.txnSource(), row.activityType(), row.sendDate(), row.galacticId(), row.bucketId(),
        row.attemptId(), row.senderName(), row.receiverName(), row.senderCity(), row.senderCountry(), row.senderPhone(),
        row.senderDateOfBirth(), row.senderIdType(), row.senderIdNumber(), row.receiverCity(), row.receiverCountry(), row.receiverPhone(),
        row.receiverDateOfBirth(), row.receiverIdType(), row.receiverIdNumber(), row.transactionStatus(), row.transactionSubStatus(),
        row.ruleHitsJson());
  }

  private long aggregateCount(TransactionReportContextProjection context, TransactionMetric metric) {
    return switch (metric) {
      case ALL, SELECTED -> context.selectedTransactions();
      case ATTEMPTS_FOUND -> context.attemptsFound();
      case MISSING -> context.missingAttempts();
      case EXPECTED_ELIGIBLE -> context.expectedEligible();
      case ACTUAL_ELIGIBLE -> context.actualEligible();
      case TRANSFORMED -> context.transformed();
      case FAILED -> context.failed();
      case EXPECTED_REPORTABLE -> context.expectedReportable();
      case ACTUAL_REPORTABLE, TRANSFORMER_OUTPUT -> context.actualReportable();
      case EXCLUDED -> context.excluded();
      case SIMULATED -> context.simulated();
      case ALREADY_REPORTED -> context.alreadyReported();
      case SOFT_DEDUP -> context.softDedup();
      // Mirrors the Data Selection card's "Filtered data" tile exactly: every reason a
      // selected transaction did not carry through. Missing attempts are part of that
      // total but have no record-level rows, so evidenceLevel/evidenceMessage will
      // report the shortfall rather than the table silently coming up short.
      case FILTERED -> context.missingAttempts() + context.excluded() + context.simulated() + context.alreadyReported()
          + context.softDedup();
      case FILTRATION_VARIANCE -> context.filtrationVariance();
      case RECONCILIATION_VARIANCE -> context.reconciliationVariance();
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
      case FILTERED -> "Filtered transactions";
      case FILTRATION_VARIANCE -> "Filtration variance";
      case RECONCILIATION_VARIANCE -> "Reconciliation variance";
      case TRANSFORMER_OUTPUT -> "Transformer output";
    };
  }

  /**
   * Unlike the single-batch report (one fixed metric per page), the period-wide report's only axis
   * is the status filter — the heading reflects whatever status is currently selected, since a user
   * can pivot it freely to browse any evidence for the scoped report group/period.
   */
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

  private boolean hasDefaultEvidenceFilters(String search, TransactionEvidenceSource source, TransactionStage stage,
      TransactionOutcome outcome, TransactionStatus status) {
    return search.isEmpty() && source == TransactionEvidenceSource.ALL && stage == TransactionStage.ALL && outcome == TransactionOutcome.ALL
        && status == TransactionStatus.ALL;
  }

  private long availableRecordCount(int reportGroupId, String batchId, TransactionMetric metric) {
    return transactionEvidenceCache.countEvidenceRecords(reportGroupId, batchId, metric.name(), "", TransactionEvidenceSource.ALL.name(),
        TransactionStage.ALL.name(), TransactionOutcome.ALL.name(), TransactionStatus.ALL.name());
  }

  private String evidenceMessage(TransactionEvidenceLevel level, long aggregateCount, long availableRecords) {
    return switch (level) {
      case RECORD_LEVEL -> "Record-level evidence is available for the full aggregate count in this batch.";
      case PARTIAL_RECORD_LEVEL -> availableRecords + " latest-state record(s) are available for an aggregate count of " + aggregateCount
          + ". Journey is latest-state evidence, not event history.";
      case AGGREGATE_ONLY -> "The count of " + aggregateCount
          + " is available only as batch reconciliation evidence; Phase 1 has no authoritative transaction rows for this metric.";
      case NO_RECORDS -> "The batch has no transactions for this metric.";
    };
  }
}
