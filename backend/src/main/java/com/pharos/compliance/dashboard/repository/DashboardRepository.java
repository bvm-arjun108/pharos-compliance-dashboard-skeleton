package com.pharos.compliance.dashboard.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import static com.pharos.compliance.common.jooq.JooqConditions.containsIgnoreCase;
import static com.pharos.compliance.common.jooq.JooqConditions.countDistinctTupleFiltered;
import static com.pharos.compliance.common.jooq.JooqConditions.zonelessTimestampBetween;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class DashboardRepository {
  private static final String ACTIVITY_MISSING_BATCHES_ALIAS = "activityMissingBatches";
  private static final String ACTIVITY_MISSING_BATCHES_COLUMN = "activity_missing_batches";
  private static final String BATCHES_NEEDING_ATTENTION_ALIAS = "batchesNeedingAttention";
  private static final String BATCHES_NEEDING_ATTENTION_COLUMN = "batches_needing_attention";
  private static final String BATCHES_RAN_ALIAS = "batchesRan";
  private static final String BATCHES_RAN_COLUMN = "batches_ran";
  private static final String MISSING_ATTEMPT_BATCHES_ALIAS = "missingAttemptBatches";
  private static final String MISSING_ATTEMPT_BATCHES_COLUMN = "missing_attempt_batches";
  private static final String PERIOD_START_COLUMN = "period_start";
  private static final String SUCCESSFUL_BATCHES_ALIAS = "successfulBatches";
  private static final String TOTAL_EXCLUDED_TRANSACTIONS_ALIAS = "totalExcludedTransactions";
  private static final String TOTAL_EXCLUDED_TRANSACTIONS_COLUMN = "total_excluded_transactions";
  private static final String TOTAL_REPORTED_TRANSACTIONS_ALIAS = "totalReportedTransactions";
  private static final String TOTAL_REPORTED_TRANSACTIONS_COLUMN = "total_reported_transactions";
  private static final String TRANSFORMATION_FAILURE_BATCHES_ALIAS = "transformationFailureBatches";
  private static final String TRANSFORMATION_FAILURE_BATCHES_COLUMN = "transformation_failure_batches";
  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation RECONCILIATION =
      REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  private final DSLContext dsl;

  public DashboardRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Condition reportGroupScope(boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup,
      int reportGroupId, Field<Integer> reportGroupIdField) {
    Condition condition = DSL.trueCondition();
    if (filterByCountry) {
      condition = condition.and(reportGroupIdField.in(reportGroupIds));
    }
    if (filterByReportGroup) {
      condition = condition.and(reportGroupIdField.eq(reportGroupId));
    }
    return condition;
  }

  @SqlQueryPurpose("Load dashboard headline batch, issue, and transaction totals")
  public DashboardCountsProjection getDashboardCounts(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    Condition scope = RECONCILIATION.CREATED_TIMESTAMP
      .ge(fromTimestamp)
      .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
      .and(containsIgnoreCase(RECONCILIATION.BATCH_ID, batchId))
      .and(reportGroupScope(filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, RECONCILIATION.RPT_GRP_ID));

    Table<Record> rtrScope = dsl.select(RECONCILIATION.asterisk()).from(RECONCILIATION).where(scope).asTable("rtr_scope");

    Field<Integer> rptGrpId = requiredField(rtrScope, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> scopedBatchId = requiredField(rtrScope, RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Integer> seqNo = requiredField(rtrScope, RECONCILIATION.SEQ_NO.getName(), Integer.class);
    Field<Integer> transformationFailed = requiredField(rtrScope, RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED.getName(), Integer.class);
    Field<Integer> missingAttempts = requiredField(rtrScope, RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT.getName(), Integer.class);
    Field<Integer> activityMissing = requiredField(rtrScope, RECONCILIATION.ACTIVITY_MISSING.getName(), Integer.class);
    Field<Integer> duplicateTransformation = requiredField(rtrScope, RECONCILIATION.DUPLICATE_TRANSFORMATION.getName(), Integer.class);
    Field<Integer> excludedTxn = requiredField(rtrScope, RECONCILIATION.EXCLUDED_TXN.getName(), Integer.class);
    Field<Integer> txnSimulated = requiredField(rtrScope, RECONCILIATION.TXN_SIMULATED.getName(), Integer.class);
    Field<Integer> softDedup = requiredField(rtrScope, RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT.getName(), Integer.class);
    Field<Integer> actualReportableTxn = requiredField(rtrScope, RECONCILIATION.ACTUAL_REPORTABLE_TXN.getName(), Integer.class);

    Condition transformationFailedGtZero = DSL.coalesce(transformationFailed, 0).gt(0);
    Condition missingAttemptsGtZero = DSL.coalesce(missingAttempts, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(activityMissing, 0).gt(0);
    Condition noPriorIssue =
        DSL
      .coalesce(transformationFailed, 0)
      .eq(0)
      .and(DSL.coalesce(missingAttempts, 0).eq(0))
      .and(DSL.coalesce(activityMissing, 0).eq(0));

    var rtrAggregates = dsl
      .select(countDistinctTupleFiltered(DSL.trueCondition(), rptGrpId, scopedBatchId, seqNo).as(BATCHES_RAN_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero), rptGrpId, scopedBatchId,
              seqNo)
            .as(BATCHES_NEEDING_ATTENTION_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero, rptGrpId, scopedBatchId, seqNo).as(TRANSFORMATION_FAILURE_BATCHES_COLUMN),
          countDistinctTupleFiltered(missingAttemptsGtZero, rptGrpId, scopedBatchId, seqNo).as(MISSING_ATTEMPT_BATCHES_COLUMN),
          countDistinctTupleFiltered(activityMissingGtZero, rptGrpId, scopedBatchId, seqNo).as(ACTIVITY_MISSING_BATCHES_COLUMN),
          countDistinctTupleFiltered(DSL.coalesce(duplicateTransformation, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo)
            .as("duplicate_transaction_batches"),
          countDistinctTupleFiltered(DSL.coalesce(excludedTxn, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo).as(
              "exclusion_batches"),
          countDistinctTupleFiltered(DSL.coalesce(txnSimulated, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo)
            .as("simulated_transaction_batches"),
          countDistinctTupleFiltered(DSL.coalesce(softDedup, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo).as(
              "soft_dedup_batches"),
          DSL.coalesce(DSL.sum(actualReportableTxn), DSL.inline(java.math.BigDecimal.ZERO)).as(TOTAL_REPORTED_TRANSACTIONS_COLUMN),
          DSL.coalesce(DSL.sum(excludedTxn), DSL.inline(java.math.BigDecimal.ZERO)).as(TOTAL_EXCLUDED_TRANSACTIONS_COLUMN))
      .from(rtrScope)
      .asTable("rtr_aggregates");

    var notYetReported = dsl
      .select(countDistinctTupleFiltered(DSL.trueCondition(), JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID).as("batches_not_yet_reported"))
      .from(JOURNEY)
      .where(zonelessTimestampBetween(JOURNEY.CREATED_TIMESTAMP, fromTimestamp, toTimestampExclusive))
      .and(containsIgnoreCase(JOURNEY.BATCH_ID, batchId))
      .and(reportGroupScope(filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, JOURNEY.RPT_GRP_ID))
      .and(DSL.notExists(dsl
        .selectOne()
        .from(RECONCILIATION)
        .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
        .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
      .asTable("not_yet_reported");

    Field<Long> batchesRanA = requiredField(rtrAggregates, BATCHES_RAN_COLUMN, Long.class);
    Field<Long> batchesNeedingAttentionA = requiredField(rtrAggregates, BATCHES_NEEDING_ATTENTION_COLUMN, Long.class);
    Field<Long> transformationFailureBatchesA = requiredField(rtrAggregates, TRANSFORMATION_FAILURE_BATCHES_COLUMN, Long.class);
    Field<Long> missingAttemptBatchesA = requiredField(rtrAggregates, MISSING_ATTEMPT_BATCHES_COLUMN, Long.class);
    Field<Long> activityMissingBatchesA = requiredField(rtrAggregates, ACTIVITY_MISSING_BATCHES_COLUMN, Long.class);
    Field<Long> duplicateTransactionBatchesA = requiredField(rtrAggregates, "duplicate_transaction_batches", Long.class);
    Field<Long> exclusionBatchesA = requiredField(rtrAggregates, "exclusion_batches", Long.class);
    Field<Long> simulatedTransactionBatchesA = requiredField(rtrAggregates, "simulated_transaction_batches", Long.class);
    Field<Long> softDedupBatchesA = requiredField(rtrAggregates, "soft_dedup_batches", Long.class);
    Field<Long> totalReportedA = requiredField(rtrAggregates, TOTAL_REPORTED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> totalExcludedA = requiredField(rtrAggregates, TOTAL_EXCLUDED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> batchesNotYetReportedN = requiredField(notYetReported, "batches_not_yet_reported", Long.class);

    return dsl
      .select(batchesRanA.add(batchesNotYetReportedN).as(BATCHES_RAN_ALIAS), batchesNotYetReportedN.as("batchesNotYetReported"),
          batchesNeedingAttentionA.as(BATCHES_NEEDING_ATTENTION_ALIAS),
          transformationFailureBatchesA.as(TRANSFORMATION_FAILURE_BATCHES_ALIAS), missingAttemptBatchesA.as(MISSING_ATTEMPT_BATCHES_ALIAS),
          activityMissingBatchesA.as(ACTIVITY_MISSING_BATCHES_ALIAS), duplicateTransactionBatchesA.as("duplicateTransactionBatches"),
          exclusionBatchesA.as("exclusionBatches"), simulatedTransactionBatchesA.as("simulatedTransactionBatches"),
          softDedupBatchesA.as("softDedupBatches"), totalReportedA.as(TOTAL_REPORTED_TRANSACTIONS_ALIAS),
          totalExcludedA.as(TOTAL_EXCLUDED_TRANSACTIONS_ALIAS))
      .from(rtrAggregates)
      .crossJoin(notYetReported)
      .fetchOptional(r -> new DashboardCountsProjection(requiredLong(r, BATCHES_RAN_ALIAS), requiredLong(r, "batchesNotYetReported"),
          requiredLong(r, BATCHES_NEEDING_ATTENTION_ALIAS), requiredLong(r, TRANSFORMATION_FAILURE_BATCHES_ALIAS),
          requiredLong(r, MISSING_ATTEMPT_BATCHES_ALIAS), requiredLong(r, ACTIVITY_MISSING_BATCHES_ALIAS),
          requiredLong(r, "duplicateTransactionBatches"), requiredLong(r, "exclusionBatches"),
          requiredLong(r, "simulatedTransactionBatches"), requiredLong(r, "softDedupBatches"),
          requiredLong(r, TOTAL_REPORTED_TRANSACTIONS_ALIAS), requiredLong(r, TOTAL_EXCLUDED_TRANSACTIONS_ALIAS)))
      .orElseThrow(() -> new IllegalStateException("Dashboard count aggregate returned no row"));
  }

  @SqlQueryPurpose("Load report groups requiring attention, ordered by operational priority")
  public List<ReportGroupMetricsProjection> getReportGroupsRequiringAttention(LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive, String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup,
      int reportGroupId) {
    Condition scope = RECONCILIATION.CREATED_TIMESTAMP
      .ge(fromTimestamp)
      .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
      .and(containsIgnoreCase(RECONCILIATION.BATCH_ID, batchId))
      .and(reportGroupScope(filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, RECONCILIATION.RPT_GRP_ID));

    Condition transformationFailedGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).gt(0);
    Condition missingAttemptsGtZero = DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).gt(0);

    var reportGroupMetrics = dsl
      .select(RECONCILIATION.RPT_GRP_ID, DSL.max(RECONCILIATION.RPT_GRP_NAME).as("rpt_grp_name"),
          countDistinctTupleFiltered(DSL.trueCondition(), RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO).as(BATCHES_RAN_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero), RECONCILIATION.BATCH_ID,
              RECONCILIATION.SEQ_NO)
            .as(BATCHES_NEEDING_ATTENTION_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(TRANSFORMATION_FAILURE_BATCHES_COLUMN),
          countDistinctTupleFiltered(missingAttemptsGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO).as(
              MISSING_ATTEMPT_BATCHES_COLUMN),
          countDistinctTupleFiltered(activityMissingGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO).as(
              ACTIVITY_MISSING_BATCHES_COLUMN),
          DSL.coalesce(DSL.sum(RECONCILIATION.ACTUAL_REPORTABLE_TXN), DSL.inline(java.math.BigDecimal.ZERO)).as(
              TOTAL_REPORTED_TRANSACTIONS_COLUMN),
          DSL.coalesce(DSL.sum(RECONCILIATION.EXCLUDED_TXN), DSL.inline(java.math.BigDecimal.ZERO)).as(TOTAL_EXCLUDED_TRANSACTIONS_COLUMN))
      .from(RECONCILIATION)
      .where(scope)
      .groupBy(RECONCILIATION.RPT_GRP_ID)
      .asTable("report_group_metrics");

    Field<Integer> rptGrpId = requiredField(reportGroupMetrics, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> rptGrpName = requiredField(reportGroupMetrics, "rpt_grp_name", String.class);
    Field<Long> batchesRan = requiredField(reportGroupMetrics, BATCHES_RAN_COLUMN, Long.class);
    Field<Long> batchesNeedingAttention = requiredField(reportGroupMetrics, BATCHES_NEEDING_ATTENTION_COLUMN, Long.class);
    Field<Long> transformationFailureBatches = requiredField(reportGroupMetrics, TRANSFORMATION_FAILURE_BATCHES_COLUMN, Long.class);
    Field<Long> missingAttemptBatches = requiredField(reportGroupMetrics, MISSING_ATTEMPT_BATCHES_COLUMN, Long.class);
    Field<Long> activityMissingBatches = requiredField(reportGroupMetrics, ACTIVITY_MISSING_BATCHES_COLUMN, Long.class);
    Field<Long> totalReported = requiredField(reportGroupMetrics, TOTAL_REPORTED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> totalExcluded = requiredField(reportGroupMetrics, TOTAL_EXCLUDED_TRANSACTIONS_COLUMN, Long.class);

    return dsl
      .select(rptGrpId.as("reportGroupId"), rptGrpName.as("reportGroupName"), batchesRan.as(BATCHES_RAN_ALIAS),
          batchesRan.sub(batchesNeedingAttention).as(SUCCESSFUL_BATCHES_ALIAS), batchesNeedingAttention.as(BATCHES_NEEDING_ATTENTION_ALIAS),
          transformationFailureBatches.as(TRANSFORMATION_FAILURE_BATCHES_ALIAS), missingAttemptBatches.as(MISSING_ATTEMPT_BATCHES_ALIAS),
          activityMissingBatches.as(ACTIVITY_MISSING_BATCHES_ALIAS), totalReported.as(TOTAL_REPORTED_TRANSACTIONS_ALIAS),
          totalExcluded.as(TOTAL_EXCLUDED_TRANSACTIONS_ALIAS))
      .from(reportGroupMetrics)
      .where(batchesNeedingAttention.gt(0L))
      .orderBy(batchesNeedingAttention.desc(), transformationFailureBatches.add(missingAttemptBatches).add(activityMissingBatches).desc(),
          rptGrpId)
      .fetch(r -> new ReportGroupMetricsProjection(requiredInt(r, "reportGroupId"), r.get("reportGroupName", String.class),
          requiredLong(r, BATCHES_RAN_ALIAS), requiredLong(r, SUCCESSFUL_BATCHES_ALIAS), requiredLong(r, BATCHES_NEEDING_ATTENTION_ALIAS),
          requiredLong(r, TRANSFORMATION_FAILURE_BATCHES_ALIAS), requiredLong(r, MISSING_ATTEMPT_BATCHES_ALIAS),
          requiredLong(r, ACTIVITY_MISSING_BATCHES_ALIAS), requiredLong(r, TOTAL_REPORTED_TRANSACTIONS_ALIAS),
          requiredLong(r, TOTAL_EXCLUDED_TRANSACTIONS_ALIAS)));
  }

  @SqlQueryPurpose("Load successful and failed batch counts for the adaptive health trend")
  public List<BatchHealthTrendProjection> getBatchHealthTrend(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      LocalDate fromDate, LocalDate toDate, String granularity, String batchId, boolean filterByCountry, List<Integer> reportGroupIds,
      boolean filterByReportGroup, int reportGroupId) {
    // Bucket boundaries and the per-row bucketing expression are both driven entirely by
    // `granularity`, which is already resolved to exactly one of DAILY/WEEKLY/MONTHLY before this
    // method is ever called (TrendGranularity.forPeriod(...)) -- unlike the original SQL text,
    // which had to encode all three branches as a runtime CASE because a native query can't vary
    // its own text, jOOQ builds the query in Java, so only the one branch that actually applies is
    // ever constructed.
    Field<LocalDate> seriesStart;
    Field<String> seriesStep;
    Field<LocalDate> periodStartExpr;
    switch (granularity) {
      case "DAILY" -> {
        seriesStart = DSL.inline(fromDate);
        seriesStep = DSL.inline("1 day");
        periodStartExpr = RECONCILIATION.CREATED_TIMESTAMP.cast(SQLDataType.LOCALDATE);
      }
      case "WEEKLY" -> {
        seriesStart = DSL.inline(fromDate);
        seriesStep = DSL.inline("7 days");
        // fromDate + (((createdDate - fromDate) / 7) * 7): floor the day offset from fromDate down
        // to the nearest whole week, reproducing the original's integer-division bucketing exactly.
        periodStartExpr = DSL.field("{0} + ((({1}::date - {0}) / 7) * 7)", SQLDataType.LOCALDATE, DSL.inline(fromDate),
            RECONCILIATION.CREATED_TIMESTAMP);
      }
      default -> {
        seriesStart = DSL.field("date_trunc('month', {0})", SQLDataType.LOCALDATE, DSL.inline(fromDate));
        seriesStep = DSL.inline("1 month");
        periodStartExpr = DSL.trunc(RECONCILIATION.CREATED_TIMESTAMP, org.jooq.DatePart.MONTH).cast(SQLDataType.LOCALDATE);
      }
    }

    var periods = dsl
      .select(DSL
        .field("generate_series({0}, {1}, {2}::interval)", SQLDataType.LOCALDATE, seriesStart, DSL.inline(toDate), seriesStep)
        .as(PERIOD_START_COLUMN))
      .asTable("periods");
    Field<LocalDate> periodsStart = requiredField(periods, PERIOD_START_COLUMN, LocalDate.class);

    Condition scope = RECONCILIATION.CREATED_TIMESTAMP
      .ge(fromTimestamp)
      .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
      .and(containsIgnoreCase(RECONCILIATION.BATCH_ID, batchId))
      .and(reportGroupScope(filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, RECONCILIATION.RPT_GRP_ID));

    Condition transformationFailedGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).gt(0);
    Condition missingAttemptsGtZero = DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).gt(0);

    var periodMetrics = dsl
      .select(periodStartExpr.as(PERIOD_START_COLUMN),
          countDistinctTupleFiltered(DSL.trueCondition(), RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(BATCHES_RAN_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero),
              RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(BATCHES_NEEDING_ATTENTION_COLUMN),
          countDistinctTupleFiltered(transformationFailedGtZero, RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(TRANSFORMATION_FAILURE_BATCHES_COLUMN),
          countDistinctTupleFiltered(missingAttemptsGtZero, RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(MISSING_ATTEMPT_BATCHES_COLUMN),
          countDistinctTupleFiltered(activityMissingGtZero, RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
            .as(ACTIVITY_MISSING_BATCHES_COLUMN),
          DSL.coalesce(DSL.sum(RECONCILIATION.ACTUAL_REPORTABLE_TXN), DSL.inline(java.math.BigDecimal.ZERO)).as(
              TOTAL_REPORTED_TRANSACTIONS_COLUMN),
          DSL.coalesce(DSL.sum(RECONCILIATION.EXCLUDED_TXN), DSL.inline(java.math.BigDecimal.ZERO)).as(TOTAL_EXCLUDED_TRANSACTIONS_COLUMN))
      .from(RECONCILIATION)
      .where(scope)
      .groupBy(periodStartExpr)
      .asTable("period_metrics");

    Field<Long> batchesRan = requiredField(periodMetrics, BATCHES_RAN_COLUMN, Long.class);
    Field<Long> batchesNeedingAttention = requiredField(periodMetrics, BATCHES_NEEDING_ATTENTION_COLUMN, Long.class);
    Field<Long> transformationFailureBatches = requiredField(periodMetrics, TRANSFORMATION_FAILURE_BATCHES_COLUMN, Long.class);
    Field<Long> missingAttemptBatches = requiredField(periodMetrics, MISSING_ATTEMPT_BATCHES_COLUMN, Long.class);
    Field<Long> activityMissingBatches = requiredField(periodMetrics, ACTIVITY_MISSING_BATCHES_COLUMN, Long.class);
    Field<Long> totalReported = requiredField(periodMetrics, TOTAL_REPORTED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> totalExcluded = requiredField(periodMetrics, TOTAL_EXCLUDED_TRANSACTIONS_COLUMN, Long.class);

    return dsl
      .select(periodsStart.as("periodStart"), DSL.coalesce(batchesRan, 0L).as(BATCHES_RAN_ALIAS),
          DSL.coalesce(batchesRan, 0L).sub(DSL.coalesce(batchesNeedingAttention, 0L)).as(SUCCESSFUL_BATCHES_ALIAS),
          DSL.coalesce(batchesNeedingAttention, 0L).as(BATCHES_NEEDING_ATTENTION_ALIAS),
          DSL.coalesce(transformationFailureBatches, 0L).as(TRANSFORMATION_FAILURE_BATCHES_ALIAS),
          DSL.coalesce(missingAttemptBatches, 0L).as(MISSING_ATTEMPT_BATCHES_ALIAS),
          DSL.coalesce(activityMissingBatches, 0L).as(ACTIVITY_MISSING_BATCHES_ALIAS),
          DSL.coalesce(totalReported, 0L).as(TOTAL_REPORTED_TRANSACTIONS_ALIAS),
          DSL.coalesce(totalExcluded, 0L).as(TOTAL_EXCLUDED_TRANSACTIONS_ALIAS))
      .from(periods)
      .leftJoin(periodMetrics)
      .using(periodsStart)
      .orderBy(periodsStart)
      .fetch(r -> new BatchHealthTrendProjection(r.get("periodStart", LocalDate.class), requiredLong(r, BATCHES_RAN_ALIAS),
          requiredLong(r, SUCCESSFUL_BATCHES_ALIAS), requiredLong(r, BATCHES_NEEDING_ATTENTION_ALIAS),
          requiredLong(r, TRANSFORMATION_FAILURE_BATCHES_ALIAS), requiredLong(r, MISSING_ATTEMPT_BATCHES_ALIAS),
          requiredLong(r, ACTIVITY_MISSING_BATCHES_ALIAS), requiredLong(r, TOTAL_REPORTED_TRANSACTIONS_ALIAS),
          requiredLong(r, TOTAL_EXCLUDED_TRANSACTIONS_ALIAS)));
  }
}
