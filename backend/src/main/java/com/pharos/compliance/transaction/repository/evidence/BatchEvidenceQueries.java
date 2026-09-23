package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ACTIVITY_TYPE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ATTEMPT_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BUCKET_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.COMMENTS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_AMOUNT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_CODE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_AUDIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_STRATEGY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.GALACTIC_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER_BIGINT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.JOURNEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MATCHED_IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MODIFIED_AT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_ERROR;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_PENDING;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_SUCCESS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.PROCESSING_COMPLETE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECORD_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTED_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTING_TIMESTAMP_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RRA_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_HIT_MATCHES;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_HIT_TABLE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SEND_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SKIP_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SORT_TIMESTAMP;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_EXCLUSION_AUDIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_JOURNEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_RULE_HIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STAGE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STAGE_FILTRATION;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STATUS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SIDE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.matchesDigitsOnly;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.journeyOutcome;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.searchScope;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Batch-scoped transaction evidence: one reconciliation batch's Data Selection drilldowns (Batch
 * Explorer's "View transactions" links). Builds the three-source evidence UNION for exactly that
 * batch, scopes it to the requested {@code metric}, and hands off to {@link EvidencePaginator} for
 * pagination and enrichment.
 */
public class BatchEvidenceQueries {
  private final DSLContext dsl;
  private final RuleHitMatcher ruleHitMatcher;
  private final EvidencePaginator paginator;

  public BatchEvidenceQueries(DSLContext dsl, RuleHitMatcher ruleHitMatcher, EvidencePaginator paginator) {
    this.dsl = dsl;
    this.ruleHitMatcher = ruleHitMatcher;
    this.paginator = paginator;
  }

