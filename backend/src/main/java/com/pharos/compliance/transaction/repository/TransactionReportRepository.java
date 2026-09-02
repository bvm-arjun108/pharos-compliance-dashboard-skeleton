package com.pharos.compliance.transaction.repository;

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

  private static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation
      RECONCILIATION = REPORT_TRANSFORMATION_RECONCILIATION;
  private static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY =
      RECORD_TRANSFORMATION_JOURNEY;
  private static final com.pharos.compliance.jooq.tables.RuleHit RULE_HIT_TABLE = RULE_HIT;
  private static final com.pharos.compliance.jooq.tables.RuleHitExclusionAudit EXCLUSION_AUDIT =
      RULE_HIT_EXCLUSION_AUDIT;
  private static final com.pharos.compliance.jooq.tables.RegReportableActivity RRA =
      REG_REPORTABLE_ACTIVITY;

  /** Column list shared by the merged CTE and the outer projection -- 27 fields, in the exact
   *  order the original SQL's MERGED_CTE listed them, so the two stay easy to compare side by side. */
  private static final List<String> MERGE_COLUMNS =
      List.of(
          "record_key", "mtcn", "evidence_source", "stage", "status", "outcome", "comments",
          "skip_reason", "rule_id", "exclusion_reason", "exclusion_strategy", "reported_batch_id",
          "reporting_timestamp", "modified_at", "sort_ts", "processing_complete", "currency_amount",
          "currency_code", "transaction_date", "transaction_side", "txn_source", "activity_type",
          "send_date", "galactic_id", "bucket_id", "attempt_id", "rra_key");

  private final DSLContext dsl;

  public TransactionReportRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Condition journeyCreatedBetween(LocalDateTime from, LocalDateTime toExclusive) {
    return DSL.condition(
        "{0} >= {1} and {0} < {2}", JOURNEY.CREATED_TIMESTAMP, DSL.val(from), DSL.val(toExclusive));
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
   * value from the highest-priority (lowest source_rank) row that actually has a non-null value
   * for this column, among every row merged into one (batch, identifier) group. No jOOQ DSL builds
   * an array-index expression, so the aggregate is built with the real fluent API
   * (arrayAgg/orderBy/filterWhere) and only the trailing {@code [1]} is a raw template.
   */
  private static <T> Field<T> firstNonNullByRank(
      Field<T> value, Field<Integer> sourceRank, Field<String> recordKey, Class<T> type) {
    Field<T[]> aggregated =
        DSL.arrayAgg(value).orderBy(sourceRank.asc(), recordKey.asc()).filterWhere(value.isNotNull());
    @SuppressWarnings("unchecked")
    Class<T[]> arrayType = (Class<T[]>) aggregated.getType();
    return DSL.field("({0})[1]", type, DSL.field("{0}", arrayType, aggregated));
  }

  // ---------------------------------------------------------------------------------------------
  // Batch-scoped evidence (findEvidenceRecords / countEvidenceRecords)
  // ---------------------------------------------------------------------------------------------

  private Table<?> ruleHitMatchesForBatch(int reportGroupId, String batchId, String status) {
    if (!("ALL".equals(status) || "REPORTED".equals(status) || "NOT_REPORTED".equals(status))) {
      // Same performance short-circuit as the original SQL: rule_hit evidence's status can only
      // ever be REPORTED/NOT_REPORTED, so any other requested status matches zero rule_hit rows --
      // skip the two correlated identifier-lookup subqueries entirely rather than run them for no
      // reason.
      return dsl.select(
              RULE_HIT_TABLE.fields())
          .select(DSL.cast(null, SQLDataType.CLOB).as("matched_identifier"))
          .from(RULE_HIT_TABLE)
          .where(DSL.falseCondition())
          .asTable("rule_hit_matches");
    }

    Field<String> byIdentifier =
        DSL.field(
            dsl.select(JOURNEY.IDENTIFIER)
                .from(JOURNEY)
                .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
                .and(JOURNEY.BATCH_ID.eq(batchId))
                .and(matchesDigitsOnly(JOURNEY.IDENTIFIER))
                .and(JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT).eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
                .limit(1));
    Field<String> byMtcn =
        DSL.field(
            dsl.select(JOURNEY.IDENTIFIER)
                .from(JOURNEY)
                .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
                .and(JOURNEY.BATCH_ID.eq(batchId))
                .and(JOURNEY.MTCN.eq(RULE_HIT_TABLE.MTCN))
                .limit(1));

    return dsl.select(RULE_HIT_TABLE.fields())
        .select(DSL.coalesce(byIdentifier, byMtcn).as("matched_identifier"))
        .from(RULE_HIT_TABLE)
        .where(RULE_HIT_TABLE.RPT_GRP_ID.eq(reportGroupId))
        .asTable("rule_hit_matches");
  }

  private Table<?> evidenceForBatch(int reportGroupId, String batchId, Table<?> ruleHitMatches) {
    var journeyBranch =
        dsl.select(
            DSL.concat(DSL.inline("JOURNEY:"), JOURNEY.IDENTIFIER).as("record_key"),
            JOURNEY.IDENTIFIER.as("identifier"),
            JOURNEY.MTCN.as("mtcn"),
            JOURNEY.BATCH_ID.as("evidence_batch_id"),
            DSL.inline("JOURNEY").as("evidence_source"),
            JOURNEY.STAGE.as("stage"),
            JOURNEY.STATUS.as("status"),
            journeyOutcome(JOURNEY.STATUS).as("outcome"),
            JOURNEY.COMMENTS.as("comments"),
            JOURNEY.SKIP_REASON.as("skip_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("rule_id"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_strategy"),
            DSL.cast(null, SQLDataType.CLOB).as("reported_batch_id"),
            JOURNEY.REPORTING_TIMESTAMP_LATEST.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as("modified_at"),
            JOURNEY.MODIFIED_TIMESTAMP.as("sort_ts"),
            JOURNEY.PROCESSING_COMPLETE.as("processing_complete"),
            DSL.cast(null, SQLDataType.DOUBLE).as("currency_amount"),
            DSL.cast(null, SQLDataType.CLOB).as("currency_code"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_date"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_side"),
            DSL.cast(null, SQLDataType.CLOB).as("txn_source"),
            DSL.cast(null, SQLDataType.CLOB).as("activity_type"),
            DSL.cast(null, SQLDataType.CLOB).as("send_date"),
            DSL.cast(null, SQLDataType.CLOB).as("galactic_id"),
            DSL.cast(null, SQLDataType.INTEGER).as("bucket_id"),
            DSL.cast(null, SQLDataType.BIGINT).as("attempt_id"),
            DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT))
                .as("rra_key"))
            .from(JOURNEY)
            .where(JOURNEY.RPT_GRP_ID.eq(reportGroupId))
            .and(JOURNEY.BATCH_ID.eq(batchId));

    var exclusionBranch =
        dsl.select(
            DSL.concat(
                    DSL.inline("EXCLUSION:"),
                    EXCLUSION_AUDIT.BUCKET_ID,
                    DSL.inline(":"),
                    EXCLUSION_AUDIT.RULE_ID,
                    DSL.inline(":"),
                    EXCLUSION_AUDIT.ATTEMPT_ID)
                .as("record_key"),
            DSL.coalesce(
                    EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.cast(SQLDataType.CLOB),
                    EXCLUSION_AUDIT.ATTEMPT_ID.cast(SQLDataType.CLOB))
                .as("identifier"),
            EXCLUSION_AUDIT.MTCN.as("mtcn"),
            EXCLUSION_AUDIT.PROCESSING_BATCH_ID.as("evidence_batch_id"),
            DSL.inline("EXCLUSION_AUDIT").as("evidence_source"),
            DSL.inline("EXCLUSION").as("stage"),
            DSL.inline("EXCLUDED").as("status"),
            DSL.inline("EXCLUDED").as("outcome"),
            DSL.cast(null, SQLDataType.CLOB).as("comments"),
            DSL.cast(null, SQLDataType.CLOB).as("skip_reason"),
            EXCLUSION_AUDIT.RULE_ID.as("rule_id"),
            EXCLUSION_AUDIT.EXCLUSION_REASON_ID.as("exclusion_reason"),
            EXCLUSION_AUDIT.EXCLUSION_STRATEGY.as("exclusion_strategy"),
            EXCLUSION_AUDIT.REPORTED_BATCH_ID.as("reported_batch_id"),
            EXCLUSION_AUDIT.REPORTING_TIMESTAMP.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            EXCLUSION_AUDIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as("modified_at"),
            DSL.field(
                    "{0} at time zone 'UTC'",
                    SQLDataType.TIMESTAMPWITHTIMEZONE, EXCLUSION_AUDIT.MODIFIED_TIMESTAMP)
                .as("sort_ts"),
            DSL.inline(true).as("processing_complete"),
            DSL.cast(null, SQLDataType.DOUBLE).as("currency_amount"),
            DSL.cast(null, SQLDataType.CLOB).as("currency_code"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_date"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_side"),
            DSL.cast(null, SQLDataType.CLOB).as("txn_source"),
            DSL.cast(null, SQLDataType.CLOB).as("activity_type"),
            DSL.cast(null, SQLDataType.CLOB).as("send_date"),
            DSL.cast(null, SQLDataType.CLOB).as("galactic_id"),
            EXCLUSION_AUDIT.BUCKET_ID.as("bucket_id"),
            EXCLUSION_AUDIT.ATTEMPT_ID.as("attempt_id"),
            EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.as("rra_key"))
            .from(EXCLUSION_AUDIT)
            .where(EXCLUSION_AUDIT.RPT_GRP_ID.eq(reportGroupId))
            .and(EXCLUSION_AUDIT.PROCESSING_BATCH_ID.eq(batchId));

    Field<Integer> rhmBucketId = ruleHitMatches.field("bucket_id", Integer.class);
    Field<String> rhmRuleId = ruleHitMatches.field("rule_id", String.class);
    Field<Long> rhmAttemptId = ruleHitMatches.field("attempt_id", Long.class);
    Field<String> rhmMatchedIdentifier = ruleHitMatches.field("matched_identifier", String.class);
    Field<String> rhmMtcn = ruleHitMatches.field("mtcn", String.class);
    Field<String> rhmEfileBatchId = ruleHitMatches.field("efile_batch_id", String.class);
    Field<Boolean> rhmIsReported = ruleHitMatches.field("is_reported", Boolean.class);
    Field<String> rhmExclusionReasonId = ruleHitMatches.field("exclusion_reason_id", String.class);
    Field<String> rhmReportedBatchId = ruleHitMatches.field("reported_batch_id", String.class);
    Field<LocalDateTime> rhmReportingTimestamp =
        ruleHitMatches.field("reporting_timestamp", LocalDateTime.class);
    Field<java.time.OffsetDateTime> rhmModifiedTimestamp =
        ruleHitMatches.field("modified_timestamp", java.time.OffsetDateTime.class);
    Field<BigDecimal> rhmCurrencyAmount = ruleHitMatches.field("rule_currency_amount", BigDecimal.class);
    Field<String> rhmCurrencyCode = ruleHitMatches.field("rule_iso_currency_code", String.class);
    Field<LocalDateTime> rhmTransactionDate =
        ruleHitMatches.field("transaction_date", LocalDateTime.class);
    Field<String> rhmTransactionSide = ruleHitMatches.field("transaction_side", String.class);
    Field<String> rhmSource = ruleHitMatches.field("source", String.class);
    Field<String> rhmActivityType = ruleHitMatches.field("activity_type", String.class);
    Field<java.time.LocalDate> rhmSendDate = ruleHitMatches.field("send_date", java.time.LocalDate.class);
    Field<String> rhmGalacticId = ruleHitMatches.field("galactic_id", String.class);
    Field<Long> rhmExternalTxnKey = ruleHitMatches.field("external_txn_key", Long.class);

    var ruleHitBranch =
        dsl.select(
            DSL.concat(
                    DSL.inline("RULE_HIT:"), rhmBucketId, DSL.inline(":"), rhmRuleId, DSL.inline(":"), rhmAttemptId)
                .as("record_key"),
            rhmMatchedIdentifier.as("identifier"),
            rhmMtcn.as("mtcn"),
            rhmEfileBatchId.as("evidence_batch_id"),
            DSL.inline("RULE_HIT").as("evidence_source"),
            DSL.inline("RULE_HIT").as("stage"),
            DSL.when(rhmIsReported, DSL.inline("REPORTED")).otherwise(DSL.inline("NOT_REPORTED")).as("status"),
            DSL.when(rhmIsReported, DSL.inline("SUCCESS")).otherwise(DSL.inline("PENDING")).as("outcome"),
            DSL.cast(null, SQLDataType.CLOB).as("comments"),
            DSL.cast(null, SQLDataType.CLOB).as("skip_reason"),
            rhmRuleId.as("rule_id"),
            rhmExclusionReasonId.as("exclusion_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_strategy"),
            rhmReportedBatchId.as("reported_batch_id"),
            rhmReportingTimestamp.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            rhmModifiedTimestamp.cast(SQLDataType.CLOB).as("modified_at"),
            rhmModifiedTimestamp.as("sort_ts"),
            DSL.inline(true).as("processing_complete"),
            rhmCurrencyAmount.cast(SQLDataType.DOUBLE).as("currency_amount"),
            rhmCurrencyCode.as("currency_code"),
            rhmTransactionDate.cast(SQLDataType.CLOB).as("transaction_date"),
            rhmTransactionSide.as("transaction_side"),
            rhmSource.as("txn_source"),
            rhmActivityType.as("activity_type"),
            rhmSendDate.cast(SQLDataType.CLOB).as("send_date"),
            rhmGalacticId.as("galactic_id"),
            rhmBucketId.as("bucket_id"),
            rhmAttemptId.as("attempt_id"),
            rhmExternalTxnKey.as("rra_key"))
            .from(ruleHitMatches)
            .where(rhmMatchedIdentifier.isNotNull());

    return journeyBranch.unionAll(exclusionBranch).unionAll(ruleHitBranch).asTable("evidence");
  }

  private static Field<String> journeyOutcome(Field<String> status) {
    Field<String> upperStatus = DSL.upper(DSL.coalesce(status, ""));
    return DSL.when(upperStatus.in("ERROR", "FAILED", "FAILURE"), DSL.inline("ERROR"))
        .when(upperStatus.in("SUCCESS", "COMPLETED", "TRANSFORMED", "REPORTED"), DSL.inline("SUCCESS"))
        .when(upperStatus.eq("EXCLUDED"), DSL.inline("EXCLUDED"))
        .otherwise(DSL.inline("PENDING"));
  }

  private Table<?> metricScoped(Table<?> evidence, String metric, String source) {
    Field<String> evidenceSource = evidence.field("evidence_source", String.class);
    Field<String> stage = evidence.field("stage", String.class);
    Field<String> outcome = evidence.field("outcome", String.class);
    Field<String> comments = evidence.field("comments", String.class);

    Field<String> upperStage = DSL.upper(DSL.coalesce(stage, ""));
    Field<String> upperComments = DSL.upper(DSL.coalesce(comments, ""));

    Condition metricCondition =
        switch (metric) {
          case "ALL" -> DSL.trueCondition();
          case "SELECTED", "ATTEMPTS_FOUND", "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE",
              "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE", "TRANSFORMER_OUTPUT" ->
              evidenceSource.eq("JOURNEY");
          case "TRANSFORMED" ->
              evidenceSource.eq("JOURNEY").and(upperStage.eq("TRANSFORMATION")).and(outcome.eq("SUCCESS"));
          case "FAILED" ->
              evidenceSource.eq("JOURNEY").and(upperStage.eq("TRANSFORMATION")).and(outcome.eq("ERROR"));
          case "EXCLUDED" -> evidenceSource.eq("EXCLUSION_AUDIT");
          case "SIMULATED" ->
              evidenceSource
                  .eq("JOURNEY")
                  .and(upperStage.eq("FILTRATION"))
                  .and(upperComments.eq("EXCLUDED_BECAUSE_SML"));
          case "ALREADY_REPORTED" ->
              evidenceSource
                  .eq("JOURNEY")
                  .and(upperStage.eq("FILTRATION"))
                  .and(upperComments.like("EXCLUDED_BECAUSE_ALREADY_REPORTED%"));
          case "SOFT_DEDUP" ->
              evidenceSource
                  .eq("JOURNEY")
                  .and(upperStage.eq("FILTRATION"))
                  .and(
                      upperComments
                          .eq("EXCLUDED_SOFT_DEDUP")
                          .or(upperComments.like("EXCLUDED_REAPPEARING_%")));
          case "ACTUAL_REPORTABLE_TRANSFORMER_OUTPUT" -> evidenceSource.eq("RULE_HIT");
          case "FILTERED" ->
              evidenceSource
                  .eq("EXCLUSION_AUDIT")
                  .or(evidenceSource.eq("JOURNEY").and(upperStage.eq("FILTRATION")));
          default -> DSL.trueCondition();
        };
    // ACTUAL_REPORTABLE and TRANSFORMER_OUTPUT share the RULE_HIT-only condition in the original
    // SQL's single OR-branch; re-expressed as two separate cases mapping to the same condition.
    if ("ACTUAL_REPORTABLE".equals(metric) || "TRANSFORMER_OUTPUT".equals(metric)) {
      metricCondition = metricCondition.or(evidenceSource.eq("RULE_HIT"));
    }

    return dsl.select(evidence.fields())
        .from(evidence)
        .where("ALL".equals(source) ? DSL.trueCondition() : evidenceSource.eq(source))
        .and(metricCondition)
        .asTable("metric_scoped");
  }

  private Table<?> filteredEvidenceForBatch(
      Table<?> evidence, String metric, String search, String source, String stage, String outcome, String status) {
    var scoped = metricScoped(evidence, metric, source);
    Field<String> identifier = scoped.field("identifier", String.class);
    Field<String> mtcn = scoped.field("mtcn", String.class);
    Field<String> stageField = scoped.field("stage", String.class);
    Field<String> outcomeField = scoped.field("outcome", String.class);
    Field<String> statusField = scoped.field("status", String.class);

    return dsl.select(scoped.fields())
        .from(scoped)
        .where("ALL".equals(stage) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(stageField, "")).eq(stage))
        .and(searchScope(search, identifier, mtcn))
        .and("ALL".equals(outcome) ? DSL.trueCondition() : outcomeField.eq(outcome))
        .and("ALL".equals(status) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(statusField, "")).eq(status))
        .asTable("filtered_evidence");
  }

  /** Collapses filtered evidence to one row per (evidence_batch_id, identifier), preferring
   *  EXCLUSION_AUDIT, then RULE_HIT, then JOURNEY wherever sources disagree on a field. */
  private Table<?> mergedEvidence(Table<?> filteredEvidence) {
    Field<String> evidenceBatchId = filteredEvidence.field("evidence_batch_id", String.class);
    Field<String> identifier = filteredEvidence.field("identifier", String.class);
    Field<String> evidenceSource = filteredEvidence.field("evidence_source", String.class);

    Field<Integer> sourceRank =
        DSL.when(evidenceSource.eq("EXCLUSION_AUDIT"), 1)
            .when(evidenceSource.eq("RULE_HIT"), 2)
            .otherwise(3)
            .as("source_rank");

    var ranked = dsl.select(filteredEvidence.fields()).select(sourceRank).from(filteredEvidence).asTable("ranked");

    Field<String> rEvidenceBatchId = ranked.field("evidence_batch_id", String.class);
    Field<String> rIdentifier = ranked.field("identifier", String.class);
    Field<Integer> rSourceRank = ranked.field("source_rank", Integer.class);
    Field<String> rRecordKey = ranked.field("record_key", String.class);

    List<Field<?>> selectList = new ArrayList<>();
    selectList.add(rEvidenceBatchId);
    selectList.add(rIdentifier);
    for (String column : MERGE_COLUMNS) {
      Class<?> type = mergeColumnType(column);
      selectList.add(firstNonNullByRankUnchecked(ranked, column, type, rSourceRank, rRecordKey));
    }
    selectList.add(DSL.min(rSourceRank).as("source_rank"));

    return dsl.select(selectList)
        .from(ranked)
        .groupBy(rEvidenceBatchId, rIdentifier)
        .asTable("merged");
  }

  @SuppressWarnings("unchecked")
  private static <T> Field<T> firstNonNullByRankUnchecked(
      Table<?> ranked, String column, Class<T> type, Field<Integer> sourceRank, Field<String> recordKey) {
    Field<T> value = (Field<T>) ranked.field(column, type);
    return firstNonNullByRank(value, sourceRank, recordKey, type).as(column);
  }

  private static Class<?> mergeColumnType(String column) {
    return switch (column) {
      case "processing_complete" -> Boolean.class;
      case "currency_amount" -> Double.class;
      case "bucket_id" -> Integer.class;
      case "attempt_id", "rra_key" -> Long.class;
      case "sort_ts" -> java.time.OffsetDateTime.class;
      default -> String.class;
    };
  }

  private List<TransactionEvidenceProjection> pageMergedEvidence(
      Table<?> merged, Table<?> ruleHitMatches, String sortDirection, int size, long offset) {
    Field<String> recordKey = merged.field("record_key", String.class);
    Field<java.time.OffsetDateTime> sortTs = merged.field("sort_ts", java.time.OffsetDateTime.class);

    var page =
        dsl.select(merged.fields())
            .from(merged)
            .orderBy(evidenceOrder(sortDirection, sortTs, recordKey))
            .limit(size)
            .offset(offset)
            .asTable("page");

    return selectEvidenceProjection(page, ruleHitMatches)
        .orderBy(
            evidenceOrder(
                sortDirection,
                page.field("sort_ts", java.time.OffsetDateTime.class),
                page.field("record_key", String.class)))
        .fetch(TransactionReportRepository::toEvidenceProjection);
  }

  private static List<OrderField<?>> evidenceOrder(
      String sortDirection, Field<java.time.OffsetDateTime> sortTs, Field<String> recordKey) {
    List<OrderField<?>> order = new ArrayList<>();
    order.add("ASC".equals(sortDirection) ? sortTs.asc().nullsLast() : sortTs.desc().nullsLast());
    order.add(recordKey.asc());
    return order;
  }

  private org.jooq.SelectConditionStep<Record> selectEvidenceProjection(
      Table<?> page, Table<?> ruleHitMatches) {
    Field<String> pIdentifier = page.field("identifier", String.class);
    Field<String> pEvidenceSource = page.field("evidence_source", String.class);
    Field<Long> pRraKey = page.field("rra_key", Long.class);
    Field<Double> pCurrencyAmount = page.field("currency_amount", Double.class);
    Field<String> pCurrencyCode = page.field("currency_code", String.class);
    Field<String> pTransactionDate = page.field("transaction_date", String.class);
    Field<String> pSendDate = page.field("send_date", String.class);

    Field<Long> rraKeyJoin = DSL.field(page.getName() + ".rra_key", Long.class);

    Field<Double> currencyAmountOut =
        DSL.when(pEvidenceSource.eq("JOURNEY"), DSL.coalesce(RRA.S_LOCAL_PRINCIPAL, RRA.R_LOCAL_PRINCIPAL))
            .otherwise(pCurrencyAmount)
            .as("currencyAmount");
    Field<String> currencyCodeOut =
        DSL.when(pEvidenceSource.eq("JOURNEY"), DSL.coalesce(RRA.S_CURRENCY, RRA.R_CURRENCY))
            .otherwise(pCurrencyCode)
            .as("currencyCode");
    Field<String> transactionDateOut =
        DSL.when(pEvidenceSource.eq("JOURNEY"), DSL.coalesce(RRA.S_DATE, RRA.R_DATE))
            .otherwise(pTransactionDate)
            .as("transactionDate");
    Field<String> sendDateOut =
        DSL.when(pEvidenceSource.eq("JOURNEY"), RRA.GROUP_SEND_DATE).otherwise(pSendDate).as("sendDate");

    var rollupRuleHits = rollupRuleHits(ruleHitMatches, pIdentifier);

    return dsl.select(
            page.field("record_key", String.class).as("recordKey"),
            pIdentifier.as("identifier"),
            page.field("mtcn", String.class).as("mtcn"),
            page.field("evidence_batch_id", String.class).as("batchId"),
            pEvidenceSource.as("evidenceSource"),
            page.field("stage", String.class).as("stage"),
            page.field("status", String.class).as("status"),
            page.field("outcome", String.class).as("outcome"),
            page.field("comments", String.class).as("comments"),
            page.field("skip_reason", String.class).as("skipReason"),
            page.field("rule_id", String.class).as("ruleId"),
            page.field("exclusion_reason", String.class).as("exclusionReason"),
            page.field("exclusion_strategy", String.class).as("exclusionStrategy"),
            page.field("reported_batch_id", String.class).as("reportedBatchId"),
            page.field("reporting_timestamp", String.class).as("reportingTimestamp"),
            page.field("modified_at", String.class).as("modifiedAt"),
            page.field("processing_complete", Boolean.class).as("processingComplete"),
            currencyAmountOut,
            currencyCodeOut,
            transactionDateOut,
            page.field("transaction_side", String.class).as("transactionSide"),
            page.field("txn_source", String.class).as("txnSource"),
            page.field("activity_type", String.class).as("activityType"),
            sendDateOut,
            page.field("galactic_id", String.class).as("galacticId"),
            page.field("bucket_id", Integer.class).as("bucketId"),
            page.field("attempt_id", Long.class).as("attemptId"),
            RRA.S_PARTY_NAME.as("senderName"),
            RRA.R_PARTY_NAME.as("receiverName"),
            RRA.S_PARTY_CITY.as("senderCity"),
            RRA.S_PARTY_COUNTRY_OF_RESIDENCE.as("senderCountry"),
            RRA.S_PARTY_PHONE_NUMBER.as("senderPhone"),
            RRA.S_PARTY_DATE_OF_BIRTH.as("senderDateOfBirth"),
            RRA.S_PARTY_ID_TYPE.as("senderIdType"),
            RRA.S_PARTY_ID_NUMBER.as("senderIdNumber"),
            RRA.R_PARTY_CITY.as("receiverCity"),
            RRA.R_PARTY_COUNTRY_OF_RESIDENCE.as("receiverCountry"),
            RRA.R_PARTY_PHONE_NUMBER.as("receiverPhone"),
            RRA.R_PARTY_DATE_OF_BIRTH.as("receiverDateOfBirth"),
            RRA.R_PARTY_ID_TYPE.as("receiverIdType"),
            RRA.R_PARTY_ID_NUMBER.as("receiverIdNumber"),
            RRA.TXN_STATUS.as("transactionStatus"),
            RRA.SUB_STATUS.as("transactionSubStatus"),
            DSL.coalesce(rollupRuleHits, DSL.inline("[]")).as("ruleHitsJson"))
        .from(page)
        .leftJoin(RRA)
        .on(RRA.TXN_SUR_KEY.eq(pRraKey))
        .where(DSL.trueCondition());
  }

  /** {@code LEFT JOIN LATERAL (SELECT json_agg(json_build_object(...)) ...) rollup ON TRUE}. */
  private Field<String> rollupRuleHits(Table<?> ruleHitMatches, Field<String> identifier) {
    Field<String> rhmMatchedIdentifier = ruleHitMatches.field("matched_identifier", String.class);
    Field<String> rhmRuleId = ruleHitMatches.field("rule_id", String.class);
    Field<Boolean> rhmIsReported = ruleHitMatches.field("is_reported", Boolean.class);
    Field<LocalDateTime> rhmReportingTimestamp =
        ruleHitMatches.field("reporting_timestamp", LocalDateTime.class);
    Field<Integer> rhmBucketId = ruleHitMatches.field("bucket_id", Integer.class);
    Field<Long> rhmAttemptId = ruleHitMatches.field("attempt_id", Long.class);

    var rollup =
        DSL.lateral(
                dsl.select(
                        DSL.jsonArrayAgg(
                                DSL.jsonObject(
                                    DSL.jsonEntry("ruleId", rhmRuleId),
                                    DSL.jsonEntry("isReported", rhmIsReported),
                                    DSL.jsonEntry("reportingTimestamp", rhmReportingTimestamp.cast(SQLDataType.CLOB)),
                                    DSL.jsonEntry("bucketId", rhmBucketId),
                                    DSL.jsonEntry("attemptId", rhmAttemptId)))
                            .orderBy(rhmRuleId)
                            .cast(SQLDataType.CLOB)
                            .as("rule_hits_json"))
                    .from(ruleHitMatches)
                    .where(rhmMatchedIdentifier.isNotNull())
                    .and(rhmMatchedIdentifier.eq(identifier)))
            .asTable("rollup");

    return DSL.field(
        dsl.select(rollup.field("rule_hits_json", String.class)).from(rollup));
  }

  public Optional<TransactionReportContextProjection> findReportContext(
      int reportGroupId, String batchId, int sequenceNumber) {
    return dsl.select(
            RECONCILIATION.RPT_GRP_ID.as("reportGroupId"),
            RECONCILIATION.RPT_GRP_NAME.as("reportGroupName"),
            RECONCILIATION.BATCH_ID.as("batchId"),
            RECONCILIATION.SEQ_NO.as("sequenceNumber"),
            RECONCILIATION.RPT_FROM_DATE.as("reportingPeriodFrom"),
            RECONCILIATION.RPT_TO_DATE.as("reportingPeriodTo"),
            DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0).cast(SQLDataType.BIGINT).as("selectedTransactions"),
            DSL.greatest(
                    DSL.coalesce(RECONCILIATION.TXN_SELECTED, 0)
                        .sub(DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0)),
                    DSL.inline(0))
                .cast(SQLDataType.BIGINT)
                .as("attemptsFound"),
            DSL.coalesce(RECONCILIATION.TXN_MISSING_ATTEMPT_COUNT, 0).cast(SQLDataType.BIGINT).as("missingAttempts"),
            DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                .cast(SQLDataType.BIGINT)
                .as("expectedEligible"),
            DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                .cast(SQLDataType.BIGINT)
                .as("actualEligible"),
            DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMED, 0).cast(SQLDataType.BIGINT).as("transformed"),
            DSL.coalesce(RECONCILIATION.ACTIVITY_TRANSFORMATION_FAILED, 0).cast(SQLDataType.BIGINT).as("failed"),
            DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0)
                .cast(SQLDataType.BIGINT)
                .as("expectedReportable"),
            DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0).cast(SQLDataType.BIGINT).as("actualReportable"),
            DSL.coalesce(RECONCILIATION.EXCLUDED_TXN, 0).cast(SQLDataType.BIGINT).as("excluded"),
            DSL.coalesce(RECONCILIATION.TXN_SIMULATED, 0).cast(SQLDataType.BIGINT).as("simulated"),
            DSL.coalesce(RECONCILIATION.ALREADY_REPORTED_COUNT, 0).cast(SQLDataType.BIGINT).as("alreadyReported"),
            DSL.coalesce(RECONCILIATION.SOFT_DEDUP_DROPPED_TXN_COUNT, 0).cast(SQLDataType.BIGINT).as("softDedup"),
            DSL.abs(
                    DSL.coalesce(RECONCILIATION.EXPECTED_REPORTABLE_TXN, 0)
                        .sub(DSL.coalesce(RECONCILIATION.ACTUAL_REPORTABLE_TXN, 0)))
                .cast(SQLDataType.BIGINT)
                .as("filtrationVariance"),
            DSL.abs(
                    DSL.coalesce(RECONCILIATION.EXPECTED_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)
                        .sub(DSL.coalesce(RECONCILIATION.ACTUAL_ACTIVITY_ELIGIBLE_FOR_TRANSFORMATION, 0)))
                .cast(SQLDataType.BIGINT)
                .as("reconciliationVariance"))
        .from(RECONCILIATION)
        .where(RECONCILIATION.RPT_GRP_ID.eq(reportGroupId))
        .and(RECONCILIATION.BATCH_ID.eq(batchId))
        .and(RECONCILIATION.SEQ_NO.eq(sequenceNumber))
        .fetchOptional(
            r ->
                new TransactionReportContextProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("batchId", String.class),
                    r.get("sequenceNumber", int.class),
                    r.get("reportingPeriodFrom", String.class),
                    r.get("reportingPeriodTo", String.class),
                    r.get("selectedTransactions", long.class),
                    r.get("attemptsFound", long.class),
                    r.get("missingAttempts", long.class),
                    r.get("expectedEligible", long.class),
                    r.get("actualEligible", long.class),
                    r.get("transformed", long.class),
                    r.get("failed", long.class),
                    r.get("expectedReportable", long.class),
                    r.get("actualReportable", long.class),
                    r.get("excluded", long.class),
                    r.get("simulated", long.class),
                    r.get("alreadyReported", long.class),
                    r.get("softDedup", long.class),
                    r.get("filtrationVariance", long.class),
                    r.get("reconciliationVariance", long.class)));
  }

  public List<TransactionEvidenceProjection> findEvidenceRecords(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String stage,
      String outcome,
      String status,
      String sortDirection,
      int size,
      long offset) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    var merged = mergedEvidence(filtered);
    return pageMergedEvidence(merged, ruleHitMatches, sortDirection, size, offset);
  }

  public long countEvidenceRecords(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String stage,
      String outcome,
      String status) {
    var ruleHitMatches = ruleHitMatchesForBatch(reportGroupId, batchId, status);
    var evidence = evidenceForBatch(reportGroupId, batchId, ruleHitMatches);
    var filtered = filteredEvidenceForBatch(evidence, metric, search, source, stage, outcome, status);
    Field<String> evidenceBatchId = filtered.field("evidence_batch_id", String.class);
    Field<String> identifier = filtered.field("identifier", String.class);
    return dsl.select(DSL.countDistinct(DSL.row(evidenceBatchId, identifier)))
        .from(filtered)
        .fetchOne(0, long.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Period-scoped evidence (findPeriodEvidenceRecords / countPeriodEvidenceRecords /
  // findPeriodAggregate)
  // ---------------------------------------------------------------------------------------------

  private Table<?> batchScope(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String batchId) {
    return dsl.select(
            RECONCILIATION.RPT_GRP_ID,
            RECONCILIATION.BATCH_ID,
            RECONCILIATION.RPT_GRP_NAME,
            RECONCILIATION.EXCLUDED_TXN)
        .from(RECONCILIATION)
        .where(RECONCILIATION.CREATED_TIMESTAMP.ge(fromTimestamp))
        .and(RECONCILIATION.CREATED_TIMESTAMP.lt(toTimestampExclusive))
        .and(filterByCountry ? RECONCILIATION.RPT_GRP_ID.in(reportGroupIds) : DSL.trueCondition())
        .and(filterByReportGroup ? RECONCILIATION.RPT_GRP_ID.eq(reportGroupId) : DSL.trueCondition())
        .and(
            batchId.isEmpty()
                ? DSL.trueCondition()
                : DSL.lower(RECONCILIATION.BATCH_ID).like(DSL.lower(DSL.inline("%" + batchId + "%"))))
        .asTable("batch_scope");
  }

  public PeriodAggregateProjection findPeriodAggregate(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId) {
    var scope =
        batchScope(
            fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    Field<Integer> bsRptGrpId = scope.field("rpt_grp_id", Integer.class);
    Field<String> bsBatchId = scope.field("batch_id", String.class);
    Field<String> bsRptGrpName = scope.field("rpt_grp_name", String.class);
    Field<Integer> bsExcludedTxn = scope.field("excluded_txn", Integer.class);

    return dsl.select(
            DSL.countDistinct(DSL.row(bsRptGrpId, bsBatchId)).as("batchCount"),
            DSL.coalesce(DSL.sum(bsExcludedTxn), DSL.inline(BigDecimal.ZERO)).as("totalExcluded"),
            DSL.max(bsRptGrpName).as("reportGroupName"))
        .from(scope)
        .fetchOne(
            r ->
                new PeriodAggregateProjectionImpl(
                    r.get("batchCount", long.class),
                    r.get("totalExcluded", long.class),
                    r.get("reportGroupName", String.class)));
  }

  private Table<?> ruleHitMatchesForPeriod(Table<?> batchScope, String status) {
    Field<Integer> bsRptGrpId = batchScope.field("rpt_grp_id", Integer.class);
    Field<String> bsBatchId = batchScope.field("batch_id", String.class);

    if (!("ALL".equals(status) || "REPORTED".equals(status) || "NOT_REPORTED".equals(status))) {
      return dsl.select(RULE_HIT_TABLE.fields())
          .select(DSL.cast(null, SQLDataType.CLOB).as("matched_identifier"))
          .from(RULE_HIT_TABLE)
          .where(DSL.falseCondition())
          .asTable("rule_hit_matches");
    }

    Field<String> byIdentifier =
        DSL.field(
            dsl.select(JOURNEY.IDENTIFIER)
                .from(JOURNEY)
                .join(batchScope)
                .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
                .and(bsBatchId.eq(JOURNEY.BATCH_ID))
                .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
                .and(matchesDigitsOnly(JOURNEY.IDENTIFIER))
                .and(JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT).eq(RULE_HIT_TABLE.EXTERNAL_TXN_KEY))
                .limit(1));
    Field<String> byMtcn =
        DSL.field(
            dsl.select(JOURNEY.IDENTIFIER)
                .from(JOURNEY)
                .join(batchScope)
                .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
                .and(bsBatchId.eq(JOURNEY.BATCH_ID))
                .where(JOURNEY.RPT_GRP_ID.eq(RULE_HIT_TABLE.RPT_GRP_ID))
                .and(JOURNEY.MTCN.eq(RULE_HIT_TABLE.MTCN))
                .limit(1));

    return dsl.select(RULE_HIT_TABLE.fields())
        .select(DSL.coalesce(byIdentifier, byMtcn).as("matched_identifier"))
        .from(RULE_HIT_TABLE)
        .where(
            RULE_HIT_TABLE.RPT_GRP_ID.in(dsl.selectDistinct(bsRptGrpId).from(batchScope)))
        .asTable("rule_hit_matches");
  }

  private Table<?> evidenceForPeriod(Table<?> batchScope, Table<?> ruleHitMatches) {
    Field<Integer> bsRptGrpId = batchScope.field("rpt_grp_id", Integer.class);
    Field<String> bsBatchId = batchScope.field("batch_id", String.class);

    var journeyBranch =
        dsl.select(
            DSL.concat(
                    DSL.inline("JOURNEY:"), JOURNEY.RPT_GRP_ID, DSL.inline(":"), JOURNEY.BATCH_ID, DSL.inline(":"),
                    JOURNEY.IDENTIFIER)
                .as("record_key"),
            JOURNEY.IDENTIFIER.as("identifier"),
            JOURNEY.MTCN.as("mtcn"),
            JOURNEY.BATCH_ID.as("evidence_batch_id"),
            DSL.inline("JOURNEY").as("evidence_source"),
            JOURNEY.STAGE.as("stage"),
            JOURNEY.STATUS.as("status"),
            journeyOutcome(JOURNEY.STATUS).as("outcome"),
            JOURNEY.COMMENTS.as("comments"),
            JOURNEY.SKIP_REASON.as("skip_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("rule_id"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_strategy"),
            DSL.cast(null, SQLDataType.CLOB).as("reported_batch_id"),
            JOURNEY.REPORTING_TIMESTAMP_LATEST.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as("modified_at"),
            JOURNEY.MODIFIED_TIMESTAMP.as("sort_ts"),
            JOURNEY.PROCESSING_COMPLETE.as("processing_complete"),
            DSL.cast(null, SQLDataType.DOUBLE).as("currency_amount"),
            DSL.cast(null, SQLDataType.CLOB).as("currency_code"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_date"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_side"),
            DSL.cast(null, SQLDataType.CLOB).as("txn_source"),
            DSL.cast(null, SQLDataType.CLOB).as("activity_type"),
            DSL.cast(null, SQLDataType.CLOB).as("send_date"),
            DSL.cast(null, SQLDataType.CLOB).as("galactic_id"),
            DSL.cast(null, SQLDataType.INTEGER).as("bucket_id"),
            DSL.cast(null, SQLDataType.BIGINT).as("attempt_id"),
            DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT))
                .as("rra_key"))
            .from(JOURNEY)
            .join(batchScope)
            .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
            .and(bsBatchId.eq(JOURNEY.BATCH_ID));

    var exclusionBranch =
        dsl.select(
            DSL.concat(
                    DSL.inline("EXCLUSION:"),
                    EXCLUSION_AUDIT.BUCKET_ID,
                    DSL.inline(":"),
                    EXCLUSION_AUDIT.RULE_ID,
                    DSL.inline(":"),
                    EXCLUSION_AUDIT.ATTEMPT_ID)
                .as("record_key"),
            DSL.coalesce(
                    EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.cast(SQLDataType.CLOB),
                    EXCLUSION_AUDIT.ATTEMPT_ID.cast(SQLDataType.CLOB))
                .as("identifier"),
            EXCLUSION_AUDIT.MTCN.as("mtcn"),
            EXCLUSION_AUDIT.PROCESSING_BATCH_ID.as("evidence_batch_id"),
            DSL.inline("EXCLUSION_AUDIT").as("evidence_source"),
            DSL.inline("EXCLUSION").as("stage"),
            DSL.inline("EXCLUDED").as("status"),
            DSL.inline("EXCLUDED").as("outcome"),
            DSL.cast(null, SQLDataType.CLOB).as("comments"),
            DSL.cast(null, SQLDataType.CLOB).as("skip_reason"),
            EXCLUSION_AUDIT.RULE_ID.as("rule_id"),
            EXCLUSION_AUDIT.EXCLUSION_REASON_ID.as("exclusion_reason"),
            EXCLUSION_AUDIT.EXCLUSION_STRATEGY.as("exclusion_strategy"),
            EXCLUSION_AUDIT.REPORTED_BATCH_ID.as("reported_batch_id"),
            EXCLUSION_AUDIT.REPORTING_TIMESTAMP.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            EXCLUSION_AUDIT.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as("modified_at"),
            DSL.field(
                    "{0} at time zone 'UTC'",
                    SQLDataType.TIMESTAMPWITHTIMEZONE, EXCLUSION_AUDIT.MODIFIED_TIMESTAMP)
                .as("sort_ts"),
            DSL.inline(true).as("processing_complete"),
            DSL.cast(null, SQLDataType.DOUBLE).as("currency_amount"),
            DSL.cast(null, SQLDataType.CLOB).as("currency_code"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_date"),
            DSL.cast(null, SQLDataType.CLOB).as("transaction_side"),
            DSL.cast(null, SQLDataType.CLOB).as("txn_source"),
            DSL.cast(null, SQLDataType.CLOB).as("activity_type"),
            DSL.cast(null, SQLDataType.CLOB).as("send_date"),
            DSL.cast(null, SQLDataType.CLOB).as("galactic_id"),
            EXCLUSION_AUDIT.BUCKET_ID.as("bucket_id"),
            EXCLUSION_AUDIT.ATTEMPT_ID.as("attempt_id"),
            EXCLUSION_AUDIT.EXTERNAL_TXN_KEY.as("rra_key"))
            .from(EXCLUSION_AUDIT)
            .join(batchScope)
            .on(bsRptGrpId.eq(EXCLUSION_AUDIT.RPT_GRP_ID))
            .and(bsBatchId.eq(EXCLUSION_AUDIT.PROCESSING_BATCH_ID));

    Field<Integer> rhmBucketId = ruleHitMatches.field("bucket_id", Integer.class);
    Field<String> rhmRuleId = ruleHitMatches.field("rule_id", String.class);
    Field<Long> rhmAttemptId = ruleHitMatches.field("attempt_id", Long.class);
    Field<String> rhmMatchedIdentifier = ruleHitMatches.field("matched_identifier", String.class);
    Field<String> rhmMtcn = ruleHitMatches.field("mtcn", String.class);
    Field<String> rhmEfileBatchId = ruleHitMatches.field("efile_batch_id", String.class);
    Field<Boolean> rhmIsReported = ruleHitMatches.field("is_reported", Boolean.class);
    Field<String> rhmExclusionReasonId = ruleHitMatches.field("exclusion_reason_id", String.class);
    Field<String> rhmReportedBatchId = ruleHitMatches.field("reported_batch_id", String.class);
    Field<LocalDateTime> rhmReportingTimestamp =
        ruleHitMatches.field("reporting_timestamp", LocalDateTime.class);
    Field<java.time.OffsetDateTime> rhmModifiedTimestamp =
        ruleHitMatches.field("modified_timestamp", java.time.OffsetDateTime.class);
    Field<BigDecimal> rhmCurrencyAmount = ruleHitMatches.field("rule_currency_amount", BigDecimal.class);
    Field<String> rhmCurrencyCode = ruleHitMatches.field("rule_iso_currency_code", String.class);
    Field<LocalDateTime> rhmTransactionDate =
        ruleHitMatches.field("transaction_date", LocalDateTime.class);
    Field<String> rhmTransactionSide = ruleHitMatches.field("transaction_side", String.class);
    Field<String> rhmSource = ruleHitMatches.field("source", String.class);
    Field<String> rhmActivityType = ruleHitMatches.field("activity_type", String.class);
    Field<java.time.LocalDate> rhmSendDate = ruleHitMatches.field("send_date", java.time.LocalDate.class);
    Field<String> rhmGalacticId = ruleHitMatches.field("galactic_id", String.class);
    Field<Long> rhmExternalTxnKey = ruleHitMatches.field("external_txn_key", Long.class);

    var ruleHitBranch =
        dsl.select(
            DSL.concat(
                    DSL.inline("RULE_HIT:"), rhmBucketId, DSL.inline(":"), rhmRuleId, DSL.inline(":"), rhmAttemptId)
                .as("record_key"),
            rhmMatchedIdentifier.as("identifier"),
            rhmMtcn.as("mtcn"),
            rhmEfileBatchId.as("evidence_batch_id"),
            DSL.inline("RULE_HIT").as("evidence_source"),
            DSL.inline("RULE_HIT").as("stage"),
            DSL.when(rhmIsReported, DSL.inline("REPORTED")).otherwise(DSL.inline("NOT_REPORTED")).as("status"),
            DSL.when(rhmIsReported, DSL.inline("SUCCESS")).otherwise(DSL.inline("PENDING")).as("outcome"),
            DSL.cast(null, SQLDataType.CLOB).as("comments"),
            DSL.cast(null, SQLDataType.CLOB).as("skip_reason"),
            rhmRuleId.as("rule_id"),
            rhmExclusionReasonId.as("exclusion_reason"),
            DSL.cast(null, SQLDataType.CLOB).as("exclusion_strategy"),
            rhmReportedBatchId.as("reported_batch_id"),
            rhmReportingTimestamp.cast(SQLDataType.CLOB).as("reporting_timestamp"),
            rhmModifiedTimestamp.cast(SQLDataType.CLOB).as("modified_at"),
            rhmModifiedTimestamp.as("sort_ts"),
            DSL.inline(true).as("processing_complete"),
            rhmCurrencyAmount.cast(SQLDataType.DOUBLE).as("currency_amount"),
            rhmCurrencyCode.as("currency_code"),
            rhmTransactionDate.cast(SQLDataType.CLOB).as("transaction_date"),
            rhmTransactionSide.as("transaction_side"),
            rhmSource.as("txn_source"),
            rhmActivityType.as("activity_type"),
            rhmSendDate.cast(SQLDataType.CLOB).as("send_date"),
            rhmGalacticId.as("galactic_id"),
            rhmBucketId.as("bucket_id"),
            rhmAttemptId.as("attempt_id"),
            rhmExternalTxnKey.as("rra_key"))
            .from(ruleHitMatches)
            .where(rhmMatchedIdentifier.isNotNull());

    return journeyBranch.unionAll(exclusionBranch).unionAll(ruleHitBranch).asTable("evidence");
  }

  private Table<?> filteredEvidenceForPeriod(
      Table<?> evidence, String search, String outcome, String status) {
    Field<String> identifier = evidence.field("identifier", String.class);
    Field<String> mtcn = evidence.field("mtcn", String.class);
    Field<String> outcomeField = evidence.field("outcome", String.class);
    Field<String> statusField = evidence.field("status", String.class);

    return dsl.select(evidence.fields())
        .from(evidence)
        .where(searchScope(search, identifier, mtcn))
        .and("ALL".equals(outcome) ? DSL.trueCondition() : outcomeField.eq(outcome))
        .and("ALL".equals(status) ? DSL.trueCondition() : DSL.upper(DSL.coalesce(statusField, "")).eq(status))
        .asTable("filtered_evidence");
  }

  public List<TransactionEvidenceProjection> findPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String search,
      String outcome,
      String status,
      String sortDirection,
      int size,
      long offset) {
    var scope =
        batchScope(
            fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    var merged = mergedEvidence(filtered);
    return pageMergedEvidence(merged, ruleHitMatches, sortDirection, size, offset);
  }

  public long countPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String search,
      String outcome,
      String status) {
    var scope =
        batchScope(
            fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, "");
    var ruleHitMatches = ruleHitMatchesForPeriod(scope, status);
    var evidence = evidenceForPeriod(scope, ruleHitMatches);
    var filtered = filteredEvidenceForPeriod(evidence, search, outcome, status);
    Field<String> evidenceBatchId = filtered.field("evidence_batch_id", String.class);
    Field<String> identifier = filtered.field("identifier", String.class);
    return dsl.select(DSL.countDistinct(DSL.row(evidenceBatchId, identifier)))
        .from(filtered)
        .fetchOne(0, long.class);
  }

  private static TransactionEvidenceProjection toEvidenceProjection(Record r) {
    Double currencyAmount = r.get("currencyAmount", Double.class);
    return new TransactionEvidenceProjectionImpl(
        r.get("recordKey", String.class),
        r.get("identifier", String.class),
        r.get("mtcn", String.class),
        r.get("batchId", String.class),
        r.get("evidenceSource", String.class),
        r.get("stage", String.class),
        r.get("status", String.class),
        r.get("outcome", String.class),
        r.get("comments", String.class),
        r.get("skipReason", String.class),
        r.get("ruleId", String.class),
        r.get("exclusionReason", String.class),
        r.get("exclusionStrategy", String.class),
        r.get("reportedBatchId", String.class),
        r.get("reportingTimestamp", String.class),
        r.get("modifiedAt", String.class),
        r.get("processingComplete", Boolean.class),
        currencyAmount == null ? null : BigDecimal.valueOf(currencyAmount),
        r.get("currencyCode", String.class),
        r.get("transactionDate", String.class),
        r.get("transactionSide", String.class),
        r.get("txnSource", String.class),
        r.get("activityType", String.class),
        r.get("sendDate", String.class),
        r.get("galacticId", String.class),
        r.get("bucketId", Integer.class),
        r.get("attemptId", Long.class),
        r.get("senderName", String.class),
        r.get("receiverName", String.class),
        r.get("senderCity", String.class),
        r.get("senderCountry", String.class),
        r.get("senderPhone", String.class),
        r.get("senderDateOfBirth", String.class),
        r.get("senderIdType", String.class),
        r.get("senderIdNumber", String.class),
        r.get("receiverCity", String.class),
        r.get("receiverCountry", String.class),
        r.get("receiverPhone", String.class),
        r.get("receiverDateOfBirth", String.class),
        r.get("receiverIdType", String.class),
        r.get("receiverIdNumber", String.class),
        r.get("transactionStatus", String.class),
        r.get("transactionSubStatus", String.class),
        r.get("ruleHitsJson", String.class));
  }

  public interface PeriodAggregateProjection {
    long getBatchCount();

    long getTotalExcluded();

    String getReportGroupName();
  }

  private record PeriodAggregateProjectionImpl(long batchCount, long totalExcluded, String reportGroupName)
      implements PeriodAggregateProjection {
    @Override
    public long getBatchCount() {
      return batchCount;
    }

    @Override
    public long getTotalExcluded() {
      return totalExcluded;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }
  }

  public interface TransactionReportContextProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    long getSelectedTransactions();

    long getAttemptsFound();

    long getMissingAttempts();

    long getExpectedEligible();

    long getActualEligible();

    long getTransformed();

    long getFailed();

    long getExpectedReportable();

    long getActualReportable();

    long getExcluded();

    long getSimulated();

    long getAlreadyReported();

    long getSoftDedup();

    long getFiltrationVariance();

    long getReconciliationVariance();
  }

  private record TransactionReportContextProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      String batchId,
      int sequenceNumber,
      String reportingPeriodFrom,
      String reportingPeriodTo,
      long selectedTransactions,
      long attemptsFound,
      long missingAttempts,
      long expectedEligible,
      long actualEligible,
      long transformed,
      long failed,
      long expectedReportable,
      long actualReportable,
      long excluded,
      long simulated,
      long alreadyReported,
      long softDedup,
      long filtrationVariance,
      long reconciliationVariance)
      implements TransactionReportContextProjection {
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
    public long getSelectedTransactions() {
      return selectedTransactions;
    }

    @Override
    public long getAttemptsFound() {
      return attemptsFound;
    }

    @Override
    public long getMissingAttempts() {
      return missingAttempts;
    }

    @Override
    public long getExpectedEligible() {
      return expectedEligible;
    }

    @Override
    public long getActualEligible() {
      return actualEligible;
    }

    @Override
    public long getTransformed() {
      return transformed;
    }

    @Override
    public long getFailed() {
      return failed;
    }

    @Override
    public long getExpectedReportable() {
      return expectedReportable;
    }

    @Override
    public long getActualReportable() {
      return actualReportable;
    }

    @Override
    public long getExcluded() {
      return excluded;
    }

    @Override
    public long getSimulated() {
      return simulated;
    }

    @Override
    public long getAlreadyReported() {
      return alreadyReported;
    }

    @Override
    public long getSoftDedup() {
      return softDedup;
    }

    @Override
    public long getFiltrationVariance() {
      return filtrationVariance;
    }

    @Override
    public long getReconciliationVariance() {
      return reconciliationVariance;
    }
  }

  public interface TransactionEvidenceProjection {
    String getRecordKey();

    String getIdentifier();

    String getMtcn();

    String getBatchId();

    String getEvidenceSource();

    String getStage();

    String getStatus();

    String getOutcome();

    String getComments();

    String getSkipReason();

    String getRuleId();

    String getExclusionReason();

    String getExclusionStrategy();

    String getReportedBatchId();

    String getReportingTimestamp();

    String getModifiedAt();

    Boolean getProcessingComplete();

    BigDecimal getCurrencyAmount();

    String getCurrencyCode();

    String getTransactionDate();

    String getTransactionSide();

    String getTxnSource();

    String getActivityType();

    String getSendDate();

    String getGalacticId();

    Integer getBucketId();

    Long getAttemptId();

    String getSenderName();

    String getReceiverName();

    String getSenderCity();

    String getSenderCountry();

    String getSenderPhone();

    String getSenderDateOfBirth();

    String getSenderIdType();

    String getSenderIdNumber();

    String getReceiverCity();

    String getReceiverCountry();

    String getReceiverPhone();

    String getReceiverDateOfBirth();

    String getReceiverIdType();

    String getReceiverIdNumber();

    String getTransactionStatus();

    String getTransactionSubStatus();

    String getRuleHitsJson();
  }

  private record TransactionEvidenceProjectionImpl(
      String recordKey,
      String identifier,
      String mtcn,
      String batchId,
      String evidenceSource,
      String stage,
      String status,
      String outcome,
      String comments,
      String skipReason,
      String ruleId,
      String exclusionReason,
      String exclusionStrategy,
      String reportedBatchId,
      String reportingTimestamp,
      String modifiedAt,
      Boolean processingComplete,
      BigDecimal currencyAmount,
      String currencyCode,
      String transactionDate,
      String transactionSide,
      String txnSource,
      String activityType,
      String sendDate,
      String galacticId,
      Integer bucketId,
      Long attemptId,
      String senderName,
      String receiverName,
      String senderCity,
      String senderCountry,
      String senderPhone,
      String senderDateOfBirth,
      String senderIdType,
      String senderIdNumber,
      String receiverCity,
      String receiverCountry,
      String receiverPhone,
      String receiverDateOfBirth,
      String receiverIdType,
      String receiverIdNumber,
      String transactionStatus,
      String transactionSubStatus,
      String ruleHitsJson)
      implements TransactionEvidenceProjection {
    @Override
    public String getRecordKey() {
      return recordKey;
    }

    @Override
    public String getIdentifier() {
      return identifier;
    }

    @Override
    public String getMtcn() {
      return mtcn;
    }

    @Override
    public String getBatchId() {
      return batchId;
    }

    @Override
    public String getEvidenceSource() {
      return evidenceSource;
    }

    @Override
    public String getStage() {
      return stage;
    }

    @Override
    public String getStatus() {
      return status;
    }

    @Override
    public String getOutcome() {
      return outcome;
    }

    @Override
    public String getComments() {
      return comments;
    }

    @Override
    public String getSkipReason() {
      return skipReason;
    }

    @Override
    public String getRuleId() {
      return ruleId;
    }

    @Override
    public String getExclusionReason() {
      return exclusionReason;
    }

    @Override
    public String getExclusionStrategy() {
      return exclusionStrategy;
    }

    @Override
    public String getReportedBatchId() {
      return reportedBatchId;
    }

    @Override
    public String getReportingTimestamp() {
      return reportingTimestamp;
    }

    @Override
    public String getModifiedAt() {
      return modifiedAt;
    }

    @Override
    public Boolean getProcessingComplete() {
      return processingComplete;
    }

    @Override
    public BigDecimal getCurrencyAmount() {
      return currencyAmount;
    }

    @Override
    public String getCurrencyCode() {
      return currencyCode;
    }

    @Override
    public String getTransactionDate() {
      return transactionDate;
    }

    @Override
    public String getTransactionSide() {
      return transactionSide;
    }

    @Override
    public String getTxnSource() {
      return txnSource;
    }

    @Override
    public String getActivityType() {
      return activityType;
    }

    @Override
    public String getSendDate() {
      return sendDate;
    }

    @Override
    public String getGalacticId() {
      return galacticId;
    }

    @Override
    public Integer getBucketId() {
      return bucketId;
    }

    @Override
    public Long getAttemptId() {
      return attemptId;
    }

    @Override
    public String getSenderName() {
      return senderName;
    }

    @Override
    public String getReceiverName() {
      return receiverName;
    }

    @Override
    public String getSenderCity() {
      return senderCity;
    }

    @Override
    public String getSenderCountry() {
      return senderCountry;
    }

    @Override
    public String getSenderPhone() {
      return senderPhone;
    }

    @Override
    public String getSenderDateOfBirth() {
      return senderDateOfBirth;
    }

    @Override
    public String getSenderIdType() {
      return senderIdType;
    }

    @Override
    public String getSenderIdNumber() {
      return senderIdNumber;
    }

    @Override
    public String getReceiverCity() {
      return receiverCity;
    }

    @Override
    public String getReceiverCountry() {
      return receiverCountry;
    }

    @Override
    public String getReceiverPhone() {
      return receiverPhone;
    }

    @Override
    public String getReceiverDateOfBirth() {
      return receiverDateOfBirth;
    }

    @Override
    public String getReceiverIdType() {
      return receiverIdType;
    }

    @Override
    public String getReceiverIdNumber() {
      return receiverIdNumber;
    }

    @Override
    public String getTransactionStatus() {
      return transactionStatus;
    }

    @Override
    public String getTransactionSubStatus() {
      return transactionSubStatus;
    }

    @Override
    public String getRuleHitsJson() {
      return ruleHitsJson;
    }
  }
}
