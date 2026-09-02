package com.pharos.compliance.dashboard.repository;

import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation
      RECONCILIATION = REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY =
      RECORD_TRANSFORMATION_JOURNEY;

  private final DSLContext dsl;

  public DashboardRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * {@code record_transformation_journey.created_timestamp} is {@code timestamptz}, while the
   * {@code fromTimestamp}/{@code toTimestampExclusive} bounds are plain {@link LocalDateTime}
   * values built from a request date with no zone attached. The original native query bound that
   * {@link LocalDateTime} directly against the {@code timestamptz} column and let Postgres apply
   * its own implicit widening (using the session's configured timezone) -- exactly reproduced here
   * as a raw comparison template rather than a typed jOOQ comparison, so neither side is converted
   * client-side in a way that could pick a different timezone than Postgres would have.
   */
  private static Condition journeyCreatedBetween(
      LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive) {
    return DSL.condition(
        "{0} >= {1} and {0} < {2}",
        JOURNEY.CREATED_TIMESTAMP, DSL.val(fromTimestamp), DSL.val(toTimestampExclusive));
  }

  /**
   * {@code COUNT(DISTINCT (col1, col2, ...)) FILTER (WHERE condition)} -- a row-value-expression
   * distinct count jOOQ has no fluent builder for (its {@code countDistinct} overloads only accept
   * single fields or varargs of independently-counted fields, not one combined tuple). Centralized
   * here since this exact shape repeats many times across all three queries in this class.
   */
  private static Field<Long> countDistinctTupleFiltered(Condition filter, Field<?>... columns) {
    String placeholders =
        IntStream.range(0, columns.length).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(", "));
    Object[] args = new Object[columns.length + 1];
    System.arraycopy(columns, 0, args, 0, columns.length);
    args[columns.length] = filter;
    return DSL.field(
        "count(distinct (" + placeholders + ")) filter (where {" + columns.length + "})",
        SQLDataType.BIGINT,
        args);
  }

  private static Condition reportGroupScope(
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      Field<Integer> reportGroupIdField) {
    Condition condition = DSL.trueCondition();
    if (filterByCountry) {
      condition = condition.and(reportGroupIdField.in(reportGroupIds));
    }
    if (filterByReportGroup) {
      condition = condition.and(reportGroupIdField.eq(reportGroupId));
    }
    return condition;
  }

  private static Condition batchIdScope(String batchId, Field<String> batchIdField) {
    return batchId.isEmpty()
        ? DSL.trueCondition()
        : DSL.lower(batchIdField).like(DSL.lower(DSL.inline("%" + batchId + "%")));
  }

  public DashboardCountsProjection getDashboardCounts(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId) {
    Condition scope =
        RECONCILIATION
            .CREATED_TIMESTAMP
            .ge(fromTimestamp)
            .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
            .and(batchIdScope(batchId, RECONCILIATION.BATCH_ID))
            .and(
                reportGroupScope(
                    filterByCountry,
                    reportGroupIds,
                    filterByReportGroup,
                    reportGroupId,
                    RECONCILIATION.RPT_GRP_ID));

    Table<Record> rtrScope = dsl.select(RECONCILIATION.asterisk()).from(RECONCILIATION).where(scope).asTable("rtr_scope");

    Field<Integer> rptGrpId = rtrScope.field(RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> scopedBatchId = rtrScope.field(RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Integer> seqNo = rtrScope.field(RECONCILIATION.SEQ_NO.getName(), Integer.class);
    Field<Integer> transformationFailed =
        rtrScope.field(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED.getName(), Integer.class);
    Field<Integer> missingAttempts =
        rtrScope.field(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT.getName(), Integer.class);
    Field<Integer> activityMissing =
        rtrScope.field(RECONCILIATION.ACTIVITY_MISSING.getName(), Integer.class);
    Field<Integer> duplicateTransformation =
        rtrScope.field(RECONCILIATION.DUPLICATE_TRANSFORMATION.getName(), Integer.class);
    Field<Integer> excludedTxn = rtrScope.field(RECONCILIATION.EXCLUDED_TXN.getName(), Integer.class);
    Field<Integer> txnSimulated = rtrScope.field(RECONCILIATION.TXN_SIMULATED.getName(), Integer.class);
    Field<Integer> softDedup =
        rtrScope.field(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT.getName(), Integer.class);
    Field<Integer> actualReportableTxn =
        rtrScope.field(RECONCILIATION.ACTUAL_REPORTABLE_TXN.getName(), Integer.class);

    Condition transformationFailedGtZero = DSL.coalesce(transformationFailed, 0).gt(0);
    Condition missingAttemptsGtZero = DSL.coalesce(missingAttempts, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(activityMissing, 0).gt(0);
    Condition noPriorIssue =
        DSL.coalesce(transformationFailed, 0)
            .eq(0)
            .and(DSL.coalesce(missingAttempts, 0).eq(0))
            .and(DSL.coalesce(activityMissing, 0).eq(0));

    var rtrAggregates =
        dsl.select(
                countDistinctTupleFiltered(DSL.trueCondition(), rptGrpId, scopedBatchId, seqNo)
                    .as("batches_ran"),
                countDistinctTupleFiltered(
                        transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero),
                        rptGrpId,
                        scopedBatchId,
                        seqNo)
                    .as("batches_needing_attention"),
                countDistinctTupleFiltered(transformationFailedGtZero, rptGrpId, scopedBatchId, seqNo)
                    .as("transformation_failure_batches"),
                countDistinctTupleFiltered(missingAttemptsGtZero, rptGrpId, scopedBatchId, seqNo)
                    .as("missing_attempt_batches"),
                countDistinctTupleFiltered(activityMissingGtZero, rptGrpId, scopedBatchId, seqNo)
                    .as("activity_missing_batches"),
                countDistinctTupleFiltered(
                        DSL.coalesce(duplicateTransformation, 0).gt(0).and(noPriorIssue),
                        rptGrpId,
                        scopedBatchId,
                        seqNo)
                    .as("duplicate_transaction_batches"),
                countDistinctTupleFiltered(
                        DSL.coalesce(excludedTxn, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo)
                    .as("exclusion_batches"),
                countDistinctTupleFiltered(
                        DSL.coalesce(txnSimulated, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo)
                    .as("simulated_transaction_batches"),
                countDistinctTupleFiltered(
                        DSL.coalesce(softDedup, 0).gt(0).and(noPriorIssue), rptGrpId, scopedBatchId, seqNo)
                    .as("soft_dedup_batches"),
                DSL.coalesce(DSL.sum(actualReportableTxn), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_reported_transactions"),
                DSL.coalesce(DSL.sum(excludedTxn), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_excluded_transactions"))
            .from(rtrScope)
            .asTable("rtr_aggregates");

    var notYetReported =
        dsl.select(
                countDistinctTupleFiltered(DSL.trueCondition(), JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
                    .as("batches_not_yet_reported"))
            .from(JOURNEY)
            .where(journeyCreatedBetween(fromTimestamp, toTimestampExclusive))
            .and(batchIdScope(batchId, JOURNEY.BATCH_ID))
            .and(
                reportGroupScope(
                    filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, JOURNEY.RPT_GRP_ID))
            .and(
                DSL.notExists(
                    dsl.selectOne()
                        .from(RECONCILIATION)
                        .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                        .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
            .asTable("not_yet_reported");

    Field<Long> batchesRanA = rtrAggregates.field("batches_ran", Long.class);
    Field<Long> batchesNeedingAttentionA = rtrAggregates.field("batches_needing_attention", Long.class);
    Field<Long> transformationFailureBatchesA =
        rtrAggregates.field("transformation_failure_batches", Long.class);
    Field<Long> missingAttemptBatchesA = rtrAggregates.field("missing_attempt_batches", Long.class);
    Field<Long> activityMissingBatchesA = rtrAggregates.field("activity_missing_batches", Long.class);
    Field<Long> duplicateTransactionBatchesA =
        rtrAggregates.field("duplicate_transaction_batches", Long.class);
    Field<Long> exclusionBatchesA = rtrAggregates.field("exclusion_batches", Long.class);
    Field<Long> simulatedTransactionBatchesA =
        rtrAggregates.field("simulated_transaction_batches", Long.class);
    Field<Long> softDedupBatchesA = rtrAggregates.field("soft_dedup_batches", Long.class);
    Field<Long> totalReportedA = rtrAggregates.field("total_reported_transactions", Long.class);
    Field<Long> totalExcludedA = rtrAggregates.field("total_excluded_transactions", Long.class);
    Field<Long> batchesNotYetReportedN =
        notYetReported.field("batches_not_yet_reported", Long.class);

    return dsl.select(
            batchesRanA.add(batchesNotYetReportedN).as("batchesRan"),
            batchesNotYetReportedN.as("batchesNotYetReported"),
            batchesNeedingAttentionA.as("batchesNeedingAttention"),
            transformationFailureBatchesA.as("transformationFailureBatches"),
            missingAttemptBatchesA.as("missingAttemptBatches"),
            activityMissingBatchesA.as("activityMissingBatches"),
            duplicateTransactionBatchesA.as("duplicateTransactionBatches"),
            exclusionBatchesA.as("exclusionBatches"),
            simulatedTransactionBatchesA.as("simulatedTransactionBatches"),
            softDedupBatchesA.as("softDedupBatches"),
            totalReportedA.as("totalReportedTransactions"),
            totalExcludedA.as("totalExcludedTransactions"))
        .from(rtrAggregates)
        .crossJoin(notYetReported)
        .fetchOne(
            r ->
                new DashboardCountsProjectionImpl(
                    r.get("batchesRan", long.class),
                    r.get("batchesNotYetReported", long.class),
                    r.get("batchesNeedingAttention", long.class),
                    r.get("transformationFailureBatches", long.class),
                    r.get("missingAttemptBatches", long.class),
                    r.get("activityMissingBatches", long.class),
                    r.get("duplicateTransactionBatches", long.class),
                    r.get("exclusionBatches", long.class),
                    r.get("simulatedTransactionBatches", long.class),
                    r.get("softDedupBatches", long.class),
                    r.get("totalReportedTransactions", long.class),
                    r.get("totalExcludedTransactions", long.class)));
  }

  public List<ReportGroupMetricsProjection> getReportGroupsRequiringAttention(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId) {
    Condition scope =
        RECONCILIATION
            .CREATED_TIMESTAMP
            .ge(fromTimestamp)
            .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
            .and(batchIdScope(batchId, RECONCILIATION.BATCH_ID))
            .and(
                reportGroupScope(
                    filterByCountry,
                    reportGroupIds,
                    filterByReportGroup,
                    reportGroupId,
                    RECONCILIATION.RPT_GRP_ID));

    Condition transformationFailedGtZero =
        DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).gt(0);
    Condition missingAttemptsGtZero =
        DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).gt(0);

    var reportGroupMetrics =
        dsl.select(
                RECONCILIATION.RPT_GRP_ID,
                DSL.max(RECONCILIATION.RPT_GRP_NAME).as("rpt_grp_name"),
                countDistinctTupleFiltered(
                        DSL.trueCondition(), RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
                    .as("batches_ran"),
                countDistinctTupleFiltered(
                        transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero),
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("batches_needing_attention"),
                countDistinctTupleFiltered(
                        transformationFailedGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
                    .as("transformation_failure_batches"),
                countDistinctTupleFiltered(
                        missingAttemptsGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
                    .as("missing_attempt_batches"),
                countDistinctTupleFiltered(
                        activityMissingGtZero, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO)
                    .as("activity_missing_batches"),
                DSL.coalesce(
                        DSL.sum(RECONCILIATION.ACTUAL_REPORTABLE_TXN), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_reported_transactions"),
                DSL.coalesce(DSL.sum(RECONCILIATION.EXCLUDED_TXN), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_excluded_transactions"))
            .from(RECONCILIATION)
            .where(scope)
            .groupBy(RECONCILIATION.RPT_GRP_ID)
            .asTable("report_group_metrics");

    Field<Integer> rptGrpId = reportGroupMetrics.field(RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> rptGrpName = reportGroupMetrics.field("rpt_grp_name", String.class);
    Field<Long> batchesRan = reportGroupMetrics.field("batches_ran", Long.class);
    Field<Long> batchesNeedingAttention =
        reportGroupMetrics.field("batches_needing_attention", Long.class);
    Field<Long> transformationFailureBatches =
        reportGroupMetrics.field("transformation_failure_batches", Long.class);
    Field<Long> missingAttemptBatches = reportGroupMetrics.field("missing_attempt_batches", Long.class);
    Field<Long> activityMissingBatches =
        reportGroupMetrics.field("activity_missing_batches", Long.class);
    Field<Long> totalReported = reportGroupMetrics.field("total_reported_transactions", Long.class);
    Field<Long> totalExcluded = reportGroupMetrics.field("total_excluded_transactions", Long.class);

    return dsl.select(
            rptGrpId.as("reportGroupId"),
            rptGrpName.as("reportGroupName"),
            batchesRan.as("batchesRan"),
            batchesRan.sub(batchesNeedingAttention).as("successfulBatches"),
            batchesNeedingAttention.as("batchesNeedingAttention"),
            transformationFailureBatches.as("transformationFailureBatches"),
            missingAttemptBatches.as("missingAttemptBatches"),
            activityMissingBatches.as("activityMissingBatches"),
            totalReported.as("totalReportedTransactions"),
            totalExcluded.as("totalExcludedTransactions"))
        .from(reportGroupMetrics)
        .where(batchesNeedingAttention.gt(0L))
        .orderBy(
            batchesNeedingAttention.desc(),
            transformationFailureBatches.add(missingAttemptBatches).add(activityMissingBatches).desc(),
            rptGrpId)
        .fetch(
            r ->
                new ReportGroupMetricsProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("batchesRan", long.class),
                    r.get("successfulBatches", long.class),
                    r.get("batchesNeedingAttention", long.class),
                    r.get("transformationFailureBatches", long.class),
                    r.get("missingAttemptBatches", long.class),
                    r.get("activityMissingBatches", long.class),
                    r.get("totalReportedTransactions", long.class),
                    r.get("totalExcludedTransactions", long.class)));
  }

  public List<BatchHealthTrendProjection> getBatchHealthTrend(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      LocalDate fromDate,
      LocalDate toDate,
      String granularity,
      String batchId,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId) {
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
        periodStartExpr =
            DSL.field(
                "{0} + ((({1}::date - {0}) / 7) * 7)",
                SQLDataType.LOCALDATE, DSL.inline(fromDate), RECONCILIATION.CREATED_TIMESTAMP);
      }
      default -> {
        seriesStart =
            DSL.field("date_trunc('month', {0})", SQLDataType.LOCALDATE, DSL.inline(fromDate));
        seriesStep = DSL.inline("1 month");
        periodStartExpr =
            DSL.trunc(RECONCILIATION.CREATED_TIMESTAMP, org.jooq.DatePart.MONTH)
                .cast(SQLDataType.LOCALDATE);
      }
    }

    var periods =
        dsl.select(
                DSL.field(
                        "generate_series({0}, {1}, {2}::interval)",
                        SQLDataType.LOCALDATE, seriesStart, DSL.inline(toDate), seriesStep)
                    .as("period_start"))
            .asTable("periods");
    Field<LocalDate> periodsStart = periods.field("period_start", LocalDate.class);

    Condition scope =
        RECONCILIATION
            .CREATED_TIMESTAMP
            .ge(fromTimestamp)
            .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
            .and(batchIdScope(batchId, RECONCILIATION.BATCH_ID))
            .and(
                reportGroupScope(
                    filterByCountry,
                    reportGroupIds,
                    filterByReportGroup,
                    reportGroupId,
                    RECONCILIATION.RPT_GRP_ID));

    Condition transformationFailedGtZero =
        DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).gt(0);
    Condition missingAttemptsGtZero =
        DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).gt(0);
    Condition activityMissingGtZero = DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).gt(0);

    var periodMetrics =
        dsl.select(
                periodStartExpr.as("period_start"),
                countDistinctTupleFiltered(
                        DSL.trueCondition(),
                        RECONCILIATION.RPT_GRP_ID,
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("batches_ran"),
                countDistinctTupleFiltered(
                        transformationFailedGtZero.or(missingAttemptsGtZero).or(activityMissingGtZero),
                        RECONCILIATION.RPT_GRP_ID,
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("batches_needing_attention"),
                countDistinctTupleFiltered(
                        transformationFailedGtZero,
                        RECONCILIATION.RPT_GRP_ID,
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("transformation_failure_batches"),
                countDistinctTupleFiltered(
                        missingAttemptsGtZero,
                        RECONCILIATION.RPT_GRP_ID,
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("missing_attempt_batches"),
                countDistinctTupleFiltered(
                        activityMissingGtZero,
                        RECONCILIATION.RPT_GRP_ID,
                        RECONCILIATION.BATCH_ID,
                        RECONCILIATION.SEQ_NO)
                    .as("activity_missing_batches"),
                DSL.coalesce(
                        DSL.sum(RECONCILIATION.ACTUAL_REPORTABLE_TXN), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_reported_transactions"),
                DSL.coalesce(DSL.sum(RECONCILIATION.EXCLUDED_TXN), DSL.inline(java.math.BigDecimal.ZERO))
                    .as("total_excluded_transactions"))
            .from(RECONCILIATION)
            .where(scope)
            .groupBy(periodStartExpr)
            .asTable("period_metrics");

    Field<Long> batchesRan = periodMetrics.field("batches_ran", Long.class);
    Field<Long> batchesNeedingAttention = periodMetrics.field("batches_needing_attention", Long.class);
    Field<Long> transformationFailureBatches =
        periodMetrics.field("transformation_failure_batches", Long.class);
    Field<Long> missingAttemptBatches = periodMetrics.field("missing_attempt_batches", Long.class);
    Field<Long> activityMissingBatches = periodMetrics.field("activity_missing_batches", Long.class);
    Field<Long> totalReported = periodMetrics.field("total_reported_transactions", Long.class);
    Field<Long> totalExcluded = periodMetrics.field("total_excluded_transactions", Long.class);

    return dsl.select(
            periodsStart.as("periodStart"),
            DSL.coalesce(batchesRan, 0L).as("batchesRan"),
            DSL.coalesce(batchesRan, 0L).sub(DSL.coalesce(batchesNeedingAttention, 0L)).as("successfulBatches"),
            DSL.coalesce(batchesNeedingAttention, 0L).as("batchesNeedingAttention"),
            DSL.coalesce(transformationFailureBatches, 0L).as("transformationFailureBatches"),
            DSL.coalesce(missingAttemptBatches, 0L).as("missingAttemptBatches"),
            DSL.coalesce(activityMissingBatches, 0L).as("activityMissingBatches"),
            DSL.coalesce(totalReported, 0L).as("totalReportedTransactions"),
            DSL.coalesce(totalExcluded, 0L).as("totalExcludedTransactions"))
        .from(periods)
        .leftJoin(periodMetrics)
        .using(periodsStart)
        .orderBy(periodsStart)
        .fetch(
            r ->
                new BatchHealthTrendProjectionImpl(
                    r.get("periodStart", LocalDate.class),
                    r.get("batchesRan", long.class),
                    r.get("successfulBatches", long.class),
                    r.get("batchesNeedingAttention", long.class),
                    r.get("transformationFailureBatches", long.class),
                    r.get("missingAttemptBatches", long.class),
                    r.get("activityMissingBatches", long.class),
                    r.get("totalReportedTransactions", long.class),
                    r.get("totalExcludedTransactions", long.class)));
  }

  public interface DashboardCountsProjection {
    long getBatchesRan();

    long getBatchesNotYetReported();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getDuplicateTransactionBatches();

    long getExclusionBatches();

    long getSimulatedTransactionBatches();

    long getSoftDedupBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }

  private record DashboardCountsProjectionImpl(
      long batchesRan,
      long batchesNotYetReported,
      long batchesNeedingAttention,
      long transformationFailureBatches,
      long missingAttemptBatches,
      long activityMissingBatches,
      long duplicateTransactionBatches,
      long exclusionBatches,
      long simulatedTransactionBatches,
      long softDedupBatches,
      long totalReportedTransactions,
      long totalExcludedTransactions)
      implements DashboardCountsProjection {
    @Override
    public long getBatchesRan() {
      return batchesRan;
    }

    @Override
    public long getBatchesNotYetReported() {
      return batchesNotYetReported;
    }

    @Override
    public long getBatchesNeedingAttention() {
      return batchesNeedingAttention;
    }

    @Override
    public long getTransformationFailureBatches() {
      return transformationFailureBatches;
    }

    @Override
    public long getMissingAttemptBatches() {
      return missingAttemptBatches;
    }

    @Override
    public long getActivityMissingBatches() {
      return activityMissingBatches;
    }

    @Override
    public long getDuplicateTransactionBatches() {
      return duplicateTransactionBatches;
    }

    @Override
    public long getExclusionBatches() {
      return exclusionBatches;
    }

    @Override
    public long getSimulatedTransactionBatches() {
      return simulatedTransactionBatches;
    }

    @Override
    public long getSoftDedupBatches() {
      return softDedupBatches;
    }

    @Override
    public long getTotalReportedTransactions() {
      return totalReportedTransactions;
    }

    @Override
    public long getTotalExcludedTransactions() {
      return totalExcludedTransactions;
    }
  }

  public interface ReportGroupMetricsProjection {
    int getReportGroupId();

    String getReportGroupName();

    long getBatchesRan();

    long getSuccessfulBatches();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }

  private record ReportGroupMetricsProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      long batchesRan,
      long successfulBatches,
      long batchesNeedingAttention,
      long transformationFailureBatches,
      long missingAttemptBatches,
      long activityMissingBatches,
      long totalReportedTransactions,
      long totalExcludedTransactions)
      implements ReportGroupMetricsProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public long getBatchesRan() {
      return batchesRan;
    }

    @Override
    public long getSuccessfulBatches() {
      return successfulBatches;
    }

    @Override
    public long getBatchesNeedingAttention() {
      return batchesNeedingAttention;
    }

    @Override
    public long getTransformationFailureBatches() {
      return transformationFailureBatches;
    }

    @Override
    public long getMissingAttemptBatches() {
      return missingAttemptBatches;
    }

    @Override
    public long getActivityMissingBatches() {
      return activityMissingBatches;
    }

    @Override
    public long getTotalReportedTransactions() {
      return totalReportedTransactions;
    }

    @Override
    public long getTotalExcludedTransactions() {
      return totalExcludedTransactions;
    }
  }

  public interface BatchHealthTrendProjection {
    LocalDate getPeriodStart();

    long getBatchesRan();

    long getSuccessfulBatches();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }

  private record BatchHealthTrendProjectionImpl(
      LocalDate periodStart,
      long batchesRan,
      long successfulBatches,
      long batchesNeedingAttention,
      long transformationFailureBatches,
      long missingAttemptBatches,
      long activityMissingBatches,
      long totalReportedTransactions,
      long totalExcludedTransactions)
      implements BatchHealthTrendProjection {
    @Override
    public LocalDate getPeriodStart() {
      return periodStart;
    }

    @Override
    public long getBatchesRan() {
      return batchesRan;
    }

    @Override
    public long getSuccessfulBatches() {
      return successfulBatches;
    }

    @Override
    public long getBatchesNeedingAttention() {
      return batchesNeedingAttention;
    }

    @Override
    public long getTransformationFailureBatches() {
      return transformationFailureBatches;
    }

    @Override
    public long getMissingAttemptBatches() {
      return missingAttemptBatches;
    }

    @Override
    public long getActivityMissingBatches() {
      return activityMissingBatches;
    }

    @Override
    public long getTotalReportedTransactions() {
      return totalReportedTransactions;
    }

    @Override
    public long getTotalExcludedTransactions() {
      return totalExcludedTransactions;
    }
  }
}
