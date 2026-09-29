package com.wu.compliance.dashboard.transaction.service.impl;

import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.transaction.dto.TransactionEvidenceDetailResponse;
import com.wu.compliance.dashboard.transaction.model.TransactionDetailScope;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.common.exception.ResourceNotFoundException;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.reportgroup.service.CountryCatalog;
import com.wu.compliance.dashboard.transaction.dto.PeriodTransactionContextResponse;
import com.wu.compliance.dashboard.transaction.dto.PeriodTransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionEvidenceRecordResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionReportContextResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionSearchResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionSearchResultResponse;
import com.wu.compliance.dashboard.transaction.model.EvidenceCursor;
import com.wu.compliance.dashboard.transaction.model.TransactionEvidenceLevel;
import com.wu.compliance.dashboard.transaction.model.TransactionEvidenceSource;
import com.wu.compliance.dashboard.transaction.model.TransactionMetric;
import com.wu.compliance.dashboard.transaction.model.TransactionOutcome;
import com.wu.compliance.dashboard.transaction.model.TransactionSearchField;
import com.wu.compliance.dashboard.transaction.model.TransactionSortDirection;
import com.wu.compliance.dashboard.transaction.model.TransactionStage;
import com.wu.compliance.dashboard.transaction.model.TransactionStatus;
import com.wu.compliance.dashboard.transaction.repository.TransactionEvidenceCache;
import com.wu.compliance.dashboard.transaction.repository.TransactionSearchRepository;
import com.wu.compliance.dashboard.transaction.repository.projection.PeriodAggregateProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionEvidenceProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionReportContextProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionSearchResultProjection;
import com.wu.compliance.dashboard.transaction.service.TransactionReportService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
  private final TransactionSearchRepository transactionSearchRepository;

  public TransactionReportServiceImpl(TransactionEvidenceCache transactionEvidenceCache, CountryCatalog countryCatalog,
      TransactionSearchRepository transactionSearchRepository) {
    this.transactionEvidenceCache = transactionEvidenceCache;
    this.countryCatalog = countryCatalog;
    this.transactionSearchRepository = transactionSearchRepository;
  }

  @Override
  public TransactionReportResponse getTransactionReport(int reportGroupId, String batchId, int sequenceNumber, TransactionMetric metric,
      String search, TransactionEvidenceSource source, TransactionStage stage, TransactionOutcome outcome, TransactionStatus status,
      TransactionSortDirection sortDirection, int page, int size, String cursor) {
    String normalizedSearch = search == null ? "" : search.trim();
    long offset = (long) page * size;
    EvidenceCursor decodedCursor = decodeCursor(cursor);

    return logOperation("Transaction evidence report",
        () -> LOGGER.debug("Transaction evidence scope resolved | reportGroupId={} | batchId={} | sequence={} | metric={} | source={}"
            + " | stage={} | outcome={} | status={} | sortDirection={} | searchApplied={} | page={} | size={} | cursorApplied={}",
            reportGroupId, batchId, sequenceNumber, metric, source, stage, outcome, status, sortDirection, !normalizedSearch.isEmpty(), page,
            size, decodedCursor != null),
        () -> {
          TransactionReportContextProjection reportContext = transactionEvidenceCache
            .findReportContext(reportGroupId, batchId, sequenceNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Reconciliation batch was not found"));
          long metricAggregateCount = aggregateCount(reportContext, metric);
          List<TransactionEvidenceProjection> evidenceRecords;
          long matchingRecordCount;
          long availableRecordCount;
          String nextCursor = null;
          if (isAggregateOnlyMetric(metric)) {
            // FILTRATION_VARIANCE/RECONCILIATION_VARIANCE are each an absolute delta between two
            // aggregate counts on the reconciliation row -- there is no row in
            // JOURNEY/EXCLUSION_AUDIT/RULE_HIT that represents "this transaction is part of the
            // gap," so no query condition can ever correctly select record-level evidence for
            // them (MISSING used to be grouped in here too -- see isAggregateOnlyMetric's
            // Javadoc for why that was wrong). Before this check, metricScoped() fell through to
            // its default `true` condition for these, which actually returned every evidence row
            // in the batch -- unrelated, fully-processed transactions mislabeled as if they were
            // the missing or variance ones -- after paying for the full 3-way UNION + LATERAL
            // rule_hit correlated-match pipeline to compute it. Skip straight to the
            // aggregate-only answer instead of running (and misreporting from) that pipeline at
            // all.
            evidenceRecords = List.of();
            matchingRecordCount = 0L;
            availableRecordCount = 0L;
          } else {
            var evidencePage = transactionEvidenceCache.findEvidenceRecords(reportGroupId, batchId, metric.name(), normalizedSearch,
                source.name(), stage.name(), outcome.name(), status.name(), sortDirection.name(), size, offset, decodedCursor);
            evidenceRecords = evidencePage.records();
            nextCursor = evidencePage.nextCursor();
            matchingRecordCount = transactionEvidenceCache.countEvidenceRecords(reportGroupId, batchId, metric.name(), normalizedSearch,
                source.name(), stage.name(), outcome.name(), status.name());
            availableRecordCount = hasDefaultEvidenceFilters(normalizedSearch, source, stage, outcome, status)
            ? matchingRecordCount
            : availableRecordCount(reportGroupId, batchId, metric);
          }
          TransactionEvidenceLevel evidenceLevel = evidenceLevel(metricAggregateCount, availableRecordCount);
          CountryDefinition countryDefinition = countryCatalog.getSnapshot().getForReportGroup(reportGroupId);

          return new TransactionReportResponse(toContext(reportContext, countryDefinition), metric, metricLabel(metric),
              metricAggregateCount, reportedAggregateCount(reportContext, metric), aggregateCountMismatch(reportContext, metric),
              availableRecordCount, matchingRecordCount, evidenceLevel,
              evidenceMessage(evidenceLevel, metricAggregateCount, availableRecordCount),
              evidenceRecords.stream().map(this::toEvidenceRecord).toList(), normalizedSearch, source, stage, outcome, status, sortDirection,
              page, size, nextCursor);
        },
        transactionReportResponse -> "reportGroupId=" + reportGroupId + " | batchId=" + batchId + " | metric=" + metric + " | source="
        + source + " | stage=" + stage + " | outcome=" + outcome + " | status=" + status + " | aggregate="
        + transactionReportResponse.aggregateCount() + " | available=" + transactionReportResponse.availableRecordCount() + " | matched="
        + transactionReportResponse.matchingRecordCount() + " | returned=" + transactionReportResponse.transactions().size()
        + " | evidenceLevel=" + transactionReportResponse.evidenceLevel() + " | page=" + page + " | size=" + size + " | hasNextCursor="
        + (transactionReportResponse.nextCursor() != null));
  }

  /**
   * Blank/absent cursor decodes to null (first page); a malformed one is a client error, not a 500.
   */
  private EvidenceCursor decodeCursor(String cursor) {
    try {
      return EvidenceCursor.decode(cursor);
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException(exception.getMessage());
    }
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
  private <T> T logOperation(String operationName, Runnable logRequestScope, Supplier<T> operation, Function<T, String> summarizeResponse) {
    long operationStartedAtNanos = System.nanoTime();
    if (LOGGER.isDebugEnabled()) {
      logRequestScope.run();
    }
    T operationResponse = operation.get();
    if (operationResponse != null) {
      LOGGER.info("{} ready | {} | duration={}ms", operationName, summarizeResponse.apply(operationResponse),
          (System.nanoTime() - operationStartedAtNanos) / 1_000_000);
    }
    return operationResponse;
  }

  @Override
  public PeriodTransactionReportResponse getPeriodTransactionReport(LocalDate fromDate, LocalDate toDate, String country,
      Integer reportGroupId, String batchId, String search, TransactionOutcome outcome, TransactionStatus status, String reason,
      boolean batchScopedExcluded, TransactionSortDirection sortDirection, int page, int size, String cursor) {
    if (fromDate.isAfter(toDate)) {
      throw new InvalidDateRangeException("fromDate must be on or before toDate");
    }
    String normalizedBatchId = batchId == null ? "" : batchId.trim();
    String normalizedSearch = search == null ? "" : search.trim();
    String normalizedReason = reason == null ? "" : reason.trim();
    String normalizedCountryCode = normalizeCountryCode(country);
    boolean filterByReportGroup = reportGroupId != null;
    int reportGroupIdFilter = filterByReportGroup ? reportGroupId : -1;
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();
    long offset = (long) page * size;
    EvidenceCursor decodedCursor = decodeCursor(cursor);

    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolvePeriodCountryFilter(countryCatalogSnapshot, normalizedCountryCode, reportGroupId);

    return logOperation("Period transaction evidence report",
        () -> LOGGER.debug("Period transaction evidence scope resolved | period={}..{} | country={} | reportGroupId={} | batchFilter={}"
            + " | outcome={} | status={} | sortDirection={} | searchApplied={} | reasonApplied={} | page={} | size={} | cursorApplied={}",
            fromDate, toDate, normalizedCountryCode, reportGroupId == null ? "ALL" : reportGroupId,
            normalizedBatchId.isEmpty() ? "ALL" : normalizedBatchId, outcome, status, sortDirection, !normalizedSearch.isEmpty(),
            !normalizedReason.isEmpty(), page, size, decodedCursor != null),
        () -> {
          PeriodAggregateProjection periodAggregate = transactionEvidenceCache.findPeriodAggregate(fromTimestamp, toTimestampExclusive,
              countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter, normalizedBatchId);
          var evidencePage = transactionEvidenceCache.findPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive, countryFilter.enabled(),
              countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter, normalizedBatchId, normalizedSearch, outcome.name(),
              status.name(), normalizedReason, batchScopedExcluded, sortDirection.name(), size, offset, decodedCursor);
          List<TransactionEvidenceProjection> evidenceRecords = evidencePage.records();
          long matchingRecordCount = transactionEvidenceCache.countPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive,
              countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter, normalizedBatchId,
              normalizedSearch, outcome.name(), status.name(), normalizedReason, batchScopedExcluded);
          // The reconciliation-sourced excluded_txn sum is only a meaningful "aggregate"
          // to compare record counts against when the user is actually viewing Excluded
          // evidence — it has no equivalent for Success/Reported/etc, so treating it as
          // the aggregate for every status would make evidenceLevel/evidenceMessage
          // compare unrelated numbers (e.g. "25 records for an aggregate of 37" while
          // viewing Success, where 37 is the unrelated excluded count). For every other
          // status, the matching record count IS the full picture, so it doubles as its
          // own aggregate.
          //
          // batchScopedExcluded has to be part of the test, not just the status: it is the same flag
          // TransactionReportRepository#countPeriodEvidenceRecords routes on, so it is the only case
          // where matchingRecordCount actually came from
          // PeriodEvidenceQueries#countExcludedEvidenceRecordsForBatchTotal -- the one query defined
          // to match SUM(excluded_txn). Without the flag the count comes from OverviewEvidenceQueries'
          // per-identifier rollup instead ("ever excluded and never reported across every batch in
          // this window"), which is a deliberately different question and legitimately returns a
          // smaller number -- it deduplicates a transaction excluded in several batches down to one,
          // and drops the ones that were excluded by one rule but still reported. Pairing that count
          // with excluded_txn put a permanent "8,038 matching of 8,451 in reconciliation" on the
          // Transactions Overview drilldown and made evidenceLevel/evidenceMessage report ~400
          // transactions as missing evidence when they were never in that query's scope to begin
          // with. The rollup has no reconciliation scalar that answers its question, so like every
          // other non-batch-scoped status it is its own aggregate.
          long periodAggregateCount =
          status == TransactionStatus.EXCLUDED && batchScopedExcluded ? periodAggregate.totalExcluded() : matchingRecordCount;
          long availableRecordCount = matchingRecordCount;
          TransactionEvidenceLevel evidenceLevel = evidenceLevel(periodAggregateCount, availableRecordCount);
          CountryDefinition countryDefinition = filterByReportGroup
          ? countryCatalogSnapshot.getForReportGroup(reportGroupId)
          : countryCatalogSnapshot.findByCode(normalizedCountryCode).orElse(new CountryDefinition("ALL", "All countries", Set.of()));

          return new PeriodTransactionReportResponse(new PeriodTransactionContextResponse(reportGroupId,
                  filterByReportGroup ? periodAggregate.reportGroupName() : null, countryDefinition.code(), countryDefinition.name(),
                  fromDate, toDate, periodAggregate.batchCount()), periodMetricLabel(status), periodAggregateCount, availableRecordCount,
              matchingRecordCount, evidenceLevel, evidenceMessage(evidenceLevel, periodAggregateCount, availableRecordCount),
              evidenceRecords.stream().map(this::toEvidenceRecord).toList(), normalizedSearch, outcome, status, sortDirection, page, size,
              evidencePage.nextCursor());
        },
        periodReportResponse -> "period=" + fromDate + ".." + toDate + " | country=" + normalizedCountryCode + " | reportGroupId="
        + (reportGroupId == null ? "ALL" : reportGroupId) + " | outcome=" + outcome + " | status=" + status + " | batches="
        + periodReportResponse.context().batchCount() + " | aggregate=" + periodReportResponse.aggregateCount() + " | available="
        + periodReportResponse.availableRecordCount() + " | matched=" + periodReportResponse.matchingRecordCount() + " | returned="
        + periodReportResponse.transactions().size() + " | evidenceLevel=" + periodReportResponse.evidenceLevel() + " | page=" + page
        + " | size=" + size + " | hasNextCursor=" + (periodReportResponse.nextCursor() != null));
  }

  /**
   * Backs a batch picker (typeahead) for the period report above -- same scope, no status/metric
   *  filter, not paginated (a reporting period's batch count is small enough to hand back whole).
   */
  @Override
  public List<String> getPeriodReportBatchIds(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId) {
    if (fromDate.isAfter(toDate)) {
      throw new InvalidDateRangeException("fromDate must be on or before toDate");
    }
    String normalizedCountryCode = normalizeCountryCode(country);
    boolean filterByReportGroup = reportGroupId != null;
    int reportGroupIdFilter = filterByReportGroup ? reportGroupId : -1;
    LocalDateTime fromTimestamp = fromDate.atStartOfDay();
    LocalDateTime toTimestampExclusive = toDate.plusDays(1).atStartOfDay();

    CountryCatalogSnapshot countryCatalogSnapshot = countryCatalog.getSnapshot();
    CountryFilter countryFilter = resolvePeriodCountryFilter(countryCatalogSnapshot, normalizedCountryCode, reportGroupId);
    return transactionEvidenceCache.findPeriodBatchIds(fromTimestamp, toTimestampExclusive, countryFilter.enabled(),
        countryFilter.reportGroupIds(), filterByReportGroup, reportGroupIdFilter);
  }

  /**
   * No date range, no report group, no country -- the answer to "I have this MTCN or external
   *  transaction key, which country was it evaluated under?" {@code field} picks the single column
   *  matched (see {@link TransactionSearchField}), removing the ambiguity of matching several
   *  columns against one value at once. Deliberately returns every raw matching row unmerged rather
   *  than trying to collapse them into one answer: the same real transaction can legitimately show
   *  up more than once (once per report group or rule side), and showing all of them is the point,
   *  not a defect to hide.
   */
  @Override
  public TransactionSearchResponse searchTransactions(TransactionSearchField field, String query) {
    return logOperation("Transaction search",
        () -> LOGGER.debug("Transaction search scope resolved | field={} | queryLength={}", field, query.length()),
        () -> {
          String normalizedQuery = query.trim();
          if (normalizedQuery.isEmpty()) {
            throw new InvalidRequestException("Search query must not be blank");
          }
          List<TransactionSearchResultProjection> searchMatches = transactionSearchRepository.search(field, normalizedQuery);
          return new TransactionSearchResponse(normalizedQuery,
              searchMatches
                .stream()
                .map(match -> new TransactionSearchResultResponse(match.reportGroupId(), match.reportGroupName(), match.countryCode(),
                    match.countryName(), match.batchId(), match.evidenceSource(), match.stage(), match.status(), match.comments(),
                    match.matchedOn(), match.occurredAt(), match.mtcn()))
                .toList());
        }, transactionSearchResponse -> "resultCount=" + transactionSearchResponse.results().size());
  }

  private CountryFilter resolvePeriodCountryFilter(CountryCatalogSnapshot countryCatalogSnapshot, String countryCode, Integer reportGroupId) {
    if (reportGroupId != null || "ALL".equals(countryCode)) {
      return new CountryFilter(false, List.of(-1));
    }
    CountryDefinition countryDefinition = countryCatalogSnapshot
      .findByCode(countryCode)
      .orElseThrow(() -> new InvalidRequestException("Unsupported country filter: " + countryCode));
    return new CountryFilter(true, countryDefinition.reportGroupIds().stream().toList());
  }

  private String normalizeCountryCode(String country) {
    return country == null || country.isBlank() ? "ALL" : country.trim().toUpperCase(Locale.ROOT);
  }

  private record CountryFilter(boolean enabled, List<Integer> reportGroupIds) {}

  private TransactionReportContextResponse toContext(TransactionReportContextProjection reportContext, CountryDefinition countryDefinition) {
    return new TransactionReportContextResponse(reportContext.reportGroupId(), reportContext.reportGroupName(), reportContext.batchId(),
        reportContext.sequenceNumber(), countryDefinition.code(), countryDefinition.name(), reportContext.reportingPeriodFrom(),
        reportContext.reportingPeriodTo());
  }

  private TransactionEvidenceRecordResponse toEvidenceRecord(TransactionEvidenceProjection evidenceProjection) {
    return new TransactionEvidenceRecordResponse(evidenceProjection.recordKey(), evidenceProjection.identifier(), evidenceProjection.mtcn(),
        evidenceProjection.batchId(), TransactionEvidenceSource.valueOf(evidenceProjection.evidenceSource()), evidenceProjection.stage(),
        evidenceProjection.status(), TransactionOutcome.valueOf(evidenceProjection.outcome()), evidenceProjection.comments(),
        evidenceProjection.skipReason(), evidenceProjection.exclusionReason(), evidenceProjection.reportedBatchId(),
        evidenceProjection.modifiedAt(), evidenceProjection.processingComplete());
  }

  /**
   * True for a metric whose value is an absolute delta between two aggregate counts on the
   * reconciliation row (the gap between an expected and an actual count), rather than a status a
   * specific transaction can carry. No condition against JOURNEY/EXCLUSION_AUDIT/RULE_HIT can
   * identify "the transactions that make up this gap" -- the schema simply does not record which
   * ones they are, only how many. Callers must skip the evidence pipeline entirely for these rather
   * than let it run and return an unfiltered, mislabeled batch of unrelated evidence (see the call
   * site in getTransactionReport()).
   *
   * <p>MISSING used to be grouped in here too, on the same "no row represents it" theory -- wrong,
   * confirmed against real data: a transaction that was selected but never attempted <em>does</em>
   * get its own journey row (stage SELECTION/status ATTEMPT_MISSING, or the older stage
   * TRANSACTION_JOIN/status ERROR/comments ATTEMPT_NOT_RECEIVED convention some report groups still
   * use), and its count matches {@code report_transformation_reconciliation.txn_missing_attempt_count}
   * exactly. See {@link BatchEvidenceQueries#metricScoped} for the corresponding query.
   */
  private boolean isAggregateOnlyMetric(TransactionMetric metric) {
    return switch (metric) {
      case FILTRATION_VARIANCE, RECONCILIATION_VARIANCE -> true;
      default -> false;
    };
  }

  private long aggregateCount(TransactionReportContextProjection context, TransactionMetric metric) {
    return switch (metric) {
      case ALL, SELECTED -> context.selectedTransactions();
      case ATTEMPTS_FOUND -> context.attemptsFound();
      case MISSING -> context.missingAttempts();
      case ACTIVITY_MISSING -> context.activityMissing();
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
      // Mirrors the Data Selection card's "Total exclusions" tile exactly: every reason a selected
      // transaction did not carry through.
      case FILTERED -> context.missingAttempts() + context.activityMissing() + context.excluded() + context.simulated()
          + context.alreadyReported() + context.softDedup();
      // Mirrors the Skipped Status card: the three ways a selected transaction never reaches a
      // reportable outcome outside of exclusion -- see BatchEvidenceQueries#metricScoped for the
      // corresponding evidence condition (missingAttemptCondition OR activityMissingCondition OR
      // FAILED's own condition).
      case SKIPPED -> context.missingAttempts() + context.activityMissing() + context.failed();
      case FILTRATION_VARIANCE -> context.filtrationVariance();
      case RECONCILIATION_VARIANCE -> context.reconciliationVariance();
    };
  }

  /**
   * FAILED and SKIPPED are the only metrics whose {@link #aggregateCount} can disagree with the raw
   * {@code report_transformation_reconciliation.activity_transformation_failed} column -- see
   * TransformationFailureQueries' Javadoc. Every other metric's reported and corrected values are
   * identical by construction, so this just re-returns {@link #aggregateCount} for them.
   */
  private long reportedAggregateCount(TransactionReportContextProjection context, TransactionMetric metric) {
    return switch (metric) {
      case FAILED -> context.reportedFailed();
      case SKIPPED -> context.missingAttempts() + context.activityMissing() + context.reportedFailed();
      default -> aggregateCount(context, metric);
    };
  }

  private boolean aggregateCountMismatch(TransactionReportContextProjection context, TransactionMetric metric) {
    return switch (metric) {
      case FAILED, SKIPPED -> context.failedMismatch();
      default -> false;
    };
  }

  private String metricLabel(TransactionMetric metric) {
    return switch (metric) {
      case ALL -> "Available transaction evidence";
      case SELECTED -> "Selected transactions";
      case ATTEMPTS_FOUND -> "Transaction attempts found";
      case MISSING -> "Missing transaction attempts";
      case ACTIVITY_MISSING -> "Activity missing";
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
      case SKIPPED -> "Skipped transactions";
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

  /**
   * RECORD_LEVEL requires an exact match, not just "at least as many records as the aggregate
   * claims" -- the latter silently classified an over-count (more evidence rows than the aggregate)
   * as a full, untroubled match, which is exactly backwards: an aggregate that under-reports
   * relative to its own evidence is just as much a data-quality signal as one that over-reports.
   * Both directions fall into PARTIAL_RECORD_LEVEL and get an explanatory message instead of being
   * silently absorbed into "everything's fine" (confirmed against a real over-count: a filter bug
   * once put 8,552 evidence rows against an aggregate of 8,451, which this would have shown as
   * RECORD_LEVEL with no explanation at all).
   */
  private TransactionEvidenceLevel evidenceLevel(long aggregateCount, long availableRecords) {
    if (aggregateCount == 0 && availableRecords == 0) {
      return TransactionEvidenceLevel.NO_RECORDS;
    }
    if (availableRecords == 0) {
      return TransactionEvidenceLevel.AGGREGATE_ONLY;
    }
    if (aggregateCount > 0 && availableRecords == aggregateCount) {
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
      // Deliberately states both numbers side by side rather than "N available for an aggregate of
      // M" -- that phrasing assumes the evidence list is a subset of the aggregate (N <= M), which
      // breaks if the aggregate itself under-reports relative to real evidence (N > M) -- a filter
      // or aggregate bug produces exactly that shape, and the message needs to read sensibly either
      // way, not just when evidence falls short.
      case PARTIAL_RECORD_LEVEL -> "Transaction evidence shows " + availableRecords + " record(s); the reconciliation aggregate reports "
          + aggregateCount + ". Journey is latest-state evidence, not full event history.";
      case AGGREGATE_ONLY -> "The count of " + aggregateCount
          + " is available only as batch reconciliation evidence; Phase 1 has no authoritative transaction rows for this metric.";
      case NO_RECORDS -> "The batch has no transactions for this metric.";
    };
  }

  /**
   * The on-demand detail request. Resolves by explicit {@link TransactionDetailScope} rather than by
   * any status/metric filter, so the rule-hit enrichment cannot be narrowed by a list filter -- the
   * defect that previously emptied the Rule Hit Details panel on every EXCLUDED drilldown.
   */
  @Override
  public TransactionEvidenceDetailResponse getTransactionDetail(TransactionDetailScope scope, Integer reportGroupId, String batchId,
      String identifier, LocalDate fromDate, LocalDate toDate, String country, TransactionMetric metric, TransactionEvidenceSource source,
      TransactionStage stage, TransactionOutcome outcome, TransactionStatus status, String reason, boolean batchScopedExcluded,
      String batchIdFilter, String recordKey) {
    String normalizedIdentifier = identifier == null ? "" : identifier.trim();
    if (normalizedIdentifier.isEmpty()) {
      throw new InvalidRequestException("identifier is required");
    }
    Optional<TransactionEvidenceProjection> matchingEvidence;
    if (scope == TransactionDetailScope.BATCH) {
      if (reportGroupId == null) {
        throw new InvalidRequestException("reportGroupId is required for scope=BATCH");
      }
      matchingEvidence = transactionEvidenceCache.findBatchEvidenceDetail(reportGroupId, batchId, normalizedIdentifier, metric.name(),
          source.name(), stage.name(), outcome.name(), status.name(), recordKey);
    } else {
      if (fromDate == null || toDate == null) {
        throw new InvalidRequestException("fromDate and toDate are required for scope=PERIOD");
      }
      if (fromDate.isAfter(toDate)) {
        throw new InvalidDateRangeException("fromDate must be on or before toDate");
      }
      // Mirrors the list exactly: a period view scoped to "All report groups" resolves by country
      // alone, so requiring a report group here would strand every row it returns.
      String normalizedCountry = normalizeCountryCode(country);
      CountryFilter countryFilter = resolvePeriodCountryFilter(countryCatalog.getSnapshot(), normalizedCountry, reportGroupId);
      boolean filterByReportGroup = reportGroupId != null;
      matchingEvidence = transactionEvidenceCache.findPeriodEvidenceDetail(fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay(),
          countryFilter.enabled(), countryFilter.reportGroupIds(), filterByReportGroup, filterByReportGroup ? reportGroupId : -1, batchId,
          normalizedIdentifier, outcome.name(), status.name(), reason == null ? "" : reason.trim(), batchScopedExcluded,
          batchIdFilter == null ? "" : batchIdFilter.trim(), recordKey);
    }
    return matchingEvidence
      .map(this::toEvidenceDetail)
      .orElseThrow(() -> new ResourceNotFoundException("No transaction evidence found for the supplied identifier and scope"));
  }

  private TransactionEvidenceDetailResponse toEvidenceDetail(TransactionEvidenceProjection evidenceProjection) {
    return new TransactionEvidenceDetailResponse(evidenceProjection.recordKey(), evidenceProjection.identifier(), evidenceProjection.mtcn(),
        evidenceProjection.batchId(), evidenceProjection.senderName(), evidenceProjection.senderCity(), evidenceProjection.senderCountry(),
        evidenceProjection.senderPhone(), evidenceProjection.senderDateOfBirth(), evidenceProjection.senderIdType(),
        evidenceProjection.senderIdNumber(), evidenceProjection.receiverName(), evidenceProjection.receiverCity(),
        evidenceProjection.receiverCountry(), evidenceProjection.receiverPhone(), evidenceProjection.receiverDateOfBirth(),
        evidenceProjection.receiverIdType(), evidenceProjection.receiverIdNumber(), evidenceProjection.currencyAmount(),
        evidenceProjection.currencyCode(), evidenceProjection.transactionDate(), evidenceProjection.sendDate(),
        evidenceProjection.transactionSide(), evidenceProjection.transactionStatus(), evidenceProjection.transactionSubStatus(),
        evidenceProjection.comments(), evidenceProjection.skipReason(), evidenceProjection.ruleId(), evidenceProjection.exclusionReason(),
        evidenceProjection.exclusionStrategy(), evidenceProjection.reportingTimestamp(), evidenceProjection.txnSource(),
        evidenceProjection.activityType(), evidenceProjection.galacticId(), evidenceProjection.bucketId(), evidenceProjection.attemptId(),
        evidenceProjection.ruleHitsJson());
  }
}
