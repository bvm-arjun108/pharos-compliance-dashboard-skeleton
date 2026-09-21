package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqConditions.containsIgnoreCase;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ACTIVITY_TYPE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ATTEMPT_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BATCH_ID_COLUMN;
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
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_PENDING;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_SUCCESS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.PROCESSING_COMPLETE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECONCILIATION;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECORD_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTED_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GROUP_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GROUP_NAME_ALIAS;
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
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STATUS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SIDE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.journeyOutcome;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.matchesDigitsOnly;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.searchScope;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Period-scoped transaction evidence: Transactions Overview drilldowns spanning every batch in a
 * reporting-period date range, for every status except EXCLUDED/NOT_REPORTED (those two are
 * answered from an entirely different "ever reported across full history" definition -- see
 * {@link OverviewEvidenceQueries}, and {@link com.pharos.compliance.transaction.repository.TransactionReportRepository}
 * for the routing between the two). Structurally mirrors {@link BatchEvidenceQueries} one level up
 * the grain: scope is a set of batches instead of one, but the evidence-branch/filter/paginate
 * shape is the same.
 */
public class PeriodEvidenceQueries {
  private final DSLContext dsl;
  private final RuleHitMatcher ruleHitMatcher;
  private final EvidencePaginator paginator;

  public PeriodEvidenceQueries(DSLContext dsl, RuleHitMatcher ruleHitMatcher, EvidencePaginator paginator) {
    this.dsl = dsl;
    this.ruleHitMatcher = ruleHitMatcher;
    this.paginator = paginator;
  }

  public Table<?> batchScope(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId) {
    return dsl
      .select(RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.RPT_GRP_NAME, RECONCILIATION.EXCLUDED_TXN)
      .from(RECONCILIATION)
      .where(RECONCILIATION.CREATED_TIMESTAMP.ge(fromTimestamp))
      .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
      .and(filterByCountry ? RECONCILIATION.RPT_GRP_ID.in(reportGroupIds) : DSL.trueCondition())
      .and(filterByReportGroup ? RECONCILIATION.RPT_GRP_ID.eq(reportGroupId) : DSL.trueCondition())
      .and(containsIgnoreCase(RECONCILIATION.BATCH_ID, batchId))
      .asTable("batch_scope");
  }

