package com.pharos.compliance.batch.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.batch.repository.projection.BatchSummaryProjection;
import com.pharos.compliance.batch.repository.projection.BatchQueueProjection;
import com.pharos.compliance.batch.repository.projection.BatchDetailsProjection;
import com.pharos.compliance.batch.repository.projection.NotYetReportedBatchDetailsProjection;
import static com.pharos.compliance.common.jooq.JooqConditions.containsIgnoreCase;
import static com.pharos.compliance.common.jooq.JooqConditions.zonelessTimestampBetween;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredBoolean;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
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
  private static final String ACTIVITY_MISSING_ALIAS = "activityMissing";
  private static final String ACTIVITY_MISSING_COLUMN = "activity_missing";
  private static final String BATCH_ID_ALIAS = "batchId";
  private static final String COMPLETED_AT_ALIAS = "completedAt";
  private static final String COMPLETED_AT_COLUMN = "completed_at";
  private static final String DISCOVERED_TRANSACTIONS_ALIAS = "discoveredTransactions";
  private static final String DISCOVERED_TRANSACTIONS_COLUMN = "discovered_transactions";
  private static final String DUPLICATE_TRANSACTIONS_ALIAS = "duplicateTransactions";
  private static final String DUPLICATE_TRANSACTIONS_COLUMN = "duplicate_transactions";
  private static final String EXCLUDED_TRANSACTIONS_ALIAS = "excludedTransactions";
  private static final String EXCLUDED_TRANSACTIONS_COLUMN = "excluded_transactions";
  private static final String EXCLUSIONS_AVAILABLE_ALIAS = "exclusionsAvailable";
  private static final String FILTRATION_ERRORS_ALIAS = "filtrationErrors";
  private static final String FILTRATION_ERRORS_COLUMN = "filtration_errors";
  private static final String JOURNEY_AVAILABLE_ALIAS = "journeyAvailable";
  private static final String MISSING_ATTEMPTS_ALIAS = "missingAttempts";
  private static final String MISSING_ATTEMPTS_COLUMN = "missing_attempts";
  private static final String RECONCILIATION_IMBALANCE_ALIAS = "reconciliationImbalance";
  private static final String RECONCILIATION_IMBALANCE_COLUMN = "reconciliation_imbalance";
  private static final String REPORT_GROUP_ID_ALIAS = "reportGroupId";
  private static final String REPORT_GROUP_NAME_ALIAS = "reportGroupName";
  private static final String REPORT_GROUP_NAME_COLUMN = "rpt_grp_name";
  private static final String REPORTING_PERIOD_FROM_ALIAS = "reportingPeriodFrom";
  private static final String REPORTING_PERIOD_TO_ALIAS = "reportingPeriodTo";
  private static final String SEQUENCE_NUMBER_ALIAS = "sequenceNumber";
  private static final String SIMULATED_TRANSACTIONS_ALIAS = "simulatedTransactions";
  private static final String SIMULATED_TRANSACTIONS_COLUMN = "simulated_transactions";
  private static final String SOFT_DEDUP_TRANSACTIONS_ALIAS = "softDedupTransactions";
  private static final String SOFT_DEDUP_TRANSACTIONS_COLUMN = "soft_dedup_transactions";
  private static final String STARTED_AT_ALIAS = "startedAt";
  private static final String STARTED_AT_COLUMN = "started_at";
  private static final String STATUS_BUCKET_COLUMN = "status_bucket";
  private static final String STATUS_NOT_YET_REPORTED = "NOT_YET_REPORTED";
  private static final String STATUS_RECONCILED = "RECONCILED";
  private static final String TOTAL_ISSUES_COLUMN = "total_issues";
  private static final String TRANSFORMATION_FAILURES_ALIAS = "transformationFailures";
  private static final String TRANSFORMATION_FAILURES_COLUMN = "transformation_failures";
  private static final String TRANSFORMER_OUTPUT_ALIAS = "transformerOutput";
  private static final String TRANSFORMER_OUTPUT_COLUMN = "transformer_output";
  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation RECONCILIATION =
      REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  private static final com.pharos.compliance.jooq.tables.ReportGroupConfig CONFIG = REPORT_GROUP_CONFIG;
  private final DSLContext dsl;

  public BatchExplorerRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The original SQL's {@code metricFocus} sort keys are {@code CASE WHEN :metricFocus = 'X' THEN
   * col END DESC NULLS LAST} -- when the focus doesn't match, that CASE evaluates to NULL for every
   * row, so the key ties every row and has no effect on ordering. Since {@code metricFocus} is
   * already known in Java before this query is built, the equivalent (and simpler) approach is to
   * only add that sort key when it actually applies, rather than emit a same-value-for-every-row
   * placeholder -- Postgres treats a bare integer literal in ORDER BY as a column-position
   * reference, not a constant, so a literal placeholder isn't even a safe way to do this.
   */
  private static List<org.jooq.OrderField<?>> batchQueueSortKeys(String metricFocus, Field<Long> transformerOutput,
      Field<Long> excludedTransactions, Field<String> statusBucket, Field<java.time.OffsetDateTime> completedAt,
      Field<java.time.OffsetDateTime> startedAt, Field<String> batchId) {
    List<org.jooq.OrderField<?>> keys = new java.util.ArrayList<>();
    if ("REPORTED".equals(metricFocus)) {
      keys.add(transformerOutput.desc().nullsLast());
    }
    if ("EXCLUDED".equals(metricFocus)) {
      keys.add(excludedTransactions.desc().nullsLast());
    }
    keys.add(DSL.when(statusBucket.eq(STATUS_NOT_YET_REPORTED), 1).otherwise(0));
    keys.add(completedAt.desc().nullsLast());
    keys.add(startedAt.desc().nullsLast());
    keys.add(batchId.asc());
    return keys;
  }

  /**
   * Every batch this class reports on is either (a) a batch with a reconciliation record -- {@code
   * report_transformation_reconciliation} -- possibly with issues, or (b) a batch that has only
   * journey activity so far and no reconciliation record yet ("not yet reported"). This builds the
   * reconciled side: the latest reconciliation rows in scope, with the three issue counts and a
   * combined {@code total_issues}.
   */
  private org.jooq.Table<?> enrichedBatchMetrics(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var batchMetrics = dsl
      .select(RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO, RECONCILIATION.RPT_GRP_NAME,
          RECONCILIATION.RPT_FROM_DATE, RECONCILIATION.RPT_TO_DATE, RECONCILIATION.CREATED_TIMESTAMP, RECONCILIATION.MODIFIED_TIMESTAMP,
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as(TRANSFORMATION_FAILURES_COLUMN),
          DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).cast(SQLDataType.BIGINT).as(MISSING_ATTEMPTS_COLUMN),
          DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).cast(SQLDataType.BIGINT).as(ACTIVITY_MISSING_COLUMN),
          DSL
            .abs(DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0).sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
            .cast(SQLDataType.BIGINT)
            .as(FILTRATION_ERRORS_COLUMN),
          DSL
            .abs(DSL
              .coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
              .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
            .cast(SQLDataType.BIGINT)
            .as(RECONCILIATION_IMBALANCE_COLUMN),
          DSL
            .coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
            .cast(SQLDataType.BIGINT)
            .as("expected_transformation_attempts"),
          DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as(
              "actual_transformation_attempts"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0).cast(SQLDataType.BIGINT).as("transformed_activities"),
          DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as(TRANSFORMER_OUTPUT_COLUMN),
          DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as(EXCLUDED_TRANSACTIONS_COLUMN),
          DSL.coalesce(RECONCILIATION.DUPLICATE_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as(DUPLICATE_TRANSACTIONS_COLUMN),
          DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as(SIMULATED_TRANSACTIONS_COLUMN),
          DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0).cast(SQLDataType.BIGINT).as(SOFT_DEDUP_TRANSACTIONS_COLUMN))
      .from(RECONCILIATION)
      .where(RECONCILIATION.CREATED_TIMESTAMP.ge(fromTimestamp))
      .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
      .and(containsIgnoreCase(RECONCILIATION.BATCH_ID, batchId))
      .and(reportGroupId == null ? DSL.trueCondition() : RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
      .asTable("batch_metrics");

    Field<Integer> bmRptGrpId = requiredField(batchMetrics, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<Long> bmTransformationFailures = requiredField(batchMetrics, TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Long> bmMissingAttempts = requiredField(batchMetrics, MISSING_ATTEMPTS_COLUMN, Long.class);
    Field<Long> bmActivityMissing = requiredField(batchMetrics, ACTIVITY_MISSING_COLUMN, Long.class);

    return dsl
      .select(batchMetrics.fields())
      .select(bmTransformationFailures.add(bmMissingAttempts).add(bmActivityMissing).as(TOTAL_ISSUES_COLUMN))
      .from(batchMetrics)
      .where(filterByCountry ? bmRptGrpId.in(reportGroupIds) : DSL.trueCondition())
      .asTable("enriched_batch_metrics");
  }

  /**
   * Journey activity with no reconciliation record yet -- "not yet reported" batches.
   */
  private org.jooq.Table<?> notYetReportedBatches(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var config = CONFIG;
    Field<String> latestActiveReportGroupName = DSL.field(dsl
      .select(config.RPT_GRP_NAME)
      .from(config)
      .where(config.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
      .and(config.RPT_CONFIG_ACTIVE_FLAG.eq(true))
      .orderBy(config.MODIFIED_TIMESTAMP.desc().nullsLast())
      .limit(1));

    return dsl
      .select(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID, latestActiveReportGroupName.as(REPORT_GROUP_NAME_COLUMN),
          DSL.min(JOURNEY.CREATED_TIMESTAMP).as(STARTED_AT_COLUMN), DSL.max(JOURNEY.MODIFIED_TIMESTAMP).as("last_activity_at"),
          DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as(DISCOVERED_TRANSACTIONS_COLUMN))
      .from(JOURNEY)
      .where(zonelessTimestampBetween(JOURNEY.CREATED_TIMESTAMP, fromTimestamp, toTimestampExclusive))
      .and(containsIgnoreCase(JOURNEY.BATCH_ID, batchId))
      .and(reportGroupId == null ? DSL.trueCondition() : JOURNEY.RPT_GRP_ID.eq(reportGroupId))
      .and(filterByCountry ? JOURNEY.RPT_GRP_ID.in(reportGroupIds) : DSL.trueCondition())
      .and(DSL.notExists(dsl
        .selectOne()
        .from(RECONCILIATION)
        .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
        .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
      .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
      .asTable("not_yet_reported_batches");
  }

  @SqlQueryPurpose("Summarize batches matching the Batch Explorer filters")
  public BatchSummaryProjection getBatchSummary(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var enriched = enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    var notYetReported = notYetReportedBatches(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Long> totalIssues = requiredField(enriched, TOTAL_ISSUES_COLUMN, Long.class);
    Field<String> enrichedRptGrpName = requiredField(enriched, RECONCILIATION.RPT_GRP_NAME.getName(), String.class);
    Field<String> notYetReportedRptGrpName = requiredField(notYetReported, REPORT_GROUP_NAME_COLUMN, String.class);

    var enrichedSummary = dsl
      .select(DSL.count().cast(SQLDataType.BIGINT).as("all_enriched"),
          DSL.count().filterWhere(totalIssues.eq(0L)).cast(SQLDataType.BIGINT).as("successful"),
          DSL.count().filterWhere(totalIssues.gt(0L)).cast(SQLDataType.BIGINT).as("attention"),
          DSL.max(enrichedRptGrpName).as("enriched_report_group_name"))
      .from(enriched)
      .asTable("enriched_summary");
    var notYetReportedSummary = dsl
      .select(DSL.count().cast(SQLDataType.BIGINT).as("not_yet_reported"),
          DSL.max(notYetReportedRptGrpName).as("not_yet_reported_report_group_name"))
      .from(notYetReported)
      .asTable("not_yet_reported_summary");

    Field<Long> allEnriched = requiredField(enrichedSummary, "all_enriched", Long.class);
    Field<Long> allNotYetReported = requiredField(notYetReportedSummary, "not_yet_reported", Long.class);
    Field<Long> successful = requiredField(enrichedSummary, "successful", Long.class);
    Field<Long> attention = requiredField(enrichedSummary, "attention", Long.class);
    Field<String> reportGroupName = DSL.coalesce(requiredField(enrichedSummary, "enriched_report_group_name", String.class),
        requiredField(notYetReportedSummary, "not_yet_reported_report_group_name", String.class));

    return dsl
      .select(allEnriched.add(allNotYetReported).as("all_batches"), successful, attention, allNotYetReported,
          reportGroupName.as("report_group_name"))
      .from(enrichedSummary)
      .crossJoin(notYetReportedSummary)
      .fetchOptional(record -> new BatchSummaryProjection(requiredLong(record, "all_batches"), requiredLong(record, successful),
          requiredLong(record, attention), requiredLong(record, allNotYetReported), record.get("report_group_name", String.class)))
      .orElseThrow(() -> new IllegalStateException("Batch summary aggregate returned no row"));
  }

  @SqlQueryPurpose("Load the paginated batch investigation queue")
  public List<BatchQueueProjection> getBatchQueue(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds, String status, String issueType, String metricFocus,
      int size, long offset) {
    var enriched = enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    var notYetReported = notYetReportedBatches(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Integer> eRptGrpId = requiredField(enriched, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> eBatchId = requiredField(enriched, RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Integer> eSeqNo = requiredField(enriched, RECONCILIATION.SEQ_NO.getName(), Integer.class);
    Field<String> eRptGrpName = requiredField(enriched, RECONCILIATION.RPT_GRP_NAME.getName(), String.class);
    Field<String> eFromDate = requiredField(enriched, RECONCILIATION.RPT_FROM_DATE.getName(), String.class);
    Field<String> eToDate = requiredField(enriched, RECONCILIATION.RPT_TO_DATE.getName(), String.class);
    Field<LocalDateTime> eCreated = requiredField(enriched, RECONCILIATION.CREATED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<LocalDateTime> eModified = requiredField(enriched, RECONCILIATION.MODIFIED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<Long> eTransformationFailures = requiredField(enriched, TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Long> eMissingAttempts = requiredField(enriched, MISSING_ATTEMPTS_COLUMN, Long.class);
    Field<Long> eActivityMissing = requiredField(enriched, ACTIVITY_MISSING_COLUMN, Long.class);
    Field<Long> eFiltrationErrors = requiredField(enriched, FILTRATION_ERRORS_COLUMN, Long.class);
    Field<Long> eReconciliationImbalance = requiredField(enriched, RECONCILIATION_IMBALANCE_COLUMN, Long.class);
    Field<Long> eTransformerOutput = requiredField(enriched, TRANSFORMER_OUTPUT_COLUMN, Long.class);
    Field<Long> eExcludedTransactions = requiredField(enriched, EXCLUDED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> eDuplicateTransactions = requiredField(enriched, DUPLICATE_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> eSimulatedTransactions = requiredField(enriched, SIMULATED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> eSoftDedupTransactions = requiredField(enriched, SOFT_DEDUP_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> eTotalIssues = requiredField(enriched, TOTAL_ISSUES_COLUMN, Long.class);

    Field<Integer> nRptGrpId = requiredField(notYetReported, JOURNEY.RPT_GRP_ID.getName(), Integer.class);
    Field<String> nBatchId = requiredField(notYetReported, JOURNEY.BATCH_ID.getName(), String.class);
    Field<String> nRptGrpName = requiredField(notYetReported, REPORT_GROUP_NAME_COLUMN, String.class);
    Field<LocalDateTime> nStartedAt = requiredField(notYetReported, STARTED_AT_COLUMN, LocalDateTime.class);
    Field<Long> nDiscovered = requiredField(notYetReported, DISCOVERED_TRANSACTIONS_COLUMN, Long.class);

    var reconciledBranch = dsl
      .select(eRptGrpId, eBatchId, eSeqNo, eRptGrpName, eFromDate, eToDate,
          eCreated.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as(STARTED_AT_COLUMN),
          eModified.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as(COMPLETED_AT_COLUMN), eTransformationFailures, eMissingAttempts,
          eActivityMissing, eFiltrationErrors, eReconciliationImbalance, eTransformerOutput, eExcludedTransactions, eDuplicateTransactions,
          eSimulatedTransactions, eSoftDedupTransactions, eTotalIssues, DSL.inline(0L).as(DISCOVERED_TRANSACTIONS_COLUMN),
          DSL.inline(STATUS_RECONCILED).as(STATUS_BUCKET_COLUMN))
      .from(enriched);

    var notYetReportedBranch = dsl
      .select(nRptGrpId, nBatchId, DSL.inline(0).as("seq_no"), nRptGrpName, DSL.cast(null, SQLDataType.CLOB).as("rpt_from_date"),
          DSL.cast(null, SQLDataType.CLOB).as("rpt_to_date"), nStartedAt.cast(SQLDataType.TIMESTAMPWITHTIMEZONE).as(STARTED_AT_COLUMN),
          DSL.cast(null, SQLDataType.TIMESTAMPWITHTIMEZONE).as(COMPLETED_AT_COLUMN), DSL.inline(0L).as(TRANSFORMATION_FAILURES_COLUMN),
          DSL.inline(0L).as(MISSING_ATTEMPTS_COLUMN), DSL.inline(0L).as(ACTIVITY_MISSING_COLUMN),
          DSL.inline(0L).as(FILTRATION_ERRORS_COLUMN), DSL.inline(0L).as(RECONCILIATION_IMBALANCE_COLUMN),
          DSL.inline(0L).as(TRANSFORMER_OUTPUT_COLUMN), DSL.inline(0L).as(EXCLUDED_TRANSACTIONS_COLUMN),
          DSL.inline(0L).as(DUPLICATE_TRANSACTIONS_COLUMN), DSL.inline(0L).as(SIMULATED_TRANSACTIONS_COLUMN),
          DSL.inline(0L).as(SOFT_DEDUP_TRANSACTIONS_COLUMN), DSL.inline(0L).as(TOTAL_ISSUES_COLUMN), nDiscovered,
          DSL.inline(STATUS_NOT_YET_REPORTED).as(STATUS_BUCKET_COLUMN))
      .from(notYetReported);

    var combinedQueue = reconciledBranch.unionAll(notYetReportedBranch).asTable("combined_queue");

    Field<Integer> cRptGrpId = requiredField(combinedQueue, "rpt_grp_id", Integer.class);
    Field<String> cRptGrpName = requiredField(combinedQueue, REPORT_GROUP_NAME_COLUMN, String.class);
    Field<String> cBatchId = requiredField(combinedQueue, "batch_id", String.class);
    Field<Integer> cSeqNo = requiredField(combinedQueue, "seq_no", Integer.class);
    Field<String> cFromDate = requiredField(combinedQueue, "rpt_from_date", String.class);
    Field<String> cToDate = requiredField(combinedQueue, "rpt_to_date", String.class);
    Field<java.time.OffsetDateTime> cStartedAt = requiredField(combinedQueue, STARTED_AT_COLUMN, java.time.OffsetDateTime.class);
    Field<java.time.OffsetDateTime> cCompletedAt = requiredField(combinedQueue, COMPLETED_AT_COLUMN, java.time.OffsetDateTime.class);
    Field<Long> cTransformationFailures = requiredField(combinedQueue, TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Long> cMissingAttempts = requiredField(combinedQueue, MISSING_ATTEMPTS_COLUMN, Long.class);
    Field<Long> cActivityMissing = requiredField(combinedQueue, ACTIVITY_MISSING_COLUMN, Long.class);
    Field<Long> cFiltrationErrors = requiredField(combinedQueue, FILTRATION_ERRORS_COLUMN, Long.class);
    Field<Long> cReconciliationImbalance = requiredField(combinedQueue, RECONCILIATION_IMBALANCE_COLUMN, Long.class);
    Field<Long> cTransformerOutput = requiredField(combinedQueue, TRANSFORMER_OUTPUT_COLUMN, Long.class);
    Field<Long> cExcludedTransactions = requiredField(combinedQueue, EXCLUDED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> cDuplicateTransactions = requiredField(combinedQueue, DUPLICATE_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> cSimulatedTransactions = requiredField(combinedQueue, SIMULATED_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> cSoftDedupTransactions = requiredField(combinedQueue, SOFT_DEDUP_TRANSACTIONS_COLUMN, Long.class);
    Field<Long> cTotalIssues = requiredField(combinedQueue, TOTAL_ISSUES_COLUMN, Long.class);
    Field<Long> cDiscoveredTransactions = requiredField(combinedQueue, DISCOVERED_TRANSACTIONS_COLUMN, Long.class);
    Field<String> cStatusBucket = requiredField(combinedQueue, STATUS_BUCKET_COLUMN, String.class);

    Condition statusCondition = switch (status) {
      case "ALL" -> DSL.trueCondition();
      case "SUCCESSFUL" -> cStatusBucket.eq(STATUS_RECONCILED).and(cTotalIssues.eq(0L));
      case "ATTENTION" -> cStatusBucket.eq(STATUS_RECONCILED).and(cTotalIssues.gt(0L));
      case STATUS_NOT_YET_REPORTED -> cStatusBucket.eq(STATUS_NOT_YET_REPORTED);
      default -> DSL.falseCondition();
    };
    Condition reconciledOnly = cStatusBucket.eq(STATUS_RECONCILED);
    Condition issueTypeCondition = switch (issueType) {
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
    Condition metricFocusCondition = switch (metricFocus) {
      case "DEFAULT" -> DSL.trueCondition();
      case "REPORTED" -> reconciledOnly.and(cTransformerOutput.gt(0L));
      case "EXCLUDED" -> reconciledOnly.and(cExcludedTransactions.gt(0L));
      default -> DSL.falseCondition();
    };

    var matchingCount = DSL.count().over().as("matchingCount");

    return dsl
      .select(cRptGrpId.as(REPORT_GROUP_ID_ALIAS), cRptGrpName.as(REPORT_GROUP_NAME_ALIAS), cBatchId.as(BATCH_ID_ALIAS),
          cSeqNo.as(SEQUENCE_NUMBER_ALIAS), cFromDate.as(REPORTING_PERIOD_FROM_ALIAS), cToDate.as(REPORTING_PERIOD_TO_ALIAS),
          cStartedAt.as(STARTED_AT_ALIAS), cCompletedAt.as(COMPLETED_AT_ALIAS), cTransformationFailures.as(TRANSFORMATION_FAILURES_ALIAS),
          cMissingAttempts.as(MISSING_ATTEMPTS_ALIAS), cActivityMissing.as(ACTIVITY_MISSING_ALIAS),
          cFiltrationErrors.as(FILTRATION_ERRORS_ALIAS), cReconciliationImbalance.as(RECONCILIATION_IMBALANCE_ALIAS),
          cTransformerOutput.as(TRANSFORMER_OUTPUT_ALIAS), cExcludedTransactions.as(EXCLUDED_TRANSACTIONS_ALIAS),
          cDuplicateTransactions.as(DUPLICATE_TRANSACTIONS_ALIAS), cSimulatedTransactions.as(SIMULATED_TRANSACTIONS_ALIAS),
          cSoftDedupTransactions.as(SOFT_DEDUP_TRANSACTIONS_ALIAS), cTotalIssues.as("totalIssues"),
          cDiscoveredTransactions.as(DISCOVERED_TRANSACTIONS_ALIAS), cStatusBucket.as("statusBucket"), matchingCount)
      .from(combinedQueue)
      .where(statusCondition)
      .and(issueTypeCondition)
      .and(metricFocusCondition)
      .orderBy(
          batchQueueSortKeys(metricFocus, cTransformerOutput, cExcludedTransactions, cStatusBucket, cCompletedAt, cStartedAt, cBatchId))
      .limit(size)
      .offset(offset)
      .fetch(r -> new BatchQueueProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS), r.get(REPORT_GROUP_NAME_ALIAS, String.class),
          r.get(BATCH_ID_ALIAS, String.class), requiredInt(r, SEQUENCE_NUMBER_ALIAS), r.get(REPORTING_PERIOD_FROM_ALIAS, String.class),
          r.get(REPORTING_PERIOD_TO_ALIAS, String.class), toLocalDateTime(r.get(STARTED_AT_ALIAS, java.time.OffsetDateTime.class)),
          toLocalDateTime(r.get(COMPLETED_AT_ALIAS, java.time.OffsetDateTime.class)), requiredLong(r, TRANSFORMATION_FAILURES_ALIAS),
          requiredLong(r, MISSING_ATTEMPTS_ALIAS), requiredLong(r, ACTIVITY_MISSING_ALIAS), requiredLong(r, FILTRATION_ERRORS_ALIAS),
          requiredLong(r, RECONCILIATION_IMBALANCE_ALIAS), requiredLong(r, TRANSFORMER_OUTPUT_ALIAS),
          requiredLong(r, EXCLUDED_TRANSACTIONS_ALIAS), requiredLong(r, DUPLICATE_TRANSACTIONS_ALIAS),
          requiredLong(r, SIMULATED_TRANSACTIONS_ALIAS), requiredLong(r, SOFT_DEDUP_TRANSACTIONS_ALIAS), requiredLong(r, "totalIssues"),
          requiredLong(r, DISCOVERED_TRANSACTIONS_ALIAS), r.get("statusBucket", String.class), requiredLong(r, "matchingCount")));
  }

  private static LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
    return value == null ? null : value.toLocalDateTime();
  }

  @SqlQueryPurpose("Load reconciliation and issue evidence for one reported batch")
  public Optional<BatchDetailsProjection> getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    return dsl
      .select(RECONCILIATION.RPT_GRP_ID.as(REPORT_GROUP_ID_ALIAS), RECONCILIATION.RPT_GRP_NAME.as(REPORT_GROUP_NAME_ALIAS),
          RECONCILIATION.BATCH_ID.as(BATCH_ID_ALIAS), RECONCILIATION.SEQ_NO.as(SEQUENCE_NUMBER_ALIAS),
          RECONCILIATION.RPT_FROM_DATE.as(REPORTING_PERIOD_FROM_ALIAS), RECONCILIATION.RPT_TO_DATE.as(REPORTING_PERIOD_TO_ALIAS),
          RECONCILIATION.CREATED_TIMESTAMP.as(STARTED_AT_ALIAS), RECONCILIATION.MODIFIED_TIMESTAMP.as(COMPLETED_AT_ALIAS),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as(TRANSFORMATION_FAILURES_ALIAS),
          DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).cast(SQLDataType.BIGINT).as(MISSING_ATTEMPTS_ALIAS),
          DSL.coalesce(RECONCILIATION.ACTIVITY_MISSING, 0).cast(SQLDataType.BIGINT).as(ACTIVITY_MISSING_ALIAS),
          DSL.coalesce(RECONCILIATION.DUPLICATE_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as(DUPLICATE_TRANSACTIONS_ALIAS),
          DSL
            .abs(DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0).sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
            .cast(SQLDataType.BIGINT)
            .as(FILTRATION_ERRORS_ALIAS),
          DSL
            .abs(DSL
              .coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
              .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
            .cast(SQLDataType.BIGINT)
            .as(RECONCILIATION_IMBALANCE_ALIAS),
          DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).cast(SQLDataType.BIGINT).as("selectedTransactions"),
          DSL
            .greatest(DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).sub(DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)),
                DSL.inline(0))
            .cast(SQLDataType.BIGINT)
            .as("transactionAttemptsFound"),
          DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("expectedReportableTransactions"),
          DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("actualReportableTransactions"),
          DSL
            .coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
            .cast(SQLDataType.BIGINT)
            .as("expectedTransformationAttempts"),
          DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0).cast(SQLDataType.BIGINT).as(
              "actualTransformationAttempts"),
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0).cast(SQLDataType.BIGINT).as("transformedActivities"),
          DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as(TRANSFORMER_OUTPUT_ALIAS),
          DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as(EXCLUDED_TRANSACTIONS_ALIAS),
          DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as(SIMULATED_TRANSACTIONS_ALIAS),
          DSL.coalesce(RECONCILIATION.ALREADY_REPORTED_COUNT, 0).cast(SQLDataType.BIGINT).as("alreadyReportedTransactions"),
          DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0).cast(SQLDataType.BIGINT).as(SOFT_DEDUP_TRANSACTIONS_ALIAS),
          DSL
            .exists(dsl
              .selectOne()
              .from(JOURNEY)
              .where(JOURNEY.RPT_GRP_ID.eq(RECONCILIATION.RPT_GRP_ID))
              .and(JOURNEY.BATCH_ID.eq(RECONCILIATION.BATCH_ID)))
            .as(JOURNEY_AVAILABLE_ALIAS),
          DSL
            .exists(dsl
              .selectOne()
              .from(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT)
              .where(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_ID.eq(
                  RECONCILIATION.RPT_GRP_ID))
              .and(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT.PROCESSING_BATCH_ID.eq(
                  RECONCILIATION.BATCH_ID)))
            .as(EXCLUSIONS_AVAILABLE_ALIAS))
      .from(RECONCILIATION)
      .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
      .and(RECONCILIATION.BATCH_ID.eq(batchId))
      .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
      .fetchOptional(r -> new BatchDetailsProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS), r.get(REPORT_GROUP_NAME_ALIAS, String.class),
          r.get(BATCH_ID_ALIAS, String.class), requiredInt(r, SEQUENCE_NUMBER_ALIAS), r.get(REPORTING_PERIOD_FROM_ALIAS, String.class),
          r.get(REPORTING_PERIOD_TO_ALIAS, String.class), r.get(STARTED_AT_ALIAS, LocalDateTime.class),
          r.get(COMPLETED_AT_ALIAS, LocalDateTime.class), requiredLong(r, TRANSFORMATION_FAILURES_ALIAS),
          requiredLong(r, MISSING_ATTEMPTS_ALIAS), requiredLong(r, ACTIVITY_MISSING_ALIAS), requiredLong(r, DUPLICATE_TRANSACTIONS_ALIAS),
          requiredLong(r, FILTRATION_ERRORS_ALIAS), requiredLong(r, RECONCILIATION_IMBALANCE_ALIAS), requiredLong(r, "selectedTransactions"),
          requiredLong(r, "transactionAttemptsFound"), requiredLong(r, "expectedReportableTransactions"),
          requiredLong(r, "actualReportableTransactions"), requiredLong(r, "expectedTransformationAttempts"),
          requiredLong(r, "actualTransformationAttempts"), requiredLong(r, "transformedActivities"),
          requiredLong(r, TRANSFORMER_OUTPUT_ALIAS), requiredLong(r, EXCLUDED_TRANSACTIONS_ALIAS),
          requiredLong(r, SIMULATED_TRANSACTIONS_ALIAS), requiredLong(r, "alreadyReportedTransactions"),
          requiredLong(r, SOFT_DEDUP_TRANSACTIONS_ALIAS), requiredBoolean(r, JOURNEY_AVAILABLE_ALIAS),
          requiredBoolean(r, EXCLUSIONS_AVAILABLE_ALIAS)));
  }

  @SqlQueryPurpose("Load latest-state journey evidence for one not-yet-reported batch")
  public Optional<NotYetReportedBatchDetailsProjection> getNotYetReportedBatchDetails(int reportGroupId, String batchId) {
    Field<String> latestActiveReportGroupName = DSL.field(dsl
      .select(CONFIG.RPT_GRP_NAME)
      .from(CONFIG)
      .where(CONFIG.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
      .and(CONFIG.RPT_CONFIG_ACTIVE_FLAG.eq(true))
      .orderBy(CONFIG.MODIFIED_TIMESTAMP.desc().nullsLast())
      .limit(1));

    var exclusionAudit = com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT;

    Condition stalledCondition = DSL.upper(DSL.coalesce(JOURNEY.STATUS, "")).eq("ERROR").and(JOURNEY.PROCESSING_COMPLETE.isTrue());

    return dsl
      .select(JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID_ALIAS), latestActiveReportGroupName.as(REPORT_GROUP_NAME_ALIAS),
          JOURNEY.BATCH_ID.as(BATCH_ID_ALIAS), DSL.min(JOURNEY.CREATED_TIMESTAMP).as(STARTED_AT_ALIAS),
          DSL.max(JOURNEY.MODIFIED_TIMESTAMP).as("lastActivityAt"),
          DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as(DISCOVERED_TRANSACTIONS_ALIAS),
          DSL.countDistinct(JOURNEY.IDENTIFIER).filterWhere(stalledCondition).cast(SQLDataType.BIGINT).as("stalledTransactions"),
          DSL.boolOr(DSL.inline(true)).as(JOURNEY_AVAILABLE_ALIAS),
          DSL
            .exists(dsl
              .selectOne()
              .from(exclusionAudit)
              .where(exclusionAudit.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
              .and(exclusionAudit.PROCESSING_BATCH_ID.eq(JOURNEY.BATCH_ID)))
            .as(EXCLUSIONS_AVAILABLE_ALIAS))
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(reportGroupId))
      .and(JOURNEY.BATCH_ID.eq(batchId))
      .and(DSL.notExists(dsl
        .selectOne()
        .from(RECONCILIATION)
        .where(RECONCILIATION.RPT_GRP_ID.eq(JOURNEY.RPT_GRP_ID))
        .and(RECONCILIATION.BATCH_ID.eq(JOURNEY.BATCH_ID))))
      .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
      .fetchOptional(r -> new NotYetReportedBatchDetailsProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS),
          r.get(REPORT_GROUP_NAME_ALIAS, String.class), r.get(BATCH_ID_ALIAS, String.class),
          toLocalDateTime(r.get(STARTED_AT_ALIAS, java.time.OffsetDateTime.class)),
          toLocalDateTime(r.get("lastActivityAt", java.time.OffsetDateTime.class)), requiredLong(r, DISCOVERED_TRANSACTIONS_ALIAS),
          requiredLong(r, "stalledTransactions"), requiredBoolean(r, JOURNEY_AVAILABLE_ALIAS),
          requiredBoolean(r, EXCLUSIONS_AVAILABLE_ALIAS)));
  }
}
