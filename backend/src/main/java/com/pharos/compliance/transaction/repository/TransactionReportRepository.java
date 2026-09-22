package com.pharos.compliance.transaction.repository;

import static com.pharos.compliance.common.jooq.JooqFields.requiredBoolean;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECONCILIATION;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GROUP_NAME_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import com.pharos.compliance.common.jooq.TransformationFailureQueries;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import org.jooq.Field;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.BatchEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.EvidencePaginator;
import com.pharos.compliance.transaction.repository.evidence.OverviewEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.PeriodEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.RuleHitMatcher;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin facade over the transaction-evidence query pipelines, kept as the single Spring bean and
 * public contract this package exposes (unchanged by the split below -- {@link
 * TransactionEvidenceCache} calls the exact same 6 methods it always has). Every "transaction
 * evidence" row is a merge across three possible sources for the same underlying transaction:
 * JOURNEY (record_transformation_journey), EXCLUSION_AUDIT (rule_hit_exclusion_audit), and
 * RULE_HIT (rule_hit, resolved to its journey identifier via an external_txn_key/mtcn bridge). The
 * actual query construction lives in {@code com.pharos.compliance.transaction.repository.evidence},
 * split by pipeline so each is readable on its own instead of interleaved in one file:
 *
 * <ul>
 *   <li>{@link EvidencePaginator} -- the shared two-pass pagination + 27-column priority merge
 *       engine every pipeline below uses, entirely independent of where the evidence rows came
 *       from.
 *   <li>{@link RuleHitMatcher} -- the shared join-based rule_hit-to-journey-identifier resolution.
 *   <li>{@link BatchEvidenceQueries} -- one reconciliation batch's evidence (Batch Explorer
 *       drilldowns): {@link #findEvidenceRecords}/{@link #countEvidenceRecords}.
 *   <li>{@link PeriodEvidenceQueries} -- every batch in a date range (Transactions Overview
 *       drilldowns) for every status except EXCLUDED/NOT_REPORTED.
 *   <li>{@link OverviewEvidenceQueries} -- EXCLUDED/NOT_REPORTED specifically, answered from a
 *       transaction's outcome across every batch in the caller's window rather than one batch's
 *       evidence rows; {@link
 *       #findPeriodEvidenceRecords}/{@link #countPeriodEvidenceRecords} route to this instead of
 *       {@link PeriodEvidenceQueries} for those two statuses, exactly as before the split.
 * </ul>
 */
@Repository
@Transactional(readOnly = true)
public class TransactionReportRepository {
  private final DSLContext dsl;
  private final BatchEvidenceQueries batchEvidenceQueries;
  private final PeriodEvidenceQueries periodEvidenceQueries;
  private final OverviewEvidenceQueries overviewEvidenceQueries;

  public TransactionReportRepository(DSLContext dsl) {
    this.dsl = dsl;
    EvidencePaginator paginator = new EvidencePaginator(dsl);
    RuleHitMatcher ruleHitMatcher = new RuleHitMatcher(dsl);
    this.batchEvidenceQueries = new BatchEvidenceQueries(dsl, ruleHitMatcher, paginator);
    this.periodEvidenceQueries = new PeriodEvidenceQueries(dsl, ruleHitMatcher, paginator);
    this.overviewEvidenceQueries = new OverviewEvidenceQueries(dsl, paginator, periodEvidenceQueries);
  }

  @SqlQueryPurpose("Load transaction reconciliation context for one batch")
  public Optional<TransactionReportContextProjection> findReportContext(int reportGroupId, String batchId, int sequenceNumber) {
    // See TransformationFailureQueries' Javadoc: activity_transformation_failed can disagree with
    // what record_transformation_journey actually recorded, so "failed" (and SKIPPED, which sums
    // it in) is corrected to the journey-derived count whenever journey has any coverage for this
    // batch, falling back to the raw reconciliation scalar otherwise -- exactly mirroring
    // BatchExplorerRepository#getBatchDetails. The LATERAL join computes both journeyAvailable and
    // the journey-derived count once; both are then plain column references, safe to reuse below.
    var journeyStats = TransformationFailureQueries.journeyStatsLateral(dsl, RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID);
    Field<Boolean> journeyAvailable = requiredField(journeyStats, TransformationFailureQueries.JOURNEY_AVAILABLE_COLUMN, Boolean.class);
    Field<Long> journeyFailed =
        requiredField(journeyStats, TransformationFailureQueries.JOURNEY_TRANSFORMATION_FAILURES_COLUMN, Long.class);
    // Deliberately unaliased raw expression -- reused inside the CASE/comparison below; see
    // BatchExplorerRepository#getBatchDetails for why an already-.as()-aliased field can't be
    // reused a second time within the same SELECT list.
    Field<Long> reportedFailedRaw = DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT);
    Field<Long> failed = DSL.when(journeyAvailable, journeyFailed).otherwise(reportedFailedRaw).as("failed");
    Field<Boolean> failedMismatch =
        DSL.condition(journeyAvailable).and(journeyFailed.ne(reportedFailedRaw)).as("failedMismatch");

    return dsl
      .select(RECONCILIATION.RPT_GRP_ID.as("reportGroupId"), RECONCILIATION.RPT_GRP_NAME.as(REPORT_GROUP_NAME_ALIAS),
          RECONCILIATION.BATCH_ID.as("batchId"), RECONCILIATION.SEQ_NO.as("sequenceNumber"),
          RECONCILIATION.RPT_FROM_DATE.as("reportingPeriodFrom"), RECONCILIATION.RPT_TO_DATE.as("reportingPeriodTo"),
          DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).cast(SQLDataType.BIGINT).as("selectedTransactions"),
          DSL
            .greatest(DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).sub(DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)),
                DSL.inline(0))
            .cast(SQLDataType.BIGINT)
            .as("attemptsFound"), DSL
            .coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)
            .cast(SQLDataType.BIGINT)
            .as("missingAttempts"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).cast(SQLDataType.BIGINT).as("activityMissing"),
          DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as("expectedEligible"),
          DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as("actualEligible"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0).cast(SQLDataType.BIGINT).as("transformed"), failed,
          reportedFailedRaw.as("reportedFailed"), failedMismatch,
          DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("expectedReportable"),
          DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("actualReportable"),
          DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as("excluded"),
          DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as("simulated"),
          DSL.coalesce(RECONCILIATION.ALREADY_REPORTED_COUNT, 0).cast(SQLDataType.BIGINT).as("alreadyReported"),
          DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0).cast(SQLDataType.BIGINT).as("softDedup"),
          DSL
            .abs(DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0).sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
            .cast(SQLDataType.BIGINT)
            .as("filtrationVariance"),
          DSL
            .abs(DSL
              .coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
              .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
            .cast(SQLDataType.BIGINT)
            .as("reconciliationVariance"))
      .from(RECONCILIATION)
      .crossJoin(journeyStats)
      .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
      .and(RECONCILIATION.BATCH_ID.eq(batchId))
      .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
      .fetchOptional(r -> new TransactionReportContextProjection(requiredInt(r, "reportGroupId"),
          r.get(REPORT_GROUP_NAME_ALIAS, String.class), r.get("batchId", String.class), requiredInt(r, "sequenceNumber"),
          r.get("reportingPeriodFrom", String.class), r.get("reportingPeriodTo", String.class), requiredLong(r, "selectedTransactions"),
          requiredLong(r, "attemptsFound"), requiredLong(r, "missingAttempts"), requiredLong(r, "activityMissing"),
          requiredLong(r, "expectedEligible"), requiredLong(r, "actualEligible"), requiredLong(r, "transformed"), requiredLong(r, "failed"),
          requiredLong(r, "reportedFailed"), requiredBoolean(r, "failedMismatch"), requiredLong(r, "expectedReportable"),
          requiredLong(r, "actualReportable"), requiredLong(r, "excluded"),
          requiredLong(r, "simulated"), requiredLong(r, "alreadyReported"), requiredLong(r, "softDedup"),
          requiredLong(r, "filtrationVariance"), requiredLong(r, "reconciliationVariance")));
  }

  @SqlQueryPurpose("Load paginated transaction evidence for one batch")
  public EvidencePage findEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status, String sortDirection, int size, long offset, EvidenceCursor cursor) {
    return batchEvidenceQueries.findEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status, sortDirection,
        size, offset, cursor);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records for one batch")
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    return batchEvidenceQueries.countEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status);
  }

  @SqlQueryPurpose("Summarize transaction evidence across the selected reporting period")
  public PeriodAggregateProjection findPeriodAggregate(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId) {
    return periodEvidenceQueries.findPeriodAggregate(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
  }

  @SqlQueryPurpose("List every distinct batch ID in a period-report scope")
  public List<String> findPeriodBatchIds(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    Table<?> scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, "");
    return periodEvidenceQueries.distinctBatchIds(scope);
  }

  /**
   * Routes EXCLUDED/NOT_REPORTED to {@link OverviewEvidenceQueries} (a transaction's outcome
   * across every batch in this window, matching the Transactions Overview dashboard tile's own
   * "ever excluded"/"ever reported" definition) and every other status to {@link
   * PeriodEvidenceQueries} (this period's batch evidence rows) -- see {@link
   * OverviewEvidenceQueries}'s class Javadoc for why the two are deliberately not shared. {@code
   * batchScopedExcluded} is the one exception: the Report Groups Requiring Attention table's own
   * "Excluded" column is {@code SUM(excluded_txn)} over the exact same batch set this scope
   * already resolves -- a fundamentally different, simpler definition than "ever excluded across
   * every batch in this window" -- so a caller showing evidence for
   * *that* number passes this flag to get {@link PeriodEvidenceQueries
   * #findExcludedEvidenceRecordsForBatchTotal} instead, which actually matches it. Ignored unless
   * status is EXCLUDED; NOT_REPORTED always uses the overview rollup, since it has no batch-scoped
   * KPI to match in the first place.
   */
  @SqlQueryPurpose("Load paginated transaction evidence across the selected reporting period")
  public EvidencePage findPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId, String search, String outcome,
      String status, String reason, boolean batchScopedExcluded, String sortDirection, int size, long offset, EvidenceCursor cursor) {
    Table<?> scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
    if (VALUE_EXCLUDED.equals(status) && batchScopedExcluded) {
      return periodEvidenceQueries.findExcludedEvidenceRecordsForBatchTotal(scope, search, sortDirection, size, offset, cursor);
    }
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.findOverviewEvidenceRecords(scope, status, reason, search, outcome, sortDirection, size, offset, cursor);
    }
    return periodEvidenceQueries.findEvidenceRecords(scope, search, outcome, status, sortDirection, size, offset, cursor);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records across the selected reporting period")
  public long countPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId, String search, String outcome,
      String status, String reason, boolean batchScopedExcluded) {
    Table<?> scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
    if (VALUE_EXCLUDED.equals(status) && batchScopedExcluded) {
      return periodEvidenceQueries.countExcludedEvidenceRecordsForBatchTotal(scope, search);
    }
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.countOverviewEvidenceRecords(scope, status, reason, search, outcome);
    }
    return periodEvidenceQueries.countEvidenceRecords(scope, search, outcome, status);
  }
}