  @SqlQueryPurpose("Summarize transaction evidence across the selected reporting period")
  public PeriodAggregateProjection findPeriodAggregate(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    var scope = batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    Field<Integer> bsRptGrpId = requiredField(scope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(scope, BATCH_ID_COLUMN, String.class);
    Field<String> bsRptGrpName = requiredField(scope, "rpt_grp_name", String.class);
    Field<Integer> bsExcludedTxn = requiredField(scope, "excluded_txn", Integer.class);

    return dsl
      .select(DSL.countDistinct(DSL.row(bsRptGrpId, bsBatchId)).as("batchCount"),
          DSL.coalesce(DSL.sum(bsExcludedTxn), DSL.inline(BigDecimal.ZERO)).as("totalExcluded"),
          DSL.max(bsRptGrpName).as(REPORT_GROUP_NAME_ALIAS))
      .from(scope)
      .fetchOptional(r -> new PeriodAggregateProjection(requiredLong(r, "batchCount"), requiredLong(r, "totalExcluded"),
          r.get(REPORT_GROUP_NAME_ALIAS, String.class)))
      .orElseThrow(() -> new IllegalStateException("Period aggregate returned no row"));
  }

  /**
   * Unlike the batch-scoped version, NOT_REPORTED is deliberately excluded from this
   * short-circuit exemption: that status no longer reads rule_hit.is_reported at all (see
   * {@link OverviewEvidenceQueries#reportingTarget} -- it's answered from the journey-history
   * ever_reported/ever_excluded roll-up instead), so running these correlated identifier-matching
   * subqueries for it would only add cost without affecting the result -- and could double-count a
   * transaction whose journey row and rule_hit row disagree on evidence_batch_id vs efile_batch_id.
   */
  public Table<?> ruleHitMatchesForPeriod(Table<?> batchScope, String status) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);
    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status))) {
      return dsl
        .select(RULE_HIT_TABLE.fields())
        .select(DSL.cast(null, SQLDataType.CLOB).as(MATCHED_IDENTIFIER))
        .from(RULE_HIT_TABLE)
        .where(DSL.falseCondition())
        .asTable(RULE_HIT_MATCHES);
    }

    Table<?> journeyScoped = dsl
      .select(JOURNEY.IDENTIFIER.as(IDENTIFIER), JOURNEY.MTCN.as("mtcn"),
          DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT)).as(IDENTIFIER_BIGINT))
      .from(JOURNEY)
      .join(batchScope)
      .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(bsBatchId.eq(JOURNEY.BATCH_ID))
      .asTable("journey_scoped");

    return ruleHitMatcher.ruleHitMatches(RULE_HIT_TABLE.RPT_GRP_ID.in(dsl.selectDistinct(bsRptGrpId).from(batchScope)), journeyScoped);
  }

  public Table<?> evidenceForPeriod(Table<?> batchScope, Table<?> ruleHitMatches) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);

    var journeyBranch = dsl
      .select(DSL
            .concat(DSL.inline("JOURNEY:"), JOURNEY.RPT_GRP_ID, DSL.inline(":"), JOURNEY.BATCH_ID, DSL.inline(":"), JOURNEY.IDENTIFIER)
            .as(RECORD_KEY), JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID_COLUMN), JOURNEY.IDENTIFIER.as(IDENTIFIER), JOURNEY.MTCN.as("mtcn"),
          JOURNEY.BATCH_ID.as(EVIDENCE_BATCH_ID), DSL.inline(SOURCE_JOURNEY).as(EVIDENCE_SOURCE), JOURNEY.STAGE.as(STAGE),
          JOURNEY.STATUS.as(STATUS), journeyOutcome(JOURNEY.STATUS).as(OUTCOME), JOURNEY.COMMENTS.as(COMMENTS),
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
      .join(batchScope)
      .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(bsBatchId.eq(JOURNEY.BATCH_ID));

    var exclusionBranch = dsl
      .select(DSL
            .concat(DSL.inline("EXCLUSION:"), EXCLUSION_AUDIT.BUCKET_ID, DSL.inline(":"), EXCLUSION_AUDIT.RULE_ID, DSL.inline(":"),
                EXCLUSION_AUDIT.ATTEMPT_ID)
            .as(RECORD_KEY), EXCLUSION_AUDIT.RPT_GRP_ID.as(REPORT_GROUP_ID_COLUMN),
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
      .join(batchScope)
      .on(bsRptGrpId.eq(EXCLUSION_AUDIT.RPT_GRP_ID))
      .and(bsBatchId.eq(EXCLUSION_AUDIT.PROCESSING_BATCH_ID));

    Field<Integer> rhmRptGrpId = requiredField(ruleHitMatches, REPORT_GROUP_ID_COLUMN, Integer.class);
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
          rhmRptGrpId.as(REPORT_GROUP_ID_COLUMN), rhmMatchedIdentifier.as(IDENTIFIER), rhmMtcn.as("mtcn"),
          rhmEfileBatchId.as(EVIDENCE_BATCH_ID), DSL.inline(SOURCE_RULE_HIT).as(EVIDENCE_SOURCE), DSL.inline(SOURCE_RULE_HIT).as(STAGE),
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

  public Table<?> filteredEvidenceForPeriod(Table<?> evidence, String search, String outcome, String status) {
    Field<String> identifier = requiredField(evidence, IDENTIFIER, String.class);
    Field<String> mtcn = requiredField(evidence, "mtcn", String.class);
    Field<String> outcomeField = requiredField(evidence, OUTCOME, String.class);
    Field<String> statusField = requiredField(evidence, STATUS, String.class);

    return dsl
      .select(evidence.fields())
      .from(evidence)
      .where(searchScope(search, identifier, mtcn))
      .and("ALL".equals(outcome) ? DSL.trueCondition() : outcomeField.eq(outcome))
      .and("ALL".equals(status) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(statusField, "")).eq(status))
      .asTable("filtered_evidence");
  }

  /**
   * The non-overview period path: every status except EXCLUDED/NOT_REPORTED, which {@link
   * com.pharos.compliance.transaction.repository.TransactionReportRepository} routes to {@link
   * OverviewEvidenceQueries} instead.
   */
  public EvidencePage findEvidenceRecords(Table<?> scope, String search, String outcome, String status, String sortDirection, int size,
      long offset, EvidenceCursor cursor) {
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    return paginator.pageEvidence(filtered, ruleHitMatches, sortDirection, size, offset, cursor);
  }

  public long countEvidenceRecords(Table<?> scope, String search, String outcome, String status) {
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    return paginator.countDistinctIdentifiers(filtered);
  }

  /**
   * Journey-only, scoped to exactly the batches {@code scope} covers -- the simple, direct
   * definition that actually matches how a "total excluded transactions" KPI computed as {@code
   * SUM(report_transformation_reconciliation.excluded_txn)} over the same batch set is itself
   * defined, deliberately independent of {@link OverviewEvidenceQueries}'s "ever excluded across a
   * transaction's whole history" rollup (that one matches a *different* KPI -- Transactions
   * Overview's own Excluded tile, which really is an all-time, identity-deduplicated concept).
   * Matches every FILTRATION/EXCLUDED journey row regardless of its comment -- including
   * EXCLUDED_BECAUSE_SML (simulated) -- since excluded_txn itself doesn't carve simulated out into
   * a separate scalar the way txn_simulated's own bucket does downstream; excluding SML here would
   * silently undercount relative to the sum being explained.
   */
  public Table<?> filteredExcludedEvidenceForBatchTotal(Table<?> evidence, String search) {
    Field<String> evidenceSource = requiredField(evidence, EVIDENCE_SOURCE, String.class);
    Field<String> stage = requiredField(evidence, STAGE, String.class);
    Field<String> outcome = requiredField(evidence, OUTCOME, String.class);
    Field<String> identifier = requiredField(evidence, IDENTIFIER, String.class);
    Field<String> mtcn = requiredField(evidence, "mtcn", String.class);

    return dsl
      .select(evidence.fields())
      .from(evidence)
      .where(searchScope(search, identifier, mtcn))
      .and(evidenceSource.eq(SOURCE_JOURNEY))
      .and(DSL.upper(DSL.coalesce(stage, "")).eq("FILTRATION"))
      .and(outcome.eq(VALUE_EXCLUDED))
      .asTable("filtered_excluded_evidence");
  }

  /** Backs the "Excluded" total on the Report Groups Requiring Attention table -- see {@link
   *  #filteredExcludedEvidenceForBatchTotal}. */
  public EvidencePage findExcludedEvidenceRecordsForBatchTotal(Table<?> scope, String search, String sortDirection, int size, long offset,
      EvidenceCursor cursor) {
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, VALUE_EXCLUDED);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredExcludedEvidenceForBatchTotal(evidence, search);
    return paginator.pageEvidence(filtered, ruleHitMatches, sortDirection, size, offset, cursor);
  }

  public long countExcludedEvidenceRecordsForBatchTotal(Table<?> scope, String search) {
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, VALUE_EXCLUDED);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredExcludedEvidenceForBatchTotal(evidence, search);
    return paginator.countDistinctIdentifiers(filtered);
  }
}
