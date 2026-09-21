package com.pharos.compliance.batch.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.batch.repository.projection.BatchSummaryProjection;
import com.pharos.compliance.batch.repository.projection.BatchQueueProjection;
import com.pharos.compliance.batch.repository.projection.BatchDetailsProjection;
import com.pharos.compliance.common.jooq.TransformationFailureQueries;
import static com.pharos.compliance.common.jooq.JooqConditions.containsIgnoreCase;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredBoolean;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportBatchInfo.REPORT_BATCH_INFO;
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
  private static final String REPORTED_TRANSFORMATION_FAILURES_ALIAS = "reportedTransformationFailures";
  private static final String REPORTED_TRANSFORMATION_FAILURES_COLUMN = "reported_transformation_failures";
  private static final String TRANSFORMATION_FAILURE_MISMATCH_ALIAS = "transformationFailureMismatch";
  private static final String TRANSFORMATION_FAILURE_MISMATCH_COLUMN = "transformation_failure_mismatch";
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
  private static final String TOTAL_ISSUES_COLUMN = "total_issues";
  private static final String TRANSFORMATION_FAILURES_ALIAS = "transformationFailures";
  private static final String TRANSFORMATION_FAILURES_COLUMN = "transformation_failures";
  private static final String TRANSFORMER_OUTPUT_ALIAS = "transformerOutput";
  private static final String TRANSFORMER_OUTPUT_COLUMN = "transformer_output";
  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation RECONCILIATION =
      REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  private static final com.pharos.compliance.jooq.tables.ReportBatchInfo BATCH_INFO = REPORT_BATCH_INFO;
  private static final String REPORT_SELECTION_VERSION_ID_ALIAS = "reportSelectionVersionId";
  private static final String TRANSFORMER_VERSION_ID_ALIAS = "transformerVersionId";
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
      Field<Long> excludedTransactions, Field<LocalDateTime> completedAt, Field<LocalDateTime> startedAt, Field<String> batchId) {
    List<org.jooq.OrderField<?>> keys = new java.util.ArrayList<>();
    if ("REPORTED".equals(metricFocus)) {
      keys.add(transformerOutput.desc().nullsLast());
    }
    if ("EXCLUDED".equals(metricFocus)) {
      keys.add(excludedTransactions.desc().nullsLast());
    }
    keys.add(completedAt.desc().nullsLast());
    keys.add(startedAt.desc().nullsLast());
    keys.add(batchId.asc());
    return keys;
  }

  /**
   * Phase 1 only cares about completed batches from a batch perspective -- batches with a
   * reconciliation record ({@code report_transformation_reconciliation}), possibly with issues.
   * The latest reconciliation rows in scope, with the three issue counts and a combined {@code
   * total_issues}.
   */
  private org.jooq.Table<?> enrichedBatchMetrics(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var batchMetrics = dsl
      .select(RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID, RECONCILIATION.SEQ_NO, RECONCILIATION.RPT_GRP_NAME,
          RECONCILIATION.RPT_FROM_DATE, RECONCILIATION.RPT_TO_DATE, RECONCILIATION.CREATED_TIMESTAMP, RECONCILIATION.MODIFIED_TIMESTAMP,
          DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as(REPORTED_TRANSFORMATION_FAILURES_COLUMN),
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
    Field<String> bmBatchId = requiredField(batchMetrics, RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Long> bmReportedTransformationFailures = requiredField(batchMetrics, REPORTED_TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Long> bmMissingAttempts = requiredField(batchMetrics, MISSING_ATTEMPTS_COLUMN, Long.class);
    Field<Long> bmActivityMissing = requiredField(batchMetrics, ACTIVITY_MISSING_COLUMN, Long.class);

    // See TransformationFailureQueries' own Javadoc for why the reconciliation column and the
    // journey-derived count can disagree. The LEFT JOIN's own NULL (no TRANSFORMATION-stage
    // failure rows at all for this batch) is exactly the "no journey evidence for this fact"
    // signal, so COALESCE-ing straight onto it needs no separate journeyAvailable flag here,
    // unlike the single-batch getBatchDetails query below where a correlated COUNT can't produce
    // that same NULL.
    var journeyFailures = TransformationFailureQueries.journeyFailuresByBatch(dsl);
    Field<Integer> jfRptGrpId = requiredField(journeyFailures, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> jfBatchId = requiredField(journeyFailures, RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Long> jfCount = requiredField(journeyFailures, TransformationFailureQueries.JOURNEY_TRANSFORMATION_FAILURES_COLUMN, Long.class);

    // Deliberately unaliased -- reused in TOTAL_ISSUES_COLUMN below; a jOOQ Field that already
    // carries .as(...) renders as a bare alias reference (not its original expression) the second
    // time it's used within the same SELECT list, which Postgres rejects.
    Field<Long> transformationFailuresRaw = DSL.coalesce(jfCount, bmReportedTransformationFailures);
    Field<Boolean> transformationFailureMismatch =
        jfCount.isNotNull().and(jfCount.ne(bmReportedTransformationFailures)).as(TRANSFORMATION_FAILURE_MISMATCH_COLUMN);

    return dsl
      .select(batchMetrics.fields())
      .select(transformationFailuresRaw.as(TRANSFORMATION_FAILURES_COLUMN), transformationFailureMismatch)
      .select(transformationFailuresRaw.add(bmMissingAttempts).add(bmActivityMissing).as(TOTAL_ISSUES_COLUMN))
      .from(batchMetrics)
      .leftJoin(journeyFailures)
      .on(jfRptGrpId.eq(bmRptGrpId))
      .and(jfBatchId.eq(bmBatchId))
      .where(filterByCountry ? bmRptGrpId.in(reportGroupIds) : DSL.trueCondition())
      .asTable("enriched_batch_metrics");
  }

  @SqlQueryPurpose("Summarize batches matching the Batch Explorer filters")
  public BatchSummaryProjection getBatchSummary(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var enriched = enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Long> totalIssues = requiredField(enriched, TOTAL_ISSUES_COLUMN, Long.class);
    Field<String> enrichedRptGrpName = requiredField(enriched, RECONCILIATION.RPT_GRP_NAME.getName(), String.class);

    return dsl
      .select(DSL.count().cast(SQLDataType.BIGINT).as("all_batches"),
          DSL.count().filterWhere(totalIssues.eq(0L)).cast(SQLDataType.BIGINT).as("successful"),
          DSL.count().filterWhere(totalIssues.gt(0L)).cast(SQLDataType.BIGINT).as("attention"), DSL.max(enrichedRptGrpName).as(
              "report_group_name"))
      .from(enriched)
      .fetchOptional(record -> new BatchSummaryProjection(requiredLong(record, "all_batches"), requiredLong(record, "successful"),
          requiredLong(record, "attention"), record.get("report_group_name", String.class)))
      .orElseThrow(() -> new IllegalStateException("Batch summary aggregate returned no row"));
  }

  @SqlQueryPurpose("Load the paginated batch investigation queue")
  public List<BatchQueueProjection> getBatchQueue(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds, String status, String issueType, String metricFocus,
      int size, long offset) {
    var enriched = enrichedBatchMetrics(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    Field<Integer> eRptGrpId = requiredField(enriched, RECONCILIATION.RPT_GRP_ID.getName(), Integer.class);
    Field<String> eBatchId = requiredField(enriched, RECONCILIATION.BATCH_ID.getName(), String.class);
    Field<Integer> eSeqNo = requiredField(enriched, RECONCILIATION.SEQ_NO.getName(), Integer.class);
    Field<String> eRptGrpName = requiredField(enriched, RECONCILIATION.RPT_GRP_NAME.getName(), String.class);
    Field<String> eFromDate = requiredField(enriched, RECONCILIATION.RPT_FROM_DATE.getName(), String.class);
    Field<String> eToDate = requiredField(enriched, RECONCILIATION.RPT_TO_DATE.getName(), String.class);
    Field<LocalDateTime> eCreated = requiredField(enriched, RECONCILIATION.CREATED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<LocalDateTime> eModified = requiredField(enriched, RECONCILIATION.MODIFIED_TIMESTAMP.getName(), LocalDateTime.class);
    Field<Long> eTransformationFailures = requiredField(enriched, TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Long> eReportedTransformationFailures = requiredField(enriched, REPORTED_TRANSFORMATION_FAILURES_COLUMN, Long.class);
    Field<Boolean> eTransformationFailureMismatch = requiredField(enriched, TRANSFORMATION_FAILURE_MISMATCH_COLUMN, Boolean.class);
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

    Condition statusCondition = switch (status) {
      case "ALL" -> DSL.trueCondition();
      case "SUCCESSFUL" -> eTotalIssues.eq(0L);
      case "ATTENTION" -> eTotalIssues.gt(0L);
      default -> DSL.falseCondition();
    };
    Condition issueTypeCondition = switch (issueType) {
      case "ALL" -> DSL.trueCondition();
      case "ACTIVITY_MISSING" -> eActivityMissing.gt(0L);
      case "MISSING_ATTEMPTS" -> eMissingAttempts.gt(0L);
      case "TRANSFORMATION" -> eTransformationFailures.gt(0L);
      case "DUPLICATE_TRANSFORMATION" -> eDuplicateTransactions.gt(0L);
      case "EXCLUSION" -> eExcludedTransactions.gt(0L);
      case "SIMULATED" -> eSimulatedTransactions.gt(0L);
      case "SOFT_DEDUP" -> eSoftDedupTransactions.gt(0L);
      default -> DSL.falseCondition();
    };
    Condition metricFocusCondition = switch (metricFocus) {
      case "DEFAULT" -> DSL.trueCondition();
      case "REPORTED" -> eTransformerOutput.gt(0L);
      case "EXCLUDED" -> eExcludedTransactions.gt(0L);
      default -> DSL.falseCondition();
    };

    var matchingCount = DSL.count().over().as("matchingCount");

    return dsl
      .select(eRptGrpId.as(REPORT_GROUP_ID_ALIAS), eRptGrpName.as(REPORT_GROUP_NAME_ALIAS), eBatchId.as(BATCH_ID_ALIAS),
          eSeqNo.as(SEQUENCE_NUMBER_ALIAS), eFromDate.as(REPORTING_PERIOD_FROM_ALIAS), eToDate.as(REPORTING_PERIOD_TO_ALIAS),
          eCreated.as(STARTED_AT_ALIAS), eModified.as(COMPLETED_AT_ALIAS), eTransformationFailures.as(TRANSFORMATION_FAILURES_ALIAS),
          eReportedTransformationFailures.as(REPORTED_TRANSFORMATION_FAILURES_ALIAS),
          eTransformationFailureMismatch.as(TRANSFORMATION_FAILURE_MISMATCH_ALIAS), eMissingAttempts.as(MISSING_ATTEMPTS_ALIAS),
          eActivityMissing.as(ACTIVITY_MISSING_ALIAS),
          eFiltrationErrors.as(FILTRATION_ERRORS_ALIAS), eReconciliationImbalance.as(RECONCILIATION_IMBALANCE_ALIAS),
          eTransformerOutput.as(TRANSFORMER_OUTPUT_ALIAS), eExcludedTransactions.as(EXCLUDED_TRANSACTIONS_ALIAS),
          eDuplicateTransactions.as(DUPLICATE_TRANSACTIONS_ALIAS), eSimulatedTransactions.as(SIMULATED_TRANSACTIONS_ALIAS),
          eSoftDedupTransactions.as(SOFT_DEDUP_TRANSACTIONS_ALIAS), eTotalIssues.as("totalIssues"), matchingCount)
      .from(enriched)
      .where(statusCondition)
      .and(issueTypeCondition)
      .and(metricFocusCondition)
      .orderBy(batchQueueSortKeys(metricFocus, eTransformerOutput, eExcludedTransactions, eModified, eCreated, eBatchId))
      .limit(size)
      .offset(offset)
      .fetch(r -> new BatchQueueProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS), r.get(REPORT_GROUP_NAME_ALIAS, String.class),
          r.get(BATCH_ID_ALIAS, String.class), requiredInt(r, SEQUENCE_NUMBER_ALIAS), r.get(REPORTING_PERIOD_FROM_ALIAS, String.class),
          r.get(REPORTING_PERIOD_TO_ALIAS, String.class), r.get(STARTED_AT_ALIAS, LocalDateTime.class),
          r.get(COMPLETED_AT_ALIAS, LocalDateTime.class), requiredLong(r, TRANSFORMATION_FAILURES_ALIAS),
          requiredLong(r, REPORTED_TRANSFORMATION_FAILURES_ALIAS), requiredBoolean(r, TRANSFORMATION_FAILURE_MISMATCH_ALIAS),
          requiredLong(r, MISSING_ATTEMPTS_ALIAS), requiredLong(r, ACTIVITY_MISSING_ALIAS), requiredLong(r, FILTRATION_ERRORS_ALIAS),
          requiredLong(r, RECONCILIATION_IMBALANCE_ALIAS), requiredLong(r, TRANSFORMER_OUTPUT_ALIAS),
          requiredLong(r, EXCLUDED_TRANSACTIONS_ALIAS), requiredLong(r, DUPLICATE_TRANSACTIONS_ALIAS),
          requiredLong(r, SIMULATED_TRANSACTIONS_ALIAS), requiredLong(r, SOFT_DEDUP_TRANSACTIONS_ALIAS), requiredLong(r, "totalIssues"),
          requiredLong(r, "matchingCount")));
  }

  private static LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
    return value == null ? null : value.toLocalDateTime();
  }

  @SqlQueryPurpose("Selected batch > Data Selection, Data Transformation and Reconciliation cards > Load aggregate counters and evidence "
      + "availability")
  public Optional<BatchDetailsProjection> getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    // Reused for both the exposed journeyAvailable flag and to gate the corrected transformation
    // failure count below: a correlated COUNT (unlike the list view's LEFT JOIN/GROUP BY) always
    // returns a row, so 0 is ambiguous between "confirmed zero failures" and "no journey coverage
    // at all" -- this EXISTS check is what tells the two apart.
    Condition journeyAvailableCondition =
        DSL.exists(dsl.selectOne().from(JOURNEY).where(JOURNEY.RPT_GRP_ID.eq(RECONCILIATION.RPT_GRP_ID)).and(JOURNEY.BATCH_ID.eq(
            RECONCILIATION.BATCH_ID)));
    // Deliberately unaliased -- these get reused inside the CASE/comparison expressions below, and
    // a jOOQ Field that already carries .as(...) renders as a bare alias reference (not its
    // original expression) the second time it's used within the same SELECT list, which Postgres
    // rejects since a SELECT-list alias can't be referenced by another item in that same list.
    Field<Long> reportedTransformationFailuresRaw =
        DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT);
    Field<Long> journeyTransformationFailures =
        TransformationFailureQueries.correlatedJourneyFailureCount(dsl, RECONCILIATION.RPT_GRP_ID, RECONCILIATION.BATCH_ID);
    Field<Long> transformationFailures =
        DSL.when(journeyAvailableCondition, journeyTransformationFailures).otherwise(reportedTransformationFailuresRaw).as(
            TRANSFORMATION_FAILURES_ALIAS);
    Field<Boolean> transformationFailureMismatch =
        journeyAvailableCondition.and(journeyTransformationFailures.ne(reportedTransformationFailuresRaw)).as(
            TRANSFORMATION_FAILURE_MISMATCH_ALIAS);

    return dsl
      .select(RECONCILIATION.RPT_GRP_ID.as(REPORT_GROUP_ID_ALIAS), RECONCILIATION.RPT_GRP_NAME.as(REPORT_GROUP_NAME_ALIAS),
          RECONCILIATION.BATCH_ID.as(BATCH_ID_ALIAS), RECONCILIATION.SEQ_NO.as(SEQUENCE_NUMBER_ALIAS),
          RECONCILIATION.RPT_FROM_DATE.as(REPORTING_PERIOD_FROM_ALIAS), RECONCILIATION.RPT_TO_DATE.as(REPORTING_PERIOD_TO_ALIAS),
          RECONCILIATION.CREATED_TIMESTAMP.as(STARTED_AT_ALIAS), RECONCILIATION.MODIFIED_TIMESTAMP.as(COMPLETED_AT_ALIAS),
          transformationFailures, reportedTransformationFailuresRaw.as(REPORTED_TRANSFORMATION_FAILURES_ALIAS), transformationFailureMismatch,
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
          journeyAvailableCondition.as(JOURNEY_AVAILABLE_ALIAS),
          DSL
            .exists(dsl
              .selectOne()
              .from(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT)
              .where(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT.RPT_GRP_ID.eq(
                  RECONCILIATION.RPT_GRP_ID))
              .and(com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT.PROCESSING_BATCH_ID.eq(
                  RECONCILIATION.BATCH_ID)))
            .as(EXCLUSIONS_AVAILABLE_ALIAS), BATCH_INFO.SELECTION_VERSION.as(REPORT_SELECTION_VERSION_ID_ALIAS),
          BATCH_INFO.TRANSFORMER_MAPPING_VERSION.as(TRANSFORMER_VERSION_ID_ALIAS))
      .from(RECONCILIATION)
      // report_group_config version processed it (see the projection's own null-handling note).
      .leftJoin(BATCH_INFO)
      .on(BATCH_INFO.RPT_GRP_ID.eq(RECONCILIATION.RPT_GRP_ID))
      .and(BATCH_INFO.BATCH_ID.eq(RECONCILIATION.BATCH_ID))
      .and(BATCH_INFO.SEQ_NO.eq(RECONCILIATION.SEQ_NO))
      .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
      .and(RECONCILIATION.BATCH_ID.eq(batchId))
      .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
      .fetchOptional(r -> new BatchDetailsProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS), r.get(REPORT_GROUP_NAME_ALIAS, String.class),
          r.get(BATCH_ID_ALIAS, String.class), requiredInt(r, SEQUENCE_NUMBER_ALIAS), r.get(REPORTING_PERIOD_FROM_ALIAS, String.class),
          r.get(REPORTING_PERIOD_TO_ALIAS, String.class), r.get(STARTED_AT_ALIAS, LocalDateTime.class),
          r.get(COMPLETED_AT_ALIAS, LocalDateTime.class), requiredLong(r, TRANSFORMATION_FAILURES_ALIAS),
          requiredLong(r, REPORTED_TRANSFORMATION_FAILURES_ALIAS), requiredBoolean(r, TRANSFORMATION_FAILURE_MISMATCH_ALIAS),
          requiredLong(r, MISSING_ATTEMPTS_ALIAS), requiredLong(r, ACTIVITY_MISSING_ALIAS), requiredLong(r, DUPLICATE_TRANSACTIONS_ALIAS),
          requiredLong(r, FILTRATION_ERRORS_ALIAS), requiredLong(r, RECONCILIATION_IMBALANCE_ALIAS), requiredLong(r, "selectedTransactions"),
          requiredLong(r, "transactionAttemptsFound"), requiredLong(r, "expectedReportableTransactions"),
          requiredLong(r, "actualReportableTransactions"), requiredLong(r, "expectedTransformationAttempts"),
          requiredLong(r, "actualTransformationAttempts"), requiredLong(r, "transformedActivities"),
          requiredLong(r, TRANSFORMER_OUTPUT_ALIAS), requiredLong(r, EXCLUDED_TRANSACTIONS_ALIAS),
          requiredLong(r, SIMULATED_TRANSACTIONS_ALIAS), requiredLong(r, "alreadyReportedTransactions"),
          requiredLong(r, SOFT_DEDUP_TRANSACTIONS_ALIAS), requiredBoolean(r, JOURNEY_AVAILABLE_ALIAS),
          requiredBoolean(r, EXCLUSIONS_AVAILABLE_ALIAS), r.get(REPORT_SELECTION_VERSION_ID_ALIAS, Integer.class),
          r.get(TRANSFORMER_VERSION_ID_ALIAS, String.class)));
  }
}
