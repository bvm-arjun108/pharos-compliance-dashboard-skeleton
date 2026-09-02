package com.pharos.compliance.batch.repository;

import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportGroupConfig.REPORT_GROUP_CONFIG;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class BatchExplorerRepository {

  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation
      RECONCILIATION = REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY =
      RECORD_TRANSFORMATION_JOURNEY;
  private static final com.pharos.compliance.jooq.tables.ReportGroupConfig CONFIG =
      REPORT_GROUP_CONFIG;

  private final DSLContext dsl;

  public BatchExplorerRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Same row-value distinct-count-with-FILTER pattern as {@code DashboardRepository}. */
  private static Field<Long> countDistinctTupleFiltered(Condition filter, Field<?>... columns) {
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < columns.length; i++) {
      if (i > 0) {
        placeholders.append(", ");
      }
      placeholders.append('{').append(i).append('}');
    }
    Object[] args = new Object[columns.length + 1];
    System.arraycopy(columns, 0, args, 0, columns.length);
    args[columns.length] = filter;
    return DSL.field(
        "count(distinct (" + placeholders + ")) filter (where {" + columns.length + "})",
        SQLDataType.BIGINT,
        args);
  }

  private static Condition batchIdScope(String batchId, Field<String> batchIdField) {
    return batchId.isEmpty()
        ? DSL.trueCondition()
        : DSL.lower(batchIdField).like(DSL.lower(DSL.inline("%" + batchId + "%")));
  }

  /**
   * The original SQL's {@code metricFocus} sort keys are {@code CASE WHEN :metricFocus = 'X' THEN
   * col END DESC NULLS LAST} -- when the focus doesn't match, that CASE evaluates to NULL for
   * every row, so the key ties every row and has no effect on ordering. Since {@code metricFocus}
   * is already known in Java before this query is built, the equivalent (and simpler) approach is
   * to only add that sort key when it actually applies, rather than emit a same-value-for-every-row
   * placeholder -- Postgres treats a bare integer literal in ORDER BY as a column-position
   * reference, not a constant, so a literal placeholder isn't even a safe way to do this.
   */
  private static List<org.jooq.OrderField<?>> batchQueueSortKeys(
      String metricFocus,
      Field<Long> transformerOutput,
      Field<Long> excludedTransactions,
      Field<String> statusBucket,
      Field<java.time.OffsetDateTime> completedAt,
      Field<java.time.OffsetDateTime> startedAt,
      Field<String> batchId) {
    List<org.jooq.OrderField<?>> keys = new java.util.ArrayList<>();
    if ("REPORTED".equals(metricFocus)) {
      keys.add(transformerOutput.desc().nullsLast());
    }
    if ("EXCLUDED".equals(metricFocus)) {
      keys.add(excludedTransactions.desc().nullsLast());
    }
    keys.add(DSL.when(statusBucket.eq("NOT_YET_REPORTED"), 1).otherwise(0));
    keys.add(completedAt.desc().nullsLast());
    keys.add(startedAt.desc().nullsLast());
    keys.add(batchId.asc());
    return keys;
  }

  /**
   * {@code record_transformation_journey.created_timestamp} is {@code timestamptz}; see the same
   * note in {@code DashboardRepository} for why this stays a raw comparison rather than a typed
   * one.
   */
  private static Condition journeyCreatedBetween(
      LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive) {
    return DSL.condition(
        "{0} >= {1} and {0} < {2}",
        JOURNEY.CREATED_TIMESTAMP, DSL.val(fromTimestamp), DSL.val(toTimestampExclusive));
  }

  /**
   * Every batch this class reports on is either (a) a batch with a reconciliation record --
   * {@code report_transformation_reconciliation} -- possibly with issues, or (b) a batch that has
   * only journey activity so far and no reconciliation record yet ("not yet reported"). This
   * builds the reconciled side: the latest reconciliation rows in scope, with the three issue
   * counts and a combined {@code total_issues}.
   */
  private org.jooq.Table<?> enrichedBatchMetrics(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      Integer reportGroupId,
      boolean filterByCountry,
      List<Integer> reportGroupIds) {
    var batchMetrics =
        dsl.select(
                RECONCILIATION.RPT_GRP_ID,
                RECONCILIATION.BATCH_ID,
                RECONCILIATION.SEQ_NO,
                RECONCILIATION.RPT_GRP_NAME,
                RECONCILIATION.RPT_FROM_DATE,
                RECONCILIATION.RPT_TO_DATE,
                RECONCILIATION.CREATED_TIMESTAMP,
                RECONCILIATION.MODIFIED_TIMESTAMP,
                DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("transformation_failures"),
                DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("missing_attempts"),
                DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).cast(SQLDataType.BIGINT).as("activity_missing"),
                DSL.abs(
                        DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0)
                            .sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
                    .cast(SQLDataType.BIGINT)
                    .as("filtration_errors"),
                DSL.abs(
                        DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                            .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
                    .cast(SQLDataType.BIGINT)
                    .as("reconciliation_imbalance"),
                DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("expected_transformation_attempts"),
                DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("actual_transformation_attempts"),
                DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("transformed_activities"),
                DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("transformer_output"),
                DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as("excluded_transactions"),
                DSL.coalesce(RECONCILIATION.DUPLICATE_TRANSFORMATION, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("duplicate_transactions"),
                DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as("simulated_transactions"),
                DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0)
                    .cast(SQLDataType.BIGINT)
                    .as("soft_dedup_transactions"))
            .from(RECONCILIATION)
            .where(RECONCILIATION.CREATED_TIMESTAMP.ge(fromTimestamp))
            .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
            .and(batchIdScope(batchId, RECONCILIATION.BATCH_ID))
            .and(reportGroupId == null ? DSL.trueCondition() : RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
            .asTable("batch_metrics");

    Field<Integer> bmRptGrpId = batchMetrics.field(RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<Long> bmTransformationFailures = batchMetrics.field("transformation_failures", Long.class);
    Field<Long> bmMissingAttempts = batchMetrics.field("missing_attempts", Long.class);
    Field<Long> bmActivityMissing = batchMetrics.field("activity_missing", Long.class);

    return dsl.select(batchMetrics.fields())
        .select(
            bmTransformationFailures
                .add(bmMissingAttempts)
                .add(bmActivityMissing)
                .as("total_issues"))
        .from(batchMetrics)
        .where(filterByCountry ? bmRptGrpId.in(reportGroupIds) : DSL.trueCondition())
        .asTable("enriched_batch_metrics");
  }

  /** Journey activity with no reconciliation record yet -- "not yet reported" batches. */
  private org.jooq.Table<?> notYetReportedBatches(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      Integer reportGroupId,
      boolean filterByCountry,
      List<Integer> reportGroupIds) {
    var config = CONFIG;
    Field<String> latestActiveReportGroupName =
        DSL.field(
            dsl.select(config.RPT_GRP_NAME)
                .from(config)
                .where(config.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                .and(config.RPT_CONFIG_ACTIVE_FLAG.eq(true))
                .orderBy(config.MODIFIED_TIMESTAMP.desc().nullsLast())
                .limit(1));

    return dsl.select(
            JOURNEY.RPT_GRP_ID,
            JOURNEY.BATCH_ID,
            latestActiveReportGroupName.as("rpt_grp_name"),
            DSL.min(JOURNEY.CREATED_TIMESTAMP).as("started_at"),
            DSL.max(JOURNEY.MODIFIED_TIMESTAMP).as("last_activity_at"),
            DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as("discovered_transactions"))
        .from(JOURNEY)
        .where(journeyCreatedBetween(fromTimestamp, toTimestampExclusive))
        .and(batchIdScope(batchId, JOURNEY.BATCH_ID))
        .and(reportGroupId == null ? DSL.trueCondition() : JOURNEY.RPT_GRP_ID.eq(reportGroupId))
        .and(filterByCountry ? JOURNEY.RPT_GRP_ID.in(reportGroupIds) : DSL.trueCondition())
        .and(
            DSL.notExists(
                dsl.selectOne()
                    .from(RECONCILIATION)
                    .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                    .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
        .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
        .asTable("not_yet_reported_batches");
  }

  public BatchSummaryProjection getBatchSummary(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      Integer reportGroupId,
      boolean filterByCountry,
      List<Integer> reportGroupIds) {
    var enriched =
        enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    var notYetReported =
        notYetReportedBatches(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Long> totalIssues = enriched.field("total_issues", Long.class);
    Field<String> enrichedRptGrpName = enriched.field(RECONCILIATION.RPT_GRP_NAME.getName(), String.class);
    Field<String> notYetReportedRptGrpName = notYetReported.field("rpt_grp_name", String.class);

    long allEnriched = dsl.selectCount().from(enriched).fetchOne(0, long.class);
    long allNotYetReported = dsl.selectCount().from(notYetReported).fetchOne(0, long.class);
    long successful =
        dsl.selectCount().from(enriched).where(totalIssues.eq(0L)).fetchOne(0, long.class);
    long attention =
        dsl.selectCount().from(enriched).where(totalIssues.gt(0L)).fetchOne(0, long.class);
    String reportGroupName =
        java.util.Optional.ofNullable(
                dsl.select(DSL.max(enrichedRptGrpName)).from(enriched).fetchOne(0, String.class))
            .orElseGet(
                () -> dsl.select(DSL.max(notYetReportedRptGrpName)).from(notYetReported).fetchOne(0, String.class));

    return new BatchSummaryProjectionImpl(
        allEnriched + allNotYetReported, successful, attention, allNotYetReported, reportGroupName);
  }

  public List<BatchQueueProjection> getBatchQueue(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      String batchId,
      Integer reportGroupId,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      String status,
      String issueType,
      String metricFocus,
      int size,
      long offset) {
    var enriched =
        enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    var notYetReported =
        notYetReportedBatches(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Integer> eRptGrpId = enriched.field(RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> eBatchId = enriched.field(RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Integer> eSeqNo = enriched.field(RECONCILIATION.SEQ_NO.getName(), Integer.class);
    Field<String> eRptGrpName = enriched.field(RECONCILIATION.RPT_GRP_NAME.getName(), String.class);
    Field<String> eFromDate = enriched.field(RECONCILIATION.RPT_FROM_DATE.getName(), String.class);
    Field<String> eToDate = enriched.field(RECONCILIATION.RPT_TO_DATE.getName(), String.class);
    Field<LocalDateTime> eCreated = enriched.field(RECONCILIATION.CREATED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<LocalDateTime> eModified = enriched.field(RECONCILIATION.MODIFIED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<Long> eTransformationFailures = enriched.field("transformation_failures", Long.class);
    Field<Long> eMissingAttempts = enriched.field("missing_attempts", Long.class);
    Field<Long> eActivityMissing = enriched.field("activity_missing", Long.class);
    Field<Long> eFiltrationErrors = enriched.field("filtration_errors", Long.class);
    Field<Long> eReconciliationImbalance = enriched.field("reconciliation_imbalance", Long.class);
    Field<Long> eTransformerOutput = enriched.field("transformer_output", Long.class);
    Field<Long> eExcludedTransactions = enriched.field("excluded_transactions", Long.class);
    Field<Long> eDuplicateTransactions = enriched.field("duplicate_transactions", Long.class);
    Field<Long> eSimulatedTransactions = enriched.field("simulated_transactions", Long.class);
    Field<Long> eSoftDedupTransactions = enriched.field("soft_dedup_transactions", Long.class);
    Field<Long> eTotalIssues = enriched.field("total_issues", Long.class);

    Field<Integer> nRptGrpId = notYetReported.field(JOURNEY.RPT_GRP_ID.getName(), Integer.class);
    Field<String> nBatchId = notYetReported.field(JOURNEY.BATCH_ID.getName(), String.class);
    Field<String> nRptGrpName = notYetReported.field("rpt_grp_name", String.class);
    Field<LocalDateTime> nStartedAt = notYetReported.field("started_at", LocalDateTime.class);
    Field<Long> nDiscovered = notYetReported.field("discovered_transactions", Long.class);

    var reconciledBranch =
        dsl.select(
            eRptGrpId,
            eBatchId,
            eSeqNo,
            eRptGrpName,
            eFromDate,
            eToDate,
            eCreated.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as("started_at"),
            eModified.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as("completed_at"),
            eTransformationFailures,
            eMissingAttempts,
            eActivityMissing,
            eFiltrationErrors,
            eReconciliationImbalance,
            eTransformerOutput,
            eExcludedTransactions,
            eDuplicateTransactions,
            eSimulatedTransactions,
            eSoftDedupTransactions,
            eTotalIssues,
            DSL.inline(0L).as("discovered_transactions"),
            DSL.inline("RECONCILED").as("status_bucket"))
            .from(enriched);

    var notYetReportedBranch =
        dsl.select(
            nRptGrpId,
            nBatchId,
            DSL.inline(0).as("seq_no"),
            nRptGrpName,
            DSL.cast(null, SQLDataType.CLOB).as("rpt_from_date"),
            DSL.cast(null, SQLDataType.CLOB).as("rpt_to_date"),
            nStartedAt.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as("started_at"),
            DSL.cast(null, SQLDataType.TIMESTAMPWITHTIMEZONE).as("completed_at"),
            DSL.inline(0L).as("transformation_failures"),
            DSL.inline(0L).as("missing_attempts"),
            DSL.inline(0L).as("activity_missing"),
            DSL.inline(0L).as("filtration_errors"),
            DSL.inline(0L).as("reconciliation_imbalance"),
            DSL.inline(0L).as("transformer_output"),
            DSL.inline(0L).as("excluded_transactions"),
            DSL.inline(0L).as("duplicate_transactions"),
            DSL.inline(0L).as("simulated_transactions"),
            DSL.inline(0L).as("soft_dedup_transactions"),
            DSL.inline(0L).as("total_issues"),
            nDiscovered,
            DSL.inline("NOT_YET_REPORTED").as("status_bucket"))
            .from(notYetReported);

    var combinedQueue = reconciledBranch.unionAll(notYetReportedBranch).asTable("combined_queue");

    Field<Integer> cRptGrpId = combinedQueue.field("rpt_grp_id", Integer.class);
    Field<String> cRptGrpName = combinedQueue.field("rpt_grp_name", String.class);
    Field<String> cBatchId = combinedQueue.field("batch_id", String.class);
    Field<Integer> cSeqNo = combinedQueue.field("seq_no", Integer.class);
    Field<String> cFromDate = combinedQueue.field("rpt_from_date", String.class);
    Field<String> cToDate = combinedQueue.field("rpt_to_date", String.class);
    Field<java.time.OffsetDateTime> cStartedAt =
        combinedQueue.field("started_at", java.time.OffsetDateTime.class);
    Field<java.time.OffsetDateTime> cCompletedAt =
        combinedQueue.field("completed_at", java.time.OffsetDateTime.class);
    Field<Long> cTransformationFailures = combinedQueue.field("transformation_failures", Long.class);
    Field<Long> cMissingAttempts = combinedQueue.field("missing_attempts", Long.class);
    Field<Long> cActivityMissing = combinedQueue.field("activity_missing", Long.class);
    Field<Long> cFiltrationErrors = combinedQueue.field("filtration_errors", Long.class);
    Field<Long> cReconciliationImbalance = combinedQueue.field("reconciliation_imbalance", Long.class);
    Field<Long> cTransformerOutput = combinedQueue.field("transformer_output", Long.class);
    Field<Long> cExcludedTransactions = combinedQueue.field("excluded_transactions", Long.class);
    Field<Long> cDuplicateTransactions = combinedQueue.field("duplicate_transactions", Long.class);
    Field<Long> cSimulatedTransactions = combinedQueue.field("simulated_transactions", Long.class);
    Field<Long> cSoftDedupTransactions = combinedQueue.field("soft_dedup_transactions", Long.class);
    Field<Long> cTotalIssues = combinedQueue.field("total_issues", Long.class);
    Field<Long> cDiscoveredTransactions = combinedQueue.field("discovered_transactions", Long.class);
    Field<String> cStatusBucket = combinedQueue.field("status_bucket", String.class);

    Condition statusCondition =
        switch (status) {
          case "ALL" -> DSL.trueCondition();
          case "SUCCESSFUL" -> cStatusBucket.eq("RECONCILED").and(cTotalIssues.eq(0L));
          case "ATTENTION" -> cStatusBucket.eq("RECONCILED").and(cTotalIssues.gt(0L));
          case "NOT_YET_REPORTED" -> cStatusBucket.eq("NOT_YET_REPORTED");
          default -> DSL.falseCondition();
        };
    Condition reconciledOnly = cStatusBucket.eq("RECONCILED");
    Condition issueTypeCondition =
        switch (issueType) {
          case "ALL" -> DSL.trueCondition();
          case "ACTIVITY_MISSING" -> reconciledOnly.and(cActivityMissing.gt(0L));
          case "MISSING_ATTEMPTS" -> reconciledOnly.and(cMissingAttempts.gt(0L));
          case "TRANSFORMATION" -> reconciledOnly.and(cTransformationFailures.gt(0L));
          case "DUPLICATE_TRANSFORMATION" -> reconciledOnly.and(cDuplicateTransactions.gt(0L));
          case "EXCLUSION" -> reconciledOnly.and(cExcludedTransactions.gt(0L));
          case "SIMULATED" -> reconciledOnly.and(cSimulatedTransactions.gt(0L));
          case "SOFT_DEDUP" -> reconciledOnly.and(cSoftDedupTransactions.gt(0L));
          default -> DSL.falseCondition();
        };
    Condition metricFocusCondition =
        switch (metricFocus) {
          case "DEFAULT" -> DSL.trueCondition();
          case "REPORTED" -> reconciledOnly.and(cTransformerOutput.gt(0L));
          case "EXCLUDED" -> reconciledOnly.and(cExcludedTransactions.gt(0L));
          default -> DSL.falseCondition();
        };

    var matchingCount = DSL.count().over().as("matchingCount");

    return dsl.select(
            cRptGrpId.as("reportGroupId"),
            cRptGrpName.as("reportGroupName"),
            cBatchId.as("batchId"),
            cSeqNo.as("sequenceNumber"),
            cFromDate.as("reportingPeriodFrom"),
            cToDate.as("reportingPeriodTo"),
            cStartedAt.as("startedAt"),
            cCompletedAt.as("completedAt"),
            cTransformationFailures.as("transformationFailures"),
            cMissingAttempts.as("missingAttempts"),
            cActivityMissing.as("activityMissing"),
            cFiltrationErrors.as("filtrationErrors"),
            cReconciliationImbalance.as("reconciliationImbalance"),
            cTransformerOutput.as("transformerOutput"),
            cExcludedTransactions.as("excludedTransactions"),
            cDuplicateTransactions.as("duplicateTransactions"),
            cSimulatedTransactions.as("simulatedTransactions"),
            cSoftDedupTransactions.as("softDedupTransactions"),
            cTotalIssues.as("totalIssues"),
            cDiscoveredTransactions.as("discoveredTransactions"),
            cStatusBucket.as("statusBucket"),
            matchingCount)
        .from(combinedQueue)
        .where(statusCondition)
        .and(issueTypeCondition)
        .and(metricFocusCondition)
        .orderBy(batchQueueSortKeys(metricFocus, cTransformerOutput, cExcludedTransactions, cStatusBucket, cCompletedAt, cStartedAt, cBatchId))
        .limit(size)
        .offset(offset)
        .fetch(
            r ->
                new BatchQueueProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("batchId", String.class),
                    r.get("sequenceNumber", int.class),
                    r.get("reportingPeriodFrom", String.class),
                    r.get("reportingPeriodTo", String.class),
                    toLocalDateTime(r.get("startedAt", java.time.OffsetDateTime.class)),
                    toLocalDateTime(r.get("completedAt", java.time.OffsetDateTime.class)),
                    r.get("transformationFailures", long.class),
                    r.get("missingAttempts", long.class),
                    r.get("activityMissing", long.class),
                    r.get("filtrationErrors", long.class),
                    r.get("reconciliationImbalance", long.class),
                    r.get("transformerOutput", long.class),
                    r.get("excludedTransactions", long.class),
                    r.get("duplicateTransactions", long.class),
                    r.get("simulatedTransactions", long.class),
                    r.get("softDedupTransactions", long.class),
                    r.get("totalIssues", long.class),
                    r.get("discoveredTransactions", long.class),
                    r.get("statusBucket", String.class),
                    r.get("matchingCount", long.class)));
  }

  private static LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
    return value == null ? null : value.toLocalDateTime();
  }

  public Optional<BatchDetailsProjection> getBatchDetails(
      int reportGroupId, String batchId, int sequenceNumber) {
    Field<Long> transformationFailures =
        DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as("tf_out");
    return dsl.select(
            RECONCILIATION.RPT_GRP_ID.as("reportGroupId"),
            RECONCILIATION.RPT_GRP_NAME.as("reportGroupName"),
            RECONCILIATION.BATCH_ID.as("batchId"),
            RECONCILIATION.SEQ_NO.as("sequenceNumber"),
            RECONCILIATION.RPT_FROM_DATE.as("reportingPeriodFrom"),
            RECONCILIATION.RPT_TO_DATE.as("reportingPeriodTo"),
            RECONCILIATION.CREATED_TIMESTAMP.as("startedAt"),
            RECONCILIATION.MODIFIED_TIMESTAMP.as("completedAt"),
            DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0)
                .cast(SQLDataType.BIGINT)
                .as("transformationFailures"),
            DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)
                .cast(SQLDataType.BIGINT)
                .as("missingAttempts"),
            DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).cast(SQLDataType.BIGINT).as("activityMissing"),
            DSL.coalesce(RECONCILIATION.DUPLICATE_TRANSFORMATION, 0)
                .cast(SQLDataType.BIGINT)
                .as("duplicateTransactions"),
            DSL.abs(
                    DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0)
                        .sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
                .cast(SQLDataType.BIGINT)
                .as("filtrationErrors"),
            DSL.abs(
                    DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                        .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
                .cast(SQLDataType.BIGINT)
                .as("reconciliationImbalance"),
            DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).cast(SQLDataType.BIGINT).as("selectedTransactions"),
            DSL.greatest(
                    DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0)
                        .sub(DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)),
                    DSL.inline(0))
                .cast(SQLDataType.BIGINT)
                .as("transactionAttemptsFound"),
            DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0)
                .cast(SQLDataType.BIGINT)
                .as("expectedReportableTransactions"),
            DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)
                .cast(SQLDataType.BIGINT)
                .as("actualReportableTransactions"),
            DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                .cast(SQLDataType.BIGINT)
                .as("expectedTransformationAttempts"),
            DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                .cast(SQLDataType.BIGINT)
                .as("actualTransformationAttempts"),
            DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0)
                .cast(SQLDataType.BIGINT)
                .as("transformedActivities"),
            DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("transformerOutput"),
            DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as("excludedTransactions"),
            DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as("simulatedTransactions"),
            DSL.coalesce(RECONCILIATION.ALREADY_REPORTED_COUNT, 0)
                .cast(SQLDataType.BIGINT)
                .as("alreadyReportedTransactions"),
            DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0)
                .cast(SQLDataType.BIGINT)
                .as("softDedupTransactions"),
            DSL.exists(
                    dsl.selectOne()
                        .from(JOURNEY)
                        .where(JOURNEY.RPT_GRP_ID.eq(RECONCILIATION.RPT_GRP_ID))
                        .and(JOURNEY.BATCH_ID.eq(RECONCILIATION.BATCH_ID)))
                .as("journeyAvailable"),
            DSL.exists(
                    dsl.selectOne()
                        .from(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT)
                        .where(
                            com.pharos.compliance.jooq.tables.RuleHitExclusionAudit
                                .RULE_HIT_EXCLUSION_AUDIT
                                .RPT_GRP_ID
                                .eq(RECONCILIATION.RPT_GRP_ID))
                        .and(
                            com.pharos.compliance.jooq.tables.RuleHitExclusionAudit
                                .RULE_HIT_EXCLUSION_AUDIT
                                .PROCESSING_BATCH_ID
                                .eq(RECONCILIATION.BATCH_ID)))
                .as("exclusionsAvailable"))
        .from(RECONCILIATION)
        .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
        .and(RECONCILIATION.BATCH_ID.eq(batchId))
        .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
        .fetchOptional(
            r ->
                new BatchDetailsProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("batchId", String.class),
                    r.get("sequenceNumber", int.class),
                    r.get("reportingPeriodFrom", String.class),
                    r.get("reportingPeriodTo", String.class),
                    r.get("startedAt", LocalDateTime.class),
                    r.get("completedAt", LocalDateTime.class),
                    r.get("transformationFailures", long.class),
                    r.get("missingAttempts", long.class),
                    r.get("activityMissing", long.class),
                    r.get("duplicateTransactions", long.class),
                    r.get("filtrationErrors", long.class),
                    r.get("reconciliationImbalance", long.class),
                    r.get("selectedTransactions", long.class),
                    r.get("transactionAttemptsFound", long.class),
                    r.get("expectedReportableTransactions", long.class),
                    r.get("actualReportableTransactions", long.class),
                    r.get("expectedTransformationAttempts", long.class),
                    r.get("actualTransformationAttempts", long.class),
                    r.get("transformedActivities", long.class),
                    r.get("transformerOutput", long.class),
                    r.get("excludedTransactions", long.class),
                    r.get("simulatedTransactions", long.class),
                    r.get("alreadyReportedTransactions", long.class),
                    r.get("softDedupTransactions", long.class),
                    r.get("journeyAvailable", boolean.class),
                    r.get("exclusionsAvailable", boolean.class)));
  }

  public Optional<NotYetReportedBatchDetailsProjection> getNotYetReportedBatchDetails(
      int reportGroupId, String batchId) {
    Field<String> latestActiveReportGroupName =
        DSL.field(
            dsl.select(CONFIG.RPT_GRP_NAME)
                .from(CONFIG)
                .where(CONFIG.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                .and(CONFIG.RPT_CONFIG_ACTIVE_FLAG.eq(true))
                .orderBy(CONFIG.MODIFIED_TIMESTAMP.desc().nullsLast())
                .limit(1));

    var exclusionAudit =
        com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT;

    Condition stalledCondition =
        DSL.upper(DSL.coalesce(JOURNEY.STATUS, "")).eq("ERROR").and(JOURNEY.PROCESSING_COMPLETE.isTrue());

    return dsl.select(
            JOURNEY.RPT_GRP_ID.as("reportGroupId"),
            latestActiveReportGroupName.as("reportGroupName"),
            JOURNEY.BATCH_ID.as("batchId"),
            DSL.min(JOURNEY.CREATED_TIMESTAMP).as("startedAt"),
            DSL.max(JOURNEY.MODIFIED_TIMESTAMP).as("lastActivityAt"),
            DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as("discoveredTransactions"),
            DSL.countDistinct(JOURNEY.IDENTIFIER)
                .filterWhere(stalledCondition)
                .cast(SQLDataType.BIGINT)
                .as("stalledTransactions"),
            DSL.boolOr(DSL.inline(true)).as("journeyAvailable"),
            DSL.exists(
                    dsl.selectOne()
                        .from(exclusionAudit)
                        .where(exclusionAudit.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                        .and(exclusionAudit.PROCESSING_BATCH_ID.eq(JOURNEY.BATCH_ID)))
                .as("exclusionsAvailable"))
        .from(JOURNEY)
        .where(JOURNEY.RPT_GRP_ID.eq(reportGroupId))
        .and(JOURNEY.BATCH_ID.eq(batchId))
        .and(
            DSL.notExists(
                dsl.selectOne()
                    .from(RECONCILIATION)
                    .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
                    .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
        .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
        .fetchOptional(
            r ->
                new NotYetReportedBatchDetailsProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("batchId", String.class),
                    toLocalDateTime(r.get("startedAt", java.time.OffsetDateTime.class)),
                    toLocalDateTime(r.get("lastActivityAt", java.time.OffsetDateTime.class)),
                    r.get("discoveredTransactions", long.class),
                    r.get("stalledTransactions", long.class),
                    r.get("journeyAvailable", boolean.class),
                    r.get("exclusionsAvailable", boolean.class)));
  }

  public interface BatchSummaryProjection {
    long getAllBatches();

    long getSuccessfulBatches();

    long getAttentionBatches();

    long getNotYetReportedBatches();

    String getReportGroupName();
  }

  private record BatchSummaryProjectionImpl(
      long allBatches, long successfulBatches, long attentionBatches, long notYetReportedBatches, String reportGroupName)
      implements BatchSummaryProjection {
    @Override
    public long getAllBatches() {
      return allBatches;
    }

    @Override
    public long getSuccessfulBatches() {
      return successfulBatches;
    }

    @Override
    public long getAttentionBatches() {
      return attentionBatches;
    }

    @Override
    public long getNotYetReportedBatches() {
      return notYetReportedBatches;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }
  }

  public interface BatchQueueProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    LocalDateTime getStartedAt();

    LocalDateTime getCompletedAt();

    long getTransformationFailures();

    long getMissingAttempts();

    long getActivityMissing();

    long getFiltrationErrors();

    long getReconciliationImbalance();

    long getTransformerOutput();

    long getExcludedTransactions();

    long getDuplicateTransactions();

    long getSimulatedTransactions();

    long getSoftDedupTransactions();

    long getTotalIssues();

    long getDiscoveredTransactions();

    String getStatusBucket();

    long getMatchingCount();
  }

  private record BatchQueueProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      String batchId,
      int sequenceNumber,
      String reportingPeriodFrom,
      String reportingPeriodTo,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      long transformationFailures,
      long missingAttempts,
      long activityMissing,
      long filtrationErrors,
      long reconciliationImbalance,
      long transformerOutput,
      long excludedTransactions,
      long duplicateTransactions,
      long simulatedTransactions,
      long softDedupTransactions,
      long totalIssues,
      long discoveredTransactions,
      String statusBucket,
      long matchingCount)
      implements BatchQueueProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public String getBatchId() {
      return batchId;
    }

    @Override
    public int getSequenceNumber() {
      return sequenceNumber;
    }

    @Override
    public String getReportingPeriodFrom() {
      return reportingPeriodFrom;
    }

    @Override
    public String getReportingPeriodTo() {
      return reportingPeriodTo;
    }

    @Override
    public LocalDateTime getStartedAt() {
      return startedAt;
    }

    @Override
    public LocalDateTime getCompletedAt() {
      return completedAt;
    }

    @Override
    public long getTransformationFailures() {
      return transformationFailures;
    }

    @Override
    public long getMissingAttempts() {
      return missingAttempts;
    }

    @Override
    public long getActivityMissing() {
      return activityMissing;
    }

    @Override
    public long getFiltrationErrors() {
      return filtrationErrors;
    }

    @Override
    public long getReconciliationImbalance() {
      return reconciliationImbalance;
    }

    @Override
    public long getTransformerOutput() {
      return transformerOutput;
    }

    @Override
    public long getExcludedTransactions() {
      return excludedTransactions;
    }

    @Override
    public long getDuplicateTransactions() {
      return duplicateTransactions;
    }

    @Override
    public long getSimulatedTransactions() {
      return simulatedTransactions;
    }

    @Override
    public long getSoftDedupTransactions() {
      return softDedupTransactions;
    }

    @Override
    public long getTotalIssues() {
      return totalIssues;
    }

    @Override
    public long getDiscoveredTransactions() {
      return discoveredTransactions;
    }

    @Override
    public String getStatusBucket() {
      return statusBucket;
    }

    @Override
    public long getMatchingCount() {
      return matchingCount;
    }
  }

  public interface BatchDetailsProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    LocalDateTime getStartedAt();

    LocalDateTime getCompletedAt();

    long getTransformationFailures();

    long getMissingAttempts();

    long getActivityMissing();

    long getDuplicateTransactions();

    long getFiltrationErrors();

    long getReconciliationImbalance();

    long getSelectedTransactions();

    long getTransactionAttemptsFound();

    long getExpectedReportableTransactions();

    long getActualReportableTransactions();

    long getExpectedTransformationAttempts();

    long getActualTransformationAttempts();

    long getTransformedActivities();

    long getTransformerOutput();

    long getExcludedTransactions();

    long getSimulatedTransactions();

    long getAlreadyReportedTransactions();

    long getSoftDedupTransactions();

    boolean getJourneyAvailable();

    boolean getExclusionsAvailable();
  }

  private record BatchDetailsProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      String batchId,
      int sequenceNumber,
      String reportingPeriodFrom,
      String reportingPeriodTo,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      long transformationFailures,
      long missingAttempts,
      long activityMissing,
      long duplicateTransactions,
      long filtrationErrors,
      long reconciliationImbalance,
      long selectedTransactions,
      long transactionAttemptsFound,
      long expectedReportableTransactions,
      long actualReportableTransactions,
      long expectedTransformationAttempts,
      long actualTransformationAttempts,
      long transformedActivities,
      long transformerOutput,
      long excludedTransactions,
      long simulatedTransactions,
      long alreadyReportedTransactions,
      long softDedupTransactions,
      boolean journeyAvailable,
      boolean exclusionsAvailable)
      implements BatchDetailsProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public String getBatchId() {
      return batchId;
    }

    @Override
    public int getSequenceNumber() {
      return sequenceNumber;
    }

    @Override
    public String getReportingPeriodFrom() {
      return reportingPeriodFrom;
    }

    @Override
    public String getReportingPeriodTo() {
      return reportingPeriodTo;
    }

    @Override
    public LocalDateTime getStartedAt() {
      return startedAt;
    }

    @Override
    public LocalDateTime getCompletedAt() {
      return completedAt;
    }

    @Override
    public long getTransformationFailures() {
      return transformationFailures;
    }

    @Override
    public long getMissingAttempts() {
      return missingAttempts;
    }

    @Override
    public long getActivityMissing() {
      return activityMissing;
    }

    @Override
    public long getDuplicateTransactions() {
      return duplicateTransactions;
    }

    @Override
    public long getFiltrationErrors() {
      return filtrationErrors;
    }

    @Override
    public long getReconciliationImbalance() {
      return reconciliationImbalance;
    }

    @Override
    public long getSelectedTransactions() {
      return selectedTransactions;
    }

    @Override
    public long getTransactionAttemptsFound() {
      return transactionAttemptsFound;
    }

    @Override
    public long getExpectedReportableTransactions() {
      return expectedReportableTransactions;
    }

    @Override
    public long getActualReportableTransactions() {
      return actualReportableTransactions;
    }

    @Override
    public long getExpectedTransformationAttempts() {
      return expectedTransformationAttempts;
    }

    @Override
    public long getActualTransformationAttempts() {
      return actualTransformationAttempts;
    }

    @Override
    public long getTransformedActivities() {
      return transformedActivities;
    }

    @Override
    public long getTransformerOutput() {
      return transformerOutput;
    }

    @Override
    public long getExcludedTransactions() {
      return excludedTransactions;
    }

    @Override
    public long getSimulatedTransactions() {
      return simulatedTransactions;
    }

    @Override
    public long getAlreadyReportedTransactions() {
      return alreadyReportedTransactions;
    }

    @Override
    public long getSoftDedupTransactions() {
      return softDedupTransactions;
    }

    @Override
    public boolean getJourneyAvailable() {
      return journeyAvailable;
    }

    @Override
    public boolean getExclusionsAvailable() {
      return exclusionsAvailable;
    }
  }

  public interface NotYetReportedBatchDetailsProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    LocalDateTime getStartedAt();

    LocalDateTime getLastActivityAt();

    long getDiscoveredTransactions();

    long getStalledTransactions();

    boolean getJourneyAvailable();

    boolean getExclusionsAvailable();
  }

  private record NotYetReportedBatchDetailsProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      String batchId,
      LocalDateTime startedAt,
      LocalDateTime lastActivityAt,
      long discoveredTransactions,
      long stalledTransactions,
      boolean journeyAvailable,
      boolean exclusionsAvailable)
      implements NotYetReportedBatchDetailsProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public String getBatchId() {
      return batchId;
    }

    @Override
    public LocalDateTime getStartedAt() {
      return startedAt;
    }

    @Override
    public LocalDateTime getLastActivityAt() {
      return lastActivityAt;
    }

    @Override
    public long getDiscoveredTransactions() {
      return discoveredTransactions;
    }

    @Override
    public long getStalledTransactions() {
      return stalledTransactions;
    }

    @Override
    public boolean getJourneyAvailable() {
      return journeyAvailable;
    }

    @Override
    public boolean getExclusionsAvailable() {
      return exclusionsAvailable;
    }
  }
}
