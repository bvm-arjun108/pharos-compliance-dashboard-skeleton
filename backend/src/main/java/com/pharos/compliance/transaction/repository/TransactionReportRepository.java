package com.pharos.compliance.transaction.repository;

import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECONCILIATION;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GROUP_NAME_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.BatchEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.EvidencePaginator;
import com.pharos.compliance.transaction.repository.evidence.OverviewEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.PeriodEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.RuleHitMatcher;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import java.math.BigDecimal;
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
 *       transaction's whole journey history rather than one batch's evidence rows; {@link
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
          DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as("expectedEligible"),
          DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as("actualEligible"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0).cast(SQLDataType.BIGINT).as("transformed"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as("failed"),
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
      .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
      .and(RECONCILIATION.BATCH_ID.eq(batchId))
      .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
      .fetchOptional(r -> new TransactionReportContextProjection(requiredInt(r, "reportGroupId"),
          r.get(REPORT_GROUP_NAME_ALIAS, String.class), r.get("batchId", String.class), requiredInt(r, "sequenceNumber"),
          r.get("reportingPeriodFrom", String.class), r.get("reportingPeriodTo", String.class), requiredLong(r, "selectedTransactions"),
          requiredLong(r, "attemptsFound"), requiredLong(r, "missingAttempts"), requiredLong(r, "expectedEligible"),
          requiredLong(r, "actualEligible"), requiredLong(r, "transformed"), requiredLong(r, "failed"),
          requiredLong(r, "expectedReportable"), requiredLong(r, "actualReportable"), requiredLong(r, "excluded"),
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
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    return periodEvidenceQueries.findPeriodAggregate(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
  }

  /**
   * Routes EXCLUDED/NOT_REPORTED to {@link OverviewEvidenceQueries} (a transaction's whole journey
   * history, matching the dashboard tile's own definition) and every other status to {@link
   * PeriodEvidenceQueries} (this period's batch evidence rows) -- see {@link
   * OverviewEvidenceQueries}'s class Javadoc for why the two are deliberately not shared.
   */
  @SqlQueryPurpose("Load paginated transaction evidence across the selected reporting period")
  public EvidencePage findPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome, String status,
      String reason, String sortDirection, int size, long offset, EvidenceCursor cursor) {
    Table<?> scope =
        periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
            reportGroupId, "");
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.findOverviewEvidenceRecords(scope, status, reason, search, outcome, sortDirection, size, offset,
          cursor);
    }
    return periodEvidenceQueries.findEvidenceRecords(scope, search, outcome, status, sortDirection, size, offset, cursor);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records across the selected reporting period")
  public long countPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome, String status,
      String reason) {
    Table<?> scope =
        periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
            reportGroupId, "");
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.countOverviewEvidenceRecords(scope, status, reason, search, outcome);
    }
    return periodEvidenceQueries.countEvidenceRecords(scope, search, outcome, status);
  }
}