  /**
   * Deliberately independent of {@code metric}: {@code metricScoped}'s own {@code evidenceSource}
   * filter already keeps a RULE_HIT-sourced row out of the merged evidence for every metric except
   * ALL, so this method running (or not) never changes which rows the merge itself produces
   * (ACTUAL_REPORTABLE/TRANSFORMER_OUTPUT used to also keep RULE_HIT rows via a separate,
   * now-removed OR condition -- see the metricScoped's git history if that's ever needed again).
   * But this same match also
   * feeds {@code rollupRuleHits} -- the "Rule Hit Details" enrichment shown per row, independent of
   * that row's own evidence source. An earlier version of this method also skipped based on metric,
   * on the theory that a metric which can't keep a RULE_HIT row has no use for the match at all --
   * true for the merge, but it silently starved that enrichment for every one of those metrics too:
   * viewing the exact same transaction under EXCLUDED or FILTERED showed "no rule hits matched" for
   * a transaction that, viewed under ALL, correctly showed a real match. Scoping to this batch
   * specifically (not just the report group) is what keeps this affordable without the metric
   * check -- see {@link RuleHitMatcher#ruleHitMatches} for why a report-group-wide scope was
   * expensive before that method was rewritten as a join, and {@link PeriodEvidenceQueries} for
   * the equivalent already-metric-independent period-scoped version this mirrors.
   */
  public Table<?> ruleHitMatchesForBatch(int reportGroupId, String batchId, String status) {
    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      // rule_hit evidence's status can only ever be REPORTED/NOT_REPORTED, so any other requested
      // status matches zero rule_hit rows -- skip the identifier-lookup join entirely rather than
      // run it for no reason. Union branch only: the same reasoning is false for the "Rule Hit
      // Details" enrichment, which is why that now has its own source below.
      return dsl
        .select(RULE_HIT_TABLE.fields())
        .select(DSL.cast(null, SQLDataType.CLOB).as(MATCHED_IDENTIFIER))
        .from(RULE_HIT_TABLE)
        .where(DSL.falseCondition())
        .asTable(RULE_HIT_MATCHES);
    }
    return ruleHitBridge(reportGroupId, batchId, DSL.trueCondition());
  }

  /**
   * The enrichment counterpart to {@link #ruleHitMatchesForBatch}, with no status short-circuit --
   * the batch-scoped mirror of {@link PeriodEvidenceQueries#ruleHitMatchesForPeriodEnrichment}, and
   * for the same reason. The metric-keyed version of this defect is described above; the status
   * filter this endpoint also accepts (the frontend sends one on every batch request) reached the
   * enrichment through exactly the same shared table, so filtering the list to e.g. Success or
   * Failed silently emptied every expanded row's Rule Hit Details.
   *
   * <p>Bounded to the page's own transactions -- see {@link PeriodEvidenceQueries#ruleHitMatchesForPeriodEnrichment}
   * for why the unbounded form was worth removing from the per-page path.
   */
  public Table<?> ruleHitMatchesForBatchEnrichment(int reportGroupId, String batchId, Collection<String> pageIdentifiers) {
    return ruleHitBridge(reportGroupId, batchId, JOURNEY.IDENTIFIER.in(pageIdentifiers));
  }

  private Table<?> ruleHitBridge(int reportGroupId, String batchId, Condition journeyRestriction) {
    Table<?> journeyScoped = dsl
      .select(JOURNEY.IDENTIFIER.as(IDENTIFIER), JOURNEY.MTCN.as("mtcn"),
          DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT)).as(IDENTIFIER_BIGINT))
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(reportGroupId))
      .and(JOURNEY.BATCH_ID.eq(batchId))
      .and(journeyRestriction)
      .asTable("journey_scoped");
    // efile_batch_id, not rule_hit's own unrelated integer batch_id column -- the same field the
    // merge's own RULE_HIT branch already uses as that row's evidence_batch_id. Matching an
    // identifier/mtcn alone (as this used to) could surface a rule_hit belonging to a *different*
    // batch that happens to share it -- e.g. a resubmitted transaction -- as if it were this
    // batch's own evidence, which is exactly the mixing this scope prevents; it also bounds the
    // scan to one batch's rule_hit rows instead of the whole report group's.
    return ruleHitMatcher.ruleHitMatches(RULE_HIT_TABLE.RPT_GRP_ID.eq(reportGroupId).and(RULE_HIT_TABLE.EFILE_BATCH_ID.eq(batchId)),
        journeyScoped);
  }

  public Table<?> evidenceForBatch(int reportGroupId, String batchId, Table<?> ruleHitMatches) {
    var journeyBranch = dsl
      .select(DSL.concat(DSL.inline("JOURNEY:"), JOURNEY.IDENTIFIER).as(RECORD_KEY), JOURNEY.IDENTIFIER.as(IDENTIFIER),
          JOURNEY.MTCN.as("mtcn"), JOURNEY.BATCH_ID.as(EVIDENCE_BATCH_ID), DSL.inline(SOURCE_JOURNEY).as(EVIDENCE_SOURCE),
          JOURNEY.STAGE.as(STAGE), JOURNEY.STATUS.as(STATUS), journeyOutcome(JOURNEY.STATUS).as(OUTCOME), JOURNEY.COMMENTS.as(COMMENTS),
          JOURNEY.SKIP_REASON.as(SKIP_REASON), DSL.cast(null, SQLDataType.CLOB).as(RULE_ID_COLUMN),
          DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_REASON), DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_STRATEGY),
          DSL.cast(null, SQLDataType.CLOB).as(REPORTED_BATCH_ID),
          JOURNEY.REPORTING_TIMESTAMP_LATEST.cast(SQLDataType.CLOB).as(REPORTING_TIMESTAMP_COLUMN),
          JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(MODIFIED_AT), JOURNEY.MODIFIED_TIMESTAMP.as(SORT_TIMESTAMP),
          JOURNEY.PROCESSING_COMPLETE.as(PROCESSING_COMPLETE), DSL.cast(null, SQLDataType.DOUBLE).as(CURRENCY_AMOUNT),
          DSL.cast(null, SQLDataType.CLOB).as(CURRENCY_CODE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SIDE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SOURCE),
          DSL.cast(null, SQLDataType.CLOB).as(ACTIVITY_TYPE), DSL.cast(null, SQLDataType.CLOB).as(SEND_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(GALACTIC_ID), DSL.cast(null, SQLDataType.INTEGER).as(BUCKET_ID_COLUMN),
          DSL.cast(null, SQLDataType.BIGINT).as(ATTEMPT_ID_COLUMN),
          DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT)).as(RRA_KEY))
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(reportGroupId))
      .and(JOURNEY.BATCH_ID.eq(batchId));

    var exclusionBranch = dsl
      .select(DSL
            .concat(DSL.inline("EXCLUSION:"), EXCLUSION_AUDIT.BUCKET_ID, DSL.inline(":"), EXCLUSION_AUDIT.RULE_ID, DSL.inline(":"),
                EXCLUSION_AUDIT.ATTEMPT_ID)
            .as(RECORD_KEY),
          DSL.coalesce(EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.cast(SQLDataType.CLOB), EXCLUSION_AUDIT.ATTEMPT_ID.cast(SQLDataType.CLOB)).as(
              IDENTIFIER), EXCLUSION_AUDIT.MTCN.as("mtcn"), EXCLUSION_AUDIT.PROCESSING_BATCH_ID.as(EVIDENCE_BATCH_ID),
          DSL.inline(SOURCE_EXCLUSION_AUDIT).as(EVIDENCE_SOURCE), DSL.inline("EXCLUSION").as(STAGE), DSL.inline(VALUE_EXCLUDED).as(STATUS),
          DSL.inline(VALUE_EXCLUDED).as(OUTCOME), DSL.cast(null, SQLDataType.CLOB).as(COMMENTS),
          DSL.cast(null, SQLDataType.CLOB).as(SKIP_REASON), EXCLUSION_AUDIT.RULE_ID.as(RULE_ID_COLUMN),
          EXCLUSION_AUDIT.EXCLUSION_REASON_ID.as(EXCLUSION_REASON), EXCLUSION_AUDIT.EXCLUSION_STRATEGY.as(EXCLUSION_STRATEGY),
          EXCLUSION_AUDIT.REPORTED_BATCH_ID.as(REPORTED_BATCH_ID),
          EXCLUSION_AUDIT.REPORTING_TIMESTAMP.cast(SQLDataType.CLOB).as(REPORTING_TIMESTAMP_COLUMN),
          EXCLUSION_AUDIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(MODIFIED_AT),
          DSL.field("{0} at time zone 'UTC'", SQLDataType.TIMESTAMPWITHTIMEZONE, EXCLUSION_AUDIT.MODIFIED_TIMESTAMP).as(SORT_TIMESTAMP),
          DSL.inline(true).as(PROCESSING_COMPLETE), DSL.cast(null, SQLDataType.DOUBLE).as(CURRENCY_AMOUNT),
          DSL.cast(null, SQLDataType.CLOB).as(CURRENCY_CODE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SIDE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SOURCE),
          DSL.cast(null, SQLDataType.CLOB).as(ACTIVITY_TYPE), DSL.cast(null, SQLDataType.CLOB).as(SEND_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(GALACTIC_ID), EXCLUSION_AUDIT.BUCKET_ID.as(BUCKET_ID_COLUMN),
          EXCLUSION_AUDIT.ATTEMPT_ID.as(ATTEMPT_ID_COLUMN), EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.as(RRA_KEY))
      .from(EXCLUSION_AUDIT)
      .where(EXCLUSION_AUDIT.RPT_GRP_ID.eq(reportGroupId))
      .and(EXCLUSION_AUDIT.PROCESSING_BATCH_ID.eq(batchId));

    Field<Integer> rhmBucketId = requiredField(ruleHitMatches, BUCKET_ID_COLUMN, Integer.class);
    Field<String> rhmRuleId = requiredField(ruleHitMatches, RULE_ID_COLUMN, String.class);
    Field<Long> rhmAttemptId = requiredField(ruleHitMatches, ATTEMPT_ID_COLUMN, Long.class);
    Field<String> rhmMatchedIdentifier = requiredField(ruleHitMatches, MATCHED_IDENTIFIER, String.class);
    Field<String> rhmMtcn = requiredField(ruleHitMatches, "mtcn", String.class);
    Field<String> rhmEfileBatchId = requiredField(ruleHitMatches, "efile_batch_id", String.class);
    Field<Boolean> rhmIsReported = requiredField(ruleHitMatches, "is_reported", Boolean.class);
    Field<String> rhmExclusionReasonId = requiredField(ruleHitMatches, "exclusion_reason_id", String.class);
    Field<String> rhmReportedBatchId = requiredField(ruleHitMatches, REPORTED_BATCH_ID, String.class);
    Field<LocalDateTime> rhmReportingTimestamp = requiredField(ruleHitMatches, REPORTING_TIMESTAMP_COLUMN, LocalDateTime.class);
    Field<OffsetDateTime> rhmModifiedTimestamp = requiredField(ruleHitMatches, "modified_timestamp", OffsetDateTime.class);
    Field<BigDecimal> rhmCurrencyAmount = requiredField(ruleHitMatches, "rule_currency_amount", BigDecimal.class);
    Field<String> rhmCurrencyCode = requiredField(ruleHitMatches, "rule_iso_currency_code", String.class);
    Field<LocalDateTime> rhmTransactionDate = requiredField(ruleHitMatches, TRANSACTION_DATE, LocalDateTime.class);
    Field<String> rhmTransactionSide = requiredField(ruleHitMatches, TRANSACTION_SIDE, String.class);
    Field<String> rhmSource = requiredField(ruleHitMatches, "source", String.class);
    Field<String> rhmActivityType = requiredField(ruleHitMatches, ACTIVITY_TYPE, String.class);
    Field<LocalDate> rhmSendDate = requiredField(ruleHitMatches, SEND_DATE, LocalDate.class);
    Field<String> rhmGalacticId = requiredField(ruleHitMatches, GALACTIC_ID, String.class);
    Field<Long> rhmExternalTxnKey = requiredField(ruleHitMatches, "external_txn_key", Long.class);

    var ruleHitBranch = dsl
      .select(DSL.concat(DSL.inline("RULE_HIT:"), rhmBucketId, DSL.inline(":"), rhmRuleId, DSL.inline(":"), rhmAttemptId).as(RECORD_KEY),
          rhmMatchedIdentifier.as(IDENTIFIER), rhmMtcn.as("mtcn"), rhmEfileBatchId.as(EVIDENCE_BATCH_ID),
          DSL.inline(SOURCE_RULE_HIT).as(EVIDENCE_SOURCE), DSL.inline(SOURCE_RULE_HIT).as(STAGE),
          DSL.when(rhmIsReported, DSL.inline(VALUE_REPORTED)).otherwise(DSL.inline(VALUE_NOT_REPORTED)).as(STATUS),
          DSL.when(rhmIsReported, DSL.inline(OUTCOME_SUCCESS)).otherwise(DSL.inline(OUTCOME_PENDING)).as(OUTCOME),
          DSL.cast(null, SQLDataType.CLOB).as(COMMENTS), DSL.cast(null, SQLDataType.CLOB).as(SKIP_REASON), rhmRuleId.as(RULE_ID_COLUMN),
          rhmExclusionReasonId.as(EXCLUSION_REASON), DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_STRATEGY),
          rhmReportedBatchId.as(REPORTED_BATCH_ID), rhmReportingTimestamp.cast(SQLDataType.CLOB).as(REPORTING_TIMESTAMP_COLUMN),
          rhmModifiedTimestamp.cast(SQLDataType.CLOB).as(MODIFIED_AT), rhmModifiedTimestamp.as(SORT_TIMESTAMP),
          DSL.inline(true).as(PROCESSING_COMPLETE), rhmCurrencyAmount.cast(SQLDataType.DOUBLE).as(CURRENCY_AMOUNT),
          rhmCurrencyCode.as(CURRENCY_CODE), rhmTransactionDate.cast(SQLDataType.CLOB).as(TRANSACTION_DATE),
          rhmTransactionSide.as(TRANSACTION_SIDE), rhmSource.as(TRANSACTION_SOURCE), rhmActivityType.as(ACTIVITY_TYPE),
          rhmSendDate.cast(SQLDataType.CLOB).as(SEND_DATE), rhmGalacticId.as(GALACTIC_ID), rhmBucketId.as(BUCKET_ID_COLUMN),
          rhmAttemptId.as(ATTEMPT_ID_COLUMN), rhmExternalTxnKey.as(RRA_KEY))
      .from(ruleHitMatches)
      .where(rhmMatchedIdentifier.isNotNull());

    return journeyBranch.unionAll(exclusionBranch).unionAll(ruleHitBranch).asTable("evidence");
  }

  public Table<?> metricScoped(Table<?> evidence, String metric, String source) {
    Field<String> evidenceSource = requiredField(evidence, EVIDENCE_SOURCE, String.class);
    Field<String> stage = requiredField(evidence, STAGE, String.class);
    Field<String> status = requiredField(evidence, STATUS, String.class);
    Field<String> outcome = requiredField(evidence, OUTCOME, String.class);
    Field<String> comments = requiredField(evidence, COMMENTS, String.class);

    Field<String> upperStage = DSL.upper(DSL.coalesce(stage, ""));
    Field<String> upperStatus = DSL.upper(DSL.coalesce(status, ""));
    Field<String> upperComments = DSL.upper(DSL.coalesce(comments, ""));
    // Shared "journey row at stage X" scopes, factored out since several metrics below narrow one
    // of these two stages by a further outcome/comment condition -- reused the same way
    // missingAttemptCondition/failedCondition already were.
    Condition journeyAtFiltration = evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq(STAGE_FILTRATION));
    Condition journeyAtTransformation = evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq("TRANSFORMATION"));
    // Two conventions for "this transaction never got an attempt," each used exclusively by
    // different report groups -- see the MISSING case below, and FILTERED, which needs this same
    // condition since its own aggregate (TransactionReportServiceImpl#aggregateCount) explicitly
    // adds missingAttempts into its total.
    Condition missingAttemptCondition = evidenceSource
      .eq(SOURCE_JOURNEY)
      .and(upperStage
        .eq("SELECTION")
        .and(upperStatus.eq("ATTEMPT_MISSING"))
        .or(upperStage.eq("TRANSACTION_JOIN").and(upperStatus.eq("ERROR")).and(upperComments.eq("ATTEMPT_NOT_RECEIVED"))));
    // A TRANSACTION_JOIN-stage sibling of missingAttemptCondition's own second convention, not a
    // TRANSFORMATION-stage concept despite the "activity" in its name coinciding with the
    // eligible/transformed/failed "activity_*" columns below. Matches the real
    // report-batch-transformer job's actual convention (ReportReconciliationMetricTransformer
    // .reconcileRegActivity, which sets activity_missing = ruleHitTotalCount -
    // activitySelectedCount and terminates those transactions at the join with no
    // TRANSFORMATION-stage row) -- this dashboard's own mock data was originally generated with a
    // different, invented SELECTION-stage convention that didn't match the real system; fixed at
    // the source (database/loaders/generate_load_test_data.py) rather than carried here as a
    // second branch. The comment filter is load-bearing, not optional: a separate writer at that
    // same TRANSACTION_JOIN/ERROR combination (reconcileRegActivityMissing) records a different,
    // later gap -- the shortfall between expected and actual activity eligible for
    // transformation -- and matching on stage+status alone would conflate the two.
    Condition activityMissingCondition = evidenceSource
      .eq(SOURCE_JOURNEY)
      .and(upperStage.eq("TRANSACTION_JOIN"))
      .and(upperStatus.eq("ERROR"))
      .and(upperComments.eq("TXN_DATA_MISSING"));
    // Reused by SKIPPED below, which needs the same condition FAILED matches on its own.
    Condition failedCondition = journeyAtTransformation.and(outcome.eq(OUTCOME_ERROR));
    // This mock data records some successfully-transformed transactions with a
    // REPORT_GENERATION/GENERATED row instead of a TRANSFORMATION/SUCCESS one -- see
    // generate_load_test_data.py, which appends exactly one journey row per transaction and picks
    // REPORT_GENERATION over TRANSFORMATION for roughly a third of successes once a batch's report
    // has actually been generated. Established precedent for treating the two as equivalent
    // already exists in OverviewEvidenceQueries#reportingRoll's everReportedCondition. Reused by
    // both cases below.
    Condition reportGenerationSuccess =
        evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq("REPORT_GENERATION")).and(upperStatus.eq("GENERATED"));
    // "Eligible for transformation" is any transaction that reached the TRANSFORMATION stage at
    // all, whether it succeeded or failed there, plus the REPORT_GENERATION-recorded successes
    // above. Previously scoped identically to SELECTED/ATTEMPTS_FOUND (any journey row at all, no
    // stage restriction), so clicking through from Expected/Actual Eligible showed the exact same
    // list as Selected Data. EXPECTED_ELIGIBLE and ACTUAL_ELIGIBLE share this one condition rather
    // than having their own: the aggregate gap between them (reconciliation_error) isn't tied to
    // specific records -- see RECONCILIATION_VARIANCE, already isAggregateOnlyMetric for exactly
    // that reason -- so there's no finer-grained row-level distinction to draw between the two.
    Condition eligibleForTransformationCondition =
        evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq("TRANSFORMATION")).or(reportGenerationSuccess);
    // Undercounted before reportGenerationSuccess was added here (confirmed against a real batch:
    // only 14 of 22 aggregate-reported transformed transactions had a matching
    // TRANSFORMATION/SUCCESS row -- the other 8 were recorded as REPORT_GENERATION/GENERATED).
    // EXPECTED_REPORTABLE/ACTUAL_REPORTABLE/TRANSFORMER_OUTPUT reuse this same condition rather
    // than having their own: their reconciliation formulas subtract excluded/simulated/
    // already_reported/soft_dedup/filtration_error from the transformed count, but this mock
    // data's own generator assigns each transaction to exactly one terminal category up front
    // (exclusion is decided before transformation is ever attempted), so a transaction that
    // reaches this condition was never also counted as excluded/simulated/etc -- there is no
    // finer-grained row set to subtract from it, the same reasoning already applied to
    // EXPECTED_ELIGIBLE/ACTUAL_ELIGIBLE above. Not currently reachable from any UI tile (none of
    // the three currently route here), fixed anyway to not leave the same "shows Selected Data"
    // bug in place for whenever one is wired up.
    Condition transformedSuccessfullyCondition = journeyAtTransformation.and(outcome.eq(OUTCOME_SUCCESS)).or(reportGenerationSuccess);

    Condition metricCondition = switch (metric) {
      case "ALL" -> DSL.trueCondition();
      case "SELECTED", "ATTEMPTS_FOUND" -> evidenceSource.eq(SOURCE_JOURNEY);
      case "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE" -> eligibleForTransformationCondition;
      case "TRANSFORMED", "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE", "TRANSFORMER_OUTPUT" -> transformedSuccessfullyCondition;
      case "FAILED" -> failedCondition;
      // Previously sourced from EXCLUSION_AUDIT alone, which only has a row for a transaction once
      // something (typically a downstream rule/reporting check) explicitly audits the exclusion --
      // confirmed against real production data that most FILTRATION/EXCLUDED journey rows never
      // get one: for one batch, rule_hit_exclusion_audit had 33 rows while
      // report_transformation_reconciliation.excluded_txn (and a matching count straight off
      // record_transformation_journey) was 2,236, i.e. EXCLUSION_AUDIT was missing 2,203 of the
      // batch's real exclusions -- the "Excluded" drill-through silently showed 33 rows for a tile
      // that said 2,236. Journey is the correct, always-populated source for the "generic" bucket
      // report_transformation_reconciliation.excluded_txn itself represents: matches
      // VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS specifically (confirmed 1:1 against excluded_txn on
      // every batch in a real sample, zero unaccounted), not "every FILTRATION/EXCLUDED row that
      // isn't one of SIMULATED/ALREADY_REPORTED/SOFT_DEDUP below" -- a NOT-LIKE catch-all here
      // silently absorbs any future exclusion-comment convention neither this bucket nor those
      // three know about, over-counting exactly the way the period-scoped equivalent of this
      // condition once did (see PeriodEvidenceQueries#filteredExcludedEvidenceForBatchTotal)
      // before it was narrowed to this same positive match.
      case VALUE_EXCLUDED -> journeyAtFiltration
        .and(outcome.eq(VALUE_EXCLUDED))
        .and(upperComments.eq(VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS));
      case "SIMULATED" -> journeyAtFiltration.and(upperComments.eq("EXCLUDED_BECAUSE_SML"));
      case "ALREADY_REPORTED" -> journeyAtFiltration.and(upperComments.like("EXCLUDED_BECAUSE_ALREADY_REPORTED%"));
      case "SOFT_DEDUP" -> journeyAtFiltration.and(upperComments
        .eq("EXCLUDED_SOFT_DEDUP")
        .or(upperComments.like("EXCLUDED_REAPPEARING_%")));
      // Mirrors its own aggregate exactly (missingAttempts + activityMissing + excluded +
      // simulated + alreadyReported + softDedup): the FILTRATION-stage branch already catches
      // every SML/ALREADY_REPORTED/SOFT_DEDUP/generic-EXCLUDED journey row (all four live at that
      // one stage), so missingAttemptCondition and activityMissingCondition are the only other
      // pieces needed -- exactly the six terms the aggregate sums, no more.
      //
      // Deliberately does NOT include evidenceSource.eq(SOURCE_EXCLUSION_AUDIT): an earlier version
      // did, unconditionally admitting every rule_hit_exclusion_audit row for the batch with no
      // check that the transaction was actually excluded. rule_hit_exclusion_audit is keyed at
      // rule-hit grain (bucket_id, rpt_grp_id, rule_id, attempt_id), so a transaction can pick up an
      // audit row for one rule/attempt while a different attempt still carries it through to
      // reporting -- confirmed against real production data: 13 of a batch's 30 audit rows had a
      // null exclusion_reason_id and were, in fact, ADDED_IN_REPORT, inflating "Total exclusions"
      // past its own aggregate (2,232 evidence rows for an aggregate of 2,219) instead of matching
      // it. The VALUE_EXCLUDED case above already reached the same conclusion for the same reason
      // (see its own Javadoc) -- EXCLUSION_AUDIT is sparse and unreliable as an exclusion signal,
      // journeyAtFiltration is the correct, always-populated one.
      case "FILTERED" -> journeyAtFiltration.or(missingAttemptCondition).or(activityMissingCondition);
      // The Skipped Status card's own total: the three ways a selected transaction never reaches
      // a reportable outcome outside of exclusion -- never attempted, expected activity that was
      // never found, or attempted and failed. Mirrors its aggregate (missingAttempts +
      // activityMissing + failed) exactly.
      case "SKIPPED" -> missingAttemptCondition.or(activityMissingCondition).or(failedCondition);
      // Previously routed around this whole method as an aggregate-only metric on the theory that
      // no journey row represents "this transaction never got an attempt" -- wrong, confirmed
      // against real data: different report groups use one of two conventions for the exact same
      // thing, and each matches report_transformation_reconciliation.txn_missing_attempt_count
      // exactly for the batches using it. Matching both, rather than picking one, is the same
      // multi-variant approach ALREADY_REPORTED takes above for its own two comment spellings.
      case "MISSING" -> missingAttemptCondition;
      case "ACTIVITY_MISSING" -> activityMissingCondition;
      // FILTRATION_VARIANCE/RECONCILIATION_VARIANCE never reach this method -- see
      // TransactionReportServiceImpl.isAggregateOnlyMetric. This default remains a defensive
      // fallback for a metric this switch hasn't been taught yet, not a deliberate route for
      // those two -- "match everything" was never a real condition for them, only an
      // unfiltered dump of the batch's evidence mislabeled as if it answered the metric.
      default -> DSL.trueCondition();
    };

    return dsl
      .select(evidence.fields())
      .from(evidence)
      .where("ALL".equals(source) ? DSL.trueCondition() : evidenceSource.eq(source))
      .and(metricCondition)
      .asTable("metric_scoped");
  }

  public Table<?> filteredEvidenceForBatch(Table<?> evidence, String metric, String search, String source, String stage, String outcome,
      String status) {
    var scoped = metricScoped(evidence, metric, source);
    Field<String> identifier = requiredField(scoped, IDENTIFIER, String.class);
    Field<String> mtcn = requiredField(scoped, "mtcn", String.class);
    Field<String> stageField = requiredField(scoped, STAGE, String.class);
    Field<String> outcomeField = requiredField(scoped, OUTCOME, String.class);
    Field<String> statusField = requiredField(scoped, STATUS, String.class);

    return dsl
      .select(scoped.fields())
      .from(scoped)
      .where("ALL".equals(stage) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(stageField, "")).eq(stage))
      .and(searchScope(search, identifier, mtcn))
      .and("ALL".equals(outcome) ? DSL.trueCondition() : outcomeField.eq(outcome))
      .and("ALL".equals(status) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(statusField, "")).eq(status))
      .asTable("filtered_evidence");
  }

  @SqlQueryPurpose("Load paginated transaction evidence for one batch")
  public EvidencePage findEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status, String sortDirection, int size, long offset, EvidenceCursor cursor, EvidenceProjection projection) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    return paginator.pageEvidence(filtered, ids -> ruleHitMatchesForBatchEnrichment(reportGroupId, batchId, ids), sortDirection, size,
        offset, cursor, projection);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records for one batch")
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    return paginator.countDistinctIdentifiers(filtered);
  }
}
