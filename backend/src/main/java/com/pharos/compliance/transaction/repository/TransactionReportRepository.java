package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import static com.pharos.compliance.common.jooq.JooqConditions.containsIgnoreCase;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.RegReportableActivity.REG_REPORTABLE_ACTIVITY;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;
import static com.pharos.compliance.jooq.tables.RuleHit.RULE_HIT;
import static com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every "transaction evidence" row here is a merge across three possible sources for the same
 * underlying transaction: JOURNEY (record_transformation_journey), EXCLUSION_AUDIT
 * (rule_hit_exclusion_audit), and RULE_HIT (rule_hit, resolved to its journey identifier via an
 * external_txn_key/mtcn bridge). A transaction can appear in more than one source; {@link
 * #mergedEvidence} collapses those into one row per (batch, identifier), preferring
 * EXCLUSION_AUDIT, then RULE_HIT, then JOURNEY wherever sources disagree on a field.
 */
@Repository
@Transactional(readOnly = true)
public class TransactionReportRepository {
  private static final String ACTIVITY_TYPE = "activity_type";
  private static final String ATTEMPT_ID_ALIAS = "attemptId";
  private static final String ATTEMPT_ID_COLUMN = "attempt_id";
  private static final String BATCH_ID_ALIAS = "batchId";
  private static final String BATCH_ID_COLUMN = "batch_id";
  private static final String BUCKET_ID_ALIAS = "bucketId";
  private static final String BUCKET_ID_COLUMN = "bucket_id";
  private static final String COMMENTS = "comments";
  private static final String CURRENCY_AMOUNT = "currency_amount";
  private static final String CURRENCY_CODE = "currency_code";
  private static final String EVIDENCE_BATCH_ID = "evidence_batch_id";
  private static final String EVIDENCE_SOURCE = "evidence_source";
  private static final String EXCLUSION_REASON = "exclusion_reason";
  private static final String EXCLUSION_STRATEGY = "exclusion_strategy";
  private static final String GALACTIC_ID = "galactic_id";
  private static final String IDENTIFIER = "identifier";
  private static final String IS_REPORTED = "is_reported";
  private static final String MATCHED_IDENTIFIER = "matched_identifier";
  private static final String MODIFIED_AT = "modified_at";
  private static final String OUTCOME = "outcome";
  private static final String OUTCOME_ERROR = "ERROR";
  private static final String OUTCOME_PENDING = "PENDING";
  private static final String OUTCOME_SUCCESS = "SUCCESS";
  private static final String PROCESSING_COMPLETE = "processing_complete";
  private static final String RECORD_KEY = "record_key";
  private static final String REPORTED_BATCH_ID = "reported_batch_id";
  private static final String REPORT_GROUP_ID_COLUMN = "rpt_grp_id";
  private static final String REPORT_GROUP_NAME_ALIAS = "reportGroupName";
  private static final String REPORTING_TIMESTAMP_COLUMN = "reporting_timestamp";
  private static final String RRA_KEY = "rra_key";
  private static final String RULE_HIT_MATCHES = "rule_hit_matches";
  private static final String RULE_ID_ALIAS = "ruleId";
  private static final String RULE_ID_COLUMN = "rule_id";
  private static final String SEND_DATE = "send_date";
  private static final String SKIP_REASON = "skip_reason";
  private static final String SORT_TIMESTAMP = "sort_ts";
  private static final String SOURCE_EXCLUSION_AUDIT = "EXCLUSION_AUDIT";
  private static final String SOURCE_JOURNEY = "JOURNEY";
  private static final String SOURCE_RANK = "source_rank";
  private static final String SOURCE_RULE_HIT = "RULE_HIT";
  private static final String STAGE = "stage";
  private static final String STAGE_FILTRATION = "FILTRATION";
  private static final String STATUS = "status";
  private static final String TRANSACTION_DATE = "transaction_date";
  private static final String TRANSACTION_SIDE = "transaction_side";
  private static final String TRANSACTION_SOURCE = "txn_source";
  private static final String VALUE_EXCLUDED = "EXCLUDED";
  private static final String VALUE_NOT_REPORTED = "NOT_REPORTED";
  private static final String VALUE_REPORTED = "REPORTED";
  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation RECONCILIATION =
      REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  private static final com.pharos.compliance.jooq.tables.RuleHit RULE_HIT_TABLE = RULE_HIT;
  private static final com.pharos.compliance.jooq.tables.RuleHitExclusionAudit EXCLUSION_AUDIT = RULE_HIT_EXCLUSION_AUDIT;
  private static final com.pharos.compliance.jooq.tables.RegReportableActivity RRA = REG_REPORTABLE_ACTIVITY;
  private static final String REPORTING_TIMESTAMP = "reportingTimestamp";
  /**
   * Column list shared by the merged CTE and the outer projection -- 27 fields, in the exact order
   * the original SQL's MERGED_CTE listed them, so the two stay easy to compare side by side.
   */
  private static final List<String> MERGE_COLUMNS = List.of(RECORD_KEY, "mtcn", EVIDENCE_SOURCE, STAGE, STATUS, OUTCOME, COMMENTS,
      SKIP_REASON, RULE_ID_COLUMN, EXCLUSION_REASON, EXCLUSION_STRATEGY, REPORTED_BATCH_ID, REPORTING_TIMESTAMP_COLUMN, MODIFIED_AT,
      SORT_TIMESTAMP, PROCESSING_COMPLETE, CURRENCY_AMOUNT, CURRENCY_CODE, TRANSACTION_DATE, TRANSACTION_SIDE, TRANSACTION_SOURCE,
      ACTIVITY_TYPE, SEND_DATE, GALACTIC_ID, BUCKET_ID_COLUMN, ATTEMPT_ID_COLUMN, RRA_KEY);
  private final DSLContext dsl;

  public TransactionReportRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Condition matchesDigitsOnly(Field<String> field) {
    return DSL.condition("{0} ~ '^[0-9]+$'", field);
  }

  private static Condition searchScope(String search, Field<String> identifier, Field<String> mtcn) {
    if (search.isEmpty()) {
      return DSL.trueCondition();
    }
    String pattern = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%";
    return DSL.lower(identifier).like(pattern).or(DSL.lower(DSL.coalesce(mtcn, "")).like(pattern));
  }

  /**
   * {@code (ARRAY_AGG(value ORDER BY rank, key) FILTER (WHERE value IS NOT NULL))[1]} -- picks the
   * value from the highest-priority (lowest source_rank) row that actually has a non-null value for
   * this column, among every row merged into one (batch, identifier) group. No jOOQ DSL builds an
   * array-index expression, so the aggregate is built with the real fluent API
   * (arrayAgg/orderBy/filterWhere) and only the trailing {@code [1]} is a raw template.
   */
  private static <T> Field<T> firstNonNullByRank(Field<T> value, Field<Integer> sourceRank, Field<String> recordKey, Class<T> type) {
    Field<T[]> aggregated = DSL.arrayAgg(value).orderBy(sourceRank.asc(), recordKey.asc()).filterWhere(value.isNotNull());
    Class<T[]> arrayType = aggregated.getType();
    return DSL.field("({0})[1]", type, DSL.field("{0}", arrayType, aggregated));
  }

  // ---------------------------------------------------------------------------------------------
  // Batch-scoped evidence (findEvidenceRecords / countEvidenceRecords)
  // ---------------------------------------------------------------------------------------------
  private Table<?> ruleHitMatchesForBatch(int reportGroupId, String batchId, String status) {
    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      // Same performance short-circuit as the original SQL: rule_hit evidence's status can only
      // ever be REPORTED/NOT_REPORTED, so any other requested status matches zero rule_hit rows --
      // skip the two correlated identifier-lookup subqueries entirely rather than run them for no
      // reason.
      return dsl
        .select(RULE_HIT_TABLE.fields())
        .select(DSL.cast(null, SQLDataType.CLOB).as(MATCHED_IDENTIFIER))
        .from(RULE_HIT_TABLE)
        .where(DSL.falseCondition())
        .asTable(RULE_HIT_MATCHES);
    }

    Field<String> byIdentifier = DSL.field(dsl
      .select(JOURNEY.IDENTIFIER)
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(JOURNEY.BATCH_ID.eq(batchId))
      .and(matchesDigitsOnly(JOURNEY.IDENTIFIER))
      .and(JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT).eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
      .limit(1));
    Field<String> byMtcn = DSL.field(dsl
      .select(JOURNEY.IDENTIFIER)
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(JOURNEY.BATCH_ID.eq(batchId))
      .and(JOURNEY.MTCN.eq(RULE_HIT_TABLE.MTCN))
      .limit(1));

    return dsl
      .select(RULE_HIT_TABLE.fields())
      .select(DSL.coalesce(byIdentifier, byMtcn).as(MATCHED_IDENTIFIER))
      .from(RULE_HIT_TABLE)
      .where(RULE_HIT_TABLE.RPT_GRP_ID.eq(reportGroupId))
      .asTable(RULE_HIT_MATCHES);
  }

  private Table<?> evidenceForBatch(int reportGroupId, String batchId, Table<?> ruleHitMatches) {
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
    Field<Boolean> rhmIsReported = requiredField(ruleHitMatches, IS_REPORTED, Boolean.class);
    Field<String> rhmExclusionReasonId = requiredField(ruleHitMatches, "exclusion_reason_id", String.class);
    Field<String> rhmReportedBatchId = requiredField(ruleHitMatches, REPORTED_BATCH_ID, String.class);
    Field<LocalDateTime> rhmReportingTimestamp = requiredField(ruleHitMatches, REPORTING_TIMESTAMP_COLUMN, LocalDateTime.class);
    Field<java.time.OffsetDateTime> rhmModifiedTimestamp =
        requiredField(ruleHitMatches, "modified_timestamp", java.time.OffsetDateTime.class);
    Field<BigDecimal> rhmCurrencyAmount = requiredField(ruleHitMatches, "rule_currency_amount", BigDecimal.class);
    Field<String> rhmCurrencyCode = requiredField(ruleHitMatches, "rule_iso_currency_code", String.class);
    Field<LocalDateTime> rhmTransactionDate = requiredField(ruleHitMatches, TRANSACTION_DATE, LocalDateTime.class);
    Field<String> rhmTransactionSide = requiredField(ruleHitMatches, TRANSACTION_SIDE, String.class);
    Field<String> rhmSource = requiredField(ruleHitMatches, "source", String.class);
    Field<String> rhmActivityType = requiredField(ruleHitMatches, ACTIVITY_TYPE, String.class);
    Field<java.time.LocalDate> rhmSendDate = requiredField(ruleHitMatches, SEND_DATE, java.time.LocalDate.class);
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

  private static Field<String> journeyOutcome(Field<String> status) {
    Field<String> upperStatus = DSL.upper(DSL.coalesce(status, ""));
    return DSL
      .when(upperStatus.in(OUTCOME_ERROR, "FAILED", "FAILURE"), DSL.inline(OUTCOME_ERROR))
      .when(upperStatus.in(OUTCOME_SUCCESS, "COMPLETED", "TRANSFORMED", VALUE_REPORTED), DSL.inline(OUTCOME_SUCCESS))
      .when(upperStatus.eq(VALUE_EXCLUDED), DSL.inline(VALUE_EXCLUDED))
      .otherwise(DSL.inline(OUTCOME_PENDING));
  }

  private Table<?> metricScoped(Table<?> evidence, String metric, String source) {
    Field<String> evidenceSource = requiredField(evidence, EVIDENCE_SOURCE, String.class);
    Field<String> stage = requiredField(evidence, STAGE, String.class);
    Field<String> outcome = requiredField(evidence, OUTCOME, String.class);
    Field<String> comments = requiredField(evidence, COMMENTS, String.class);

    Field<String> upperStage = DSL.upper(DSL.coalesce(stage, ""));
    Field<String> upperComments = DSL.upper(DSL.coalesce(comments, ""));

    Condition metricCondition = switch (metric) {
      case "ALL" -> DSL.trueCondition();
      case "SELECTED", "ATTEMPTS_FOUND", "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE", "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE",
          "TRANSFORMER_OUTPUT" -> evidenceSource.eq(SOURCE_JOURNEY);
      case "TRANSFORMED" -> evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq("TRANSFORMATION")).and(outcome.eq(OUTCOME_SUCCESS));
      case "FAILED" -> evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq("TRANSFORMATION")).and(outcome.eq(OUTCOME_ERROR));
      case VALUE_EXCLUDED -> evidenceSource.eq(SOURCE_EXCLUSION_AUDIT);
      case "SIMULATED" -> evidenceSource
        .eq(SOURCE_JOURNEY)
        .and(upperStage.eq(STAGE_FILTRATION))
        .and(upperComments.eq("EXCLUDED_BECAUSE_SML"));
      case "ALREADY_REPORTED" -> evidenceSource
        .eq(SOURCE_JOURNEY)
        .and(upperStage.eq(STAGE_FILTRATION))
        .and(upperComments.like("EXCLUDED_BECAUSE_ALREADY_REPORTED%"));
      case "SOFT_DEDUP" -> evidenceSource
        .eq(SOURCE_JOURNEY)
        .and(upperStage.eq(STAGE_FILTRATION))
        .and(upperComments.eq("EXCLUDED_SOFT_DEDUP").or(upperComments.like("EXCLUDED_REAPPEARING_%")));
      case "ACTUAL_REPORTABLE_TRANSFORMER_OUTPUT" -> evidenceSource.eq(SOURCE_RULE_HIT);
      case "FILTERED" -> evidenceSource
        .eq(SOURCE_EXCLUSION_AUDIT)
        .or(evidenceSource.eq(SOURCE_JOURNEY).and(upperStage.eq(STAGE_FILTRATION)));
      default -> DSL.trueCondition();
    };
    // ACTUAL_REPORTABLE and TRANSFORMER_OUTPUT share the RULE_HIT-only condition in the original
    // SQL's single OR-branch; re-expressed as two separate cases mapping to the same condition.
    if ("ACTUAL_REPORTABLE".equals(metric) || "TRANSFORMER_OUTPUT".equals(metric)) {
      metricCondition = metricCondition.or(evidenceSource.eq(SOURCE_RULE_HIT));
    }

    return dsl
      .select(evidence.fields())
      .from(evidence)
      .where("ALL".equals(source) ? DSL.trueCondition() : evidenceSource.eq(source))
      .and(metricCondition)
      .asTable("metric_scoped");
  }

  private Table<?> filteredEvidenceForBatch(Table<?> evidence, String metric, String search, String source, String stage, String outcome,
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

  /**
   * Collapses filtered evidence to one row per (evidence_batch_id, identifier), preferring
   * EXCLUSION_AUDIT, then RULE_HIT, then JOURNEY wherever sources disagree on a field.
   */
  private Table<?> mergedEvidence(Table<?> filteredEvidence) {
    Field<String> evidenceSource = requiredField(filteredEvidence, EVIDENCE_SOURCE, String.class);

    Field<Integer> sourceRank =
        DSL.when(evidenceSource.eq(SOURCE_EXCLUSION_AUDIT), 1).when(evidenceSource.eq(SOURCE_RULE_HIT), 2).otherwise(3).as(SOURCE_RANK);

    var ranked = dsl.select(filteredEvidence.fields()).select(sourceRank).from(filteredEvidence).asTable("ranked");

    Field<String> rEvidenceBatchId = requiredField(ranked, EVIDENCE_BATCH_ID, String.class);
    Field<String> rIdentifier = requiredField(ranked, IDENTIFIER, String.class);
    Field<Integer> rSourceRank = requiredField(ranked, SOURCE_RANK, Integer.class);
    Field<String> rRecordKey = requiredField(ranked, RECORD_KEY, String.class);

    List<Field<?>> selectList = new ArrayList<>();
    selectList.add(rEvidenceBatchId);
    selectList.add(rIdentifier);
    for (String column : MERGE_COLUMNS) {
      Class<?> type = mergeColumnType(column);
      selectList.add(firstNonNullByRank(ranked, column, type, rSourceRank, rRecordKey));
    }
    selectList.add(DSL.min(rSourceRank).as(SOURCE_RANK));

    return dsl.select(selectList).from(ranked).groupBy(rEvidenceBatchId, rIdentifier).asTable("merged");
  }

  private static <T> Field<T> firstNonNullByRank(Table<?> ranked, String column, Class<T> type, Field<Integer> sourceRank,
      Field<String> recordKey) {
    Field<T> value = requiredField(ranked, column, type);
    return firstNonNullByRank(value, sourceRank, recordKey, type).as(column);
  }

  private static Class<?> mergeColumnType(String column) {
    return switch (column) {
      case PROCESSING_COMPLETE -> Boolean.class;
      case CURRENCY_AMOUNT -> Double.class;
      case BUCKET_ID_COLUMN -> Integer.class;
      case ATTEMPT_ID_COLUMN, RRA_KEY -> Long.class;
      case SORT_TIMESTAMP -> java.time.OffsetDateTime.class;
      default -> String.class;
    };
  }

  private List<TransactionEvidenceProjection> pageMergedEvidence(Table<?> merged, Table<?> ruleHitMatches, String sortDirection, int size,
      long offset) {
    Field<String> recordKey = requiredField(merged, RECORD_KEY, String.class);
    Field<java.time.OffsetDateTime> sortTs = requiredField(merged, SORT_TIMESTAMP, java.time.OffsetDateTime.class);

    var page = dsl
      .select(merged.fields())
      .from(merged)
      .orderBy(evidenceOrder(sortDirection, sortTs, recordKey))
      .limit(size)
      .offset(offset)
      .asTable("page");

    return selectEvidenceProjection(page, ruleHitMatches)
      .orderBy(
          evidenceOrder(sortDirection, requiredField(page, SORT_TIMESTAMP, java.time.OffsetDateTime.class),
              requiredField(page, RECORD_KEY, String.class)))
      .fetch(TransactionReportRepository::toEvidenceProjection);
  }

  private static List<OrderField<?>> evidenceOrder(String sortDirection, Field<java.time.OffsetDateTime> sortTs, Field<String> recordKey) {
    List<OrderField<?>> order = new ArrayList<>();
    order.add("ASC".equals(sortDirection) ? sortTs.asc().nullsLast() : sortTs.desc().nullsLast());
    order.add(recordKey.asc());
    return order;
  }

  private org.jooq.SelectConditionStep<Record> selectEvidenceProjection(Table<?> page, Table<?> ruleHitMatches) {
    Field<String> pIdentifier = requiredField(page, IDENTIFIER, String.class);
    Field<String> pEvidenceSource = requiredField(page, EVIDENCE_SOURCE, String.class);
    Field<Long> pRraKey = requiredField(page, RRA_KEY, Long.class);
    Field<Double> pCurrencyAmount = requiredField(page, CURRENCY_AMOUNT, Double.class);
    Field<String> pCurrencyCode = requiredField(page, CURRENCY_CODE, String.class);
    Field<String> pTransactionDate = requiredField(page, TRANSACTION_DATE, String.class);
    Field<String> pSendDate = requiredField(page, SEND_DATE, String.class);

    Field<Double> currencyAmountOut = DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_LOCAL_PRINCIPAL, RRA.R_LOCAL_PRINCIPAL))
      .otherwise(pCurrencyAmount)
      .as("currencyAmount");
    Field<String> currencyCodeOut =
        DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_CURRENCY, RRA.R_CURRENCY))
      .otherwise(pCurrencyCode)
      .as("currencyCode");
    Field<String> transactionDateOut =
        DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_DATE, RRA.R_DATE))
      .otherwise(pTransactionDate)
      .as("transactionDate");
    Field<String> sendDateOut = DSL.when(pEvidenceSource.eq(SOURCE_JOURNEY), RRA.GROUP_SEND_DATE).otherwise(pSendDate).as("sendDate");

    var rollupRuleHits = rollupRuleHits(ruleHitMatches, pIdentifier);

    return dsl
      .select(requiredField(page, RECORD_KEY, String.class).as("recordKey"), pIdentifier.as(IDENTIFIER),
          requiredField(page, "mtcn", String.class).as("mtcn"), requiredField(page, EVIDENCE_BATCH_ID, String.class).as(BATCH_ID_ALIAS),
          pEvidenceSource.as("evidenceSource"), requiredField(page, STAGE, String.class).as(STAGE),
          requiredField(page, STATUS, String.class).as(STATUS), requiredField(page, OUTCOME, String.class).as(OUTCOME),
          requiredField(page, COMMENTS, String.class).as(COMMENTS), requiredField(page, SKIP_REASON, String.class).as("skipReason"),
          requiredField(page, RULE_ID_COLUMN, String.class).as(RULE_ID_ALIAS),
          requiredField(page, EXCLUSION_REASON, String.class).as("exclusionReason"),
          requiredField(page, EXCLUSION_STRATEGY, String.class).as("exclusionStrategy"),
          requiredField(page, REPORTED_BATCH_ID, String.class).as("reportedBatchId"),
          requiredField(page, REPORTING_TIMESTAMP_COLUMN, String.class).as(REPORTING_TIMESTAMP),
          requiredField(page, MODIFIED_AT, String.class).as("modifiedAt"),
          requiredField(page, PROCESSING_COMPLETE, Boolean.class).as("processingComplete"), currencyAmountOut, currencyCodeOut,
          transactionDateOut, requiredField(page, TRANSACTION_SIDE, String.class).as("transactionSide"),
          requiredField(page, TRANSACTION_SOURCE, String.class).as("txnSource"),
          requiredField(page, ACTIVITY_TYPE, String.class).as("activityType"), sendDateOut,
          requiredField(page, GALACTIC_ID, String.class).as("galacticId"),
          requiredField(page, BUCKET_ID_COLUMN, Integer.class).as(BUCKET_ID_ALIAS),
          requiredField(page, ATTEMPT_ID_COLUMN, Long.class).as(ATTEMPT_ID_ALIAS), RRA.S_PARTY_NAME.as("senderName"),
          RRA.R_PARTY_NAME.as("receiverName"), RRA.S_PARTY_CITY.as("senderCity"), RRA.S_PARTY_COUNTRY_OF_RESIDENCE.as("senderCountry"),
          RRA.S_PARTY_PHONE_NUMBER.as("senderPhone"), RRA.S_PARTY_DATE_OF_BIRTH.as("senderDateOfBirth"),
          RRA.S_PARTY_ID_TYPE.as("senderIdType"), RRA.S_PARTY_ID_NUMBER.as("senderIdNumber"), RRA.R_PARTY_CITY.as("receiverCity"),
          RRA.R_PARTY_COUNTRY_OF_RESIDENCE.as("receiverCountry"), RRA.R_PARTY_PHONE_NUMBER.as("receiverPhone"),
          RRA.R_PARTY_DATE_OF_BIRTH.as("receiverDateOfBirth"), RRA.R_PARTY_ID_TYPE.as("receiverIdType"),
          RRA.R_PARTY_ID_NUMBER.as("receiverIdNumber"), RRA.TXN_STATUS.as("transactionStatus"), RRA.SUB_STATUS.as("transactionSubStatus"),
          DSL.coalesce(rollupRuleHits, DSL.inline("[]")).as("ruleHitsJson"))
      .from(page)
      .leftJoin(RRA)
      .on(RRA.TXN_SUR_KEY.eq(pRraKey))
      .where(DSL.trueCondition());
  }

  /**
   * {@code LEFT JOIN LATERAL (SELECT json_agg(json_build_object(...)) ...) rollup ON TRUE}.
   */
  private Field<String> rollupRuleHits(Table<?> ruleHitMatches, Field<String> identifier) {
    Field<String> rhmMatchedIdentifier = requiredField(ruleHitMatches, MATCHED_IDENTIFIER, String.class);
    Field<String> rhmRuleId = requiredField(ruleHitMatches, RULE_ID_COLUMN, String.class);
    Field<Boolean> rhmIsReported = requiredField(ruleHitMatches, IS_REPORTED, Boolean.class);
    Field<LocalDateTime> rhmReportingTimestamp = requiredField(ruleHitMatches, REPORTING_TIMESTAMP_COLUMN, LocalDateTime.class);
    Field<Integer> rhmBucketId = requiredField(ruleHitMatches, BUCKET_ID_COLUMN, Integer.class);
    Field<Long> rhmAttemptId = requiredField(ruleHitMatches, ATTEMPT_ID_COLUMN, Long.class);

    var rollup = DSL
      .lateral(dsl
        .select(DSL
          .jsonArrayAgg(DSL.jsonObject(DSL.jsonEntry(RULE_ID_ALIAS, rhmRuleId), DSL.jsonEntry("isReported", rhmIsReported),
              DSL.jsonEntry(REPORTING_TIMESTAMP, rhmReportingTimestamp.cast(SQLDataType.CLOB)), DSL.jsonEntry(BUCKET_ID_ALIAS, rhmBucketId),
              DSL.jsonEntry(ATTEMPT_ID_ALIAS, rhmAttemptId)))
          .orderBy(rhmRuleId)
          .cast(SQLDataType.CLOB)
          .as("rule_hits_json"))
        .from(ruleHitMatches)
        .where(rhmMatchedIdentifier.isNotNull())
        .and(rhmMatchedIdentifier.eq(identifier)))
      .asTable("rollup");

    return DSL.field(dsl.select(requiredField(rollup, "rule_hits_json", String.class)).from(rollup));
  }

  @SqlQueryPurpose("Load transaction reconciliation context for one batch")
  public Optional<TransactionReportContextProjection> findReportContext(int reportGroupId, String batchId, int sequenceNumber) {
    return dsl
      .select(RECONCILIATION.RPT_GRP_ID.as("reportGroupId"), RECONCILIATION.RPT_GRP_NAME.as(REPORT_GROUP_NAME_ALIAS),
          RECONCILIATION.BATCH_ID.as(BATCH_ID_ALIAS), RECONCILIATION.SEQ_NO.as("sequenceNumber"),
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
          r.get(REPORT_GROUP_NAME_ALIAS, String.class), r.get(BATCH_ID_ALIAS, String.class), requiredInt(r, "sequenceNumber"),
          r.get("reportingPeriodFrom", String.class), r.get("reportingPeriodTo", String.class), requiredLong(r, "selectedTransactions"),
          requiredLong(r, "attemptsFound"), requiredLong(r, "missingAttempts"), requiredLong(r, "expectedEligible"),
          requiredLong(r, "actualEligible"), requiredLong(r, "transformed"), requiredLong(r, "failed"),
          requiredLong(r, "expectedReportable"), requiredLong(r, "actualReportable"), requiredLong(r, "excluded"),
          requiredLong(r, "simulated"), requiredLong(r, "alreadyReported"), requiredLong(r, "softDedup"),
          requiredLong(r, "filtrationVariance"), requiredLong(r, "reconciliationVariance")));
  }

  @SqlQueryPurpose("Load paginated transaction evidence for one batch")
  public List<TransactionEvidenceProjection> findEvidenceRecords(int reportGroupId, String batchId, String metric, String search,
      String source, String stage, String outcome, String status, String sortDirection, int size, long offset) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    var merged = mergedEvidence(filtered);
    return pageMergedEvidence(merged, ruleHitMatches, sortDirection, size, offset);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records for one batch")
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    Field<String> evidenceBatchId = requiredField(filtered, EVIDENCE_BATCH_ID, String.class);
    Field<String> identifier = requiredField(filtered, IDENTIFIER, String.class);
    Long count = dsl.select(DSL.countDistinct(DSL.row(evidenceBatchId, identifier))).from(filtered).fetchOne(0, Long.class);
    return count == null ? 0L : count;
  }

  // ---------------------------------------------------------------------------------------------
  // Period-scoped evidence (findPeriodEvidenceRecords / countPeriodEvidenceRecords /
  // findPeriodAggregate)
  // ---------------------------------------------------------------------------------------------
  private Table<?> batchScope(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
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

  private Table<?> ruleHitMatchesForPeriod(Table<?> batchScope, String status) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);

    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      return dsl
        .select(RULE_HIT_TABLE.fields())
        .select(DSL.cast(null, SQLDataType.CLOB).as(MATCHED_IDENTIFIER))
        .from(RULE_HIT_TABLE)
        .where(DSL.falseCondition())
        .asTable(RULE_HIT_MATCHES);
    }

    Field<String> byIdentifier = DSL.field(dsl
      .select(JOURNEY.IDENTIFIER)
      .from(JOURNEY)
      .join(batchScope)
      .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(bsBatchId.eq(JOURNEY.BATCH_ID))
      .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(matchesDigitsOnly(JOURNEY.IDENTIFIER))
      .and(JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT).eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
      .limit(1));
    Field<String> byMtcn = DSL.field(dsl
      .select(JOURNEY.IDENTIFIER)
      .from(JOURNEY)
      .join(batchScope)
      .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(bsBatchId.eq(JOURNEY.BATCH_ID))
      .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
      .and(JOURNEY.MTCN.eq(RULE_HIT_TABLE.MTCN))
      .limit(1));

    return dsl
      .select(RULE_HIT_TABLE.fields())
      .select(DSL.coalesce(byIdentifier, byMtcn).as(MATCHED_IDENTIFIER))
      .from(RULE_HIT_TABLE)
      .where(RULE_HIT_TABLE.RPT_GRP_ID.in(dsl.selectDistinct(bsRptGrpId).from(batchScope)))
      .asTable(RULE_HIT_MATCHES);
  }

  private Table<?> evidenceForPeriod(Table<?> batchScope, Table<?> ruleHitMatches) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);

    var journeyBranch = dsl
      .select(DSL
            .concat(DSL.inline("JOURNEY:"), JOURNEY.RPT_GRP_ID, DSL.inline(":"), JOURNEY.BATCH_ID, DSL.inline(":"), JOURNEY.IDENTIFIER)
            .as(RECORD_KEY), JOURNEY.IDENTIFIER.as(IDENTIFIER), JOURNEY.MTCN.as("mtcn"), JOURNEY.BATCH_ID.as(EVIDENCE_BATCH_ID),
          DSL.inline(SOURCE_JOURNEY).as(EVIDENCE_SOURCE), JOURNEY.STAGE.as(STAGE), JOURNEY.STATUS.as(STATUS),
          journeyOutcome(JOURNEY.STATUS).as(OUTCOME), JOURNEY.COMMENTS.as(COMMENTS), JOURNEY.SKIP_REASON.as(SKIP_REASON),
          DSL.cast(null, SQLDataType.CLOB).as(RULE_ID_COLUMN), DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_REASON),
          DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_STRATEGY), DSL.cast(null, SQLDataType.CLOB).as(REPORTED_BATCH_ID),
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
      .join(batchScope)
      .on(bsRptGrpId.eq(EXCLUSION_AUDIT.RPT_GRP_ID))
      .and(bsBatchId.eq(EXCLUSION_AUDIT.PROCESSING_BATCH_ID));

    Field<Integer> rhmBucketId = requiredField(ruleHitMatches, BUCKET_ID_COLUMN, Integer.class);
    Field<String> rhmRuleId = requiredField(ruleHitMatches, RULE_ID_COLUMN, String.class);
    Field<Long> rhmAttemptId = requiredField(ruleHitMatches, ATTEMPT_ID_COLUMN, Long.class);
    Field<String> rhmMatchedIdentifier = requiredField(ruleHitMatches, MATCHED_IDENTIFIER, String.class);
    Field<String> rhmMtcn = requiredField(ruleHitMatches, "mtcn", String.class);
    Field<String> rhmEfileBatchId = requiredField(ruleHitMatches, "efile_batch_id", String.class);
    Field<Boolean> rhmIsReported = requiredField(ruleHitMatches, IS_REPORTED, Boolean.class);
    Field<String> rhmExclusionReasonId = requiredField(ruleHitMatches, "exclusion_reason_id", String.class);
    Field<String> rhmReportedBatchId = requiredField(ruleHitMatches, REPORTED_BATCH_ID, String.class);
    Field<LocalDateTime> rhmReportingTimestamp = requiredField(ruleHitMatches, REPORTING_TIMESTAMP_COLUMN, LocalDateTime.class);
    Field<java.time.OffsetDateTime> rhmModifiedTimestamp =
        requiredField(ruleHitMatches, "modified_timestamp", java.time.OffsetDateTime.class);
    Field<BigDecimal> rhmCurrencyAmount = requiredField(ruleHitMatches, "rule_currency_amount", BigDecimal.class);
    Field<String> rhmCurrencyCode = requiredField(ruleHitMatches, "rule_iso_currency_code", String.class);
    Field<LocalDateTime> rhmTransactionDate = requiredField(ruleHitMatches, TRANSACTION_DATE, LocalDateTime.class);
    Field<String> rhmTransactionSide = requiredField(ruleHitMatches, TRANSACTION_SIDE, String.class);
    Field<String> rhmSource = requiredField(ruleHitMatches, "source", String.class);
    Field<String> rhmActivityType = requiredField(ruleHitMatches, ACTIVITY_TYPE, String.class);
    Field<java.time.LocalDate> rhmSendDate = requiredField(ruleHitMatches, SEND_DATE, java.time.LocalDate.class);
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

  private Table<?> filteredEvidenceForPeriod(Table<?> evidence, String search, String outcome, String status) {
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

  @SqlQueryPurpose("Load paginated transaction evidence across the selected reporting period")
  public List<TransactionEvidenceProjection> findPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome,
      String status, String sortDirection, int size, long offset) {
    var scope = batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    var merged = mergedEvidence(filtered);
    return pageMergedEvidence(merged, ruleHitMatches, sortDirection, size, offset);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records across the selected reporting period")
  public long countPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome, String status) {
    var scope = batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    Field<String> evidenceBatchId = requiredField(filtered, EVIDENCE_BATCH_ID, String.class);
    Field<String> identifier = requiredField(filtered, IDENTIFIER, String.class);
    Long count = dsl.select(DSL.countDistinct(DSL.row(evidenceBatchId, identifier))).from(filtered).fetchOne(0, Long.class);
    return count == null ? 0L : count;
  }

  private static TransactionEvidenceProjection toEvidenceProjection(Record r) {
    Double currencyAmount = r.get("currencyAmount", Double.class);
    return new TransactionEvidenceProjection(r.get("recordKey", String.class), r.get(IDENTIFIER, String.class), r.get("mtcn", String.class),
        r.get(BATCH_ID_ALIAS, String.class), r.get("evidenceSource", String.class), r.get(STAGE, String.class), r.get(STATUS, String.class),
        r.get(OUTCOME, String.class), r.get(COMMENTS, String.class), r.get("skipReason", String.class), r.get(RULE_ID_ALIAS, String.class),
        r.get("exclusionReason", String.class), r.get("exclusionStrategy", String.class), r.get("reportedBatchId", String.class),
        r.get(REPORTING_TIMESTAMP, String.class), r.get("modifiedAt", String.class), r.get("processingComplete", Boolean.class),
        currencyAmount == null ? null : BigDecimal.valueOf(currencyAmount), r.get("currencyCode", String.class),
        r.get("transactionDate", String.class), r.get("transactionSide", String.class), r.get("txnSource", String.class),
        r.get("activityType", String.class), r.get("sendDate", String.class), r.get("galacticId", String.class),
        r.get(BUCKET_ID_ALIAS, Integer.class), r.get(ATTEMPT_ID_ALIAS, Long.class), r.get("senderName", String.class),
        r.get("receiverName", String.class), r.get("senderCity", String.class), r.get("senderCountry", String.class),
        r.get("senderPhone", String.class), r.get("senderDateOfBirth", String.class), r.get("senderIdType", String.class),
        r.get("senderIdNumber", String.class), r.get("receiverCity", String.class), r.get("receiverCountry", String.class),
        r.get("receiverPhone", String.class), r.get("receiverDateOfBirth", String.class), r.get("receiverIdType", String.class),
        r.get("receiverIdNumber", String.class), r.get("transactionStatus", String.class), r.get("transactionSubStatus", String.class),
        r.get("ruleHitsJson", String.class));
  }
}
