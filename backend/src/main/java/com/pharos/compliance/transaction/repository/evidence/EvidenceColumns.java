package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.RegReportableActivity.REG_REPORTABLE_ACTIVITY;
import static com.pharos.compliance.jooq.tables.ReportBatchInfo.REPORT_BATCH_INFO;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;
import static com.pharos.compliance.jooq.tables.RuleHit.RULE_HIT;
import static com.pharos.compliance.jooq.tables.RuleHitExclusionAudit.RULE_HIT_EXCLUSION_AUDIT;
import java.util.List;

/**
 * Column-name constants and table aliases shared across the transaction-evidence pipelines in
 * this package ({@link BatchEvidenceQueries}, {@link PeriodEvidenceQueries}, {@link
 * OverviewEvidenceQueries}, {@link EvidencePaginator}, {@link RuleHitMatcher}). One place for
 * every string every pipeline agrees on, so renaming or re-checking a column touches one file
 * instead of hunting across five.
 */
public final class EvidenceColumns {
  public static final String ACTIVITY_TYPE = "activity_type";
  public static final String ATTEMPT_ID_ALIAS = "attemptId";
  public static final String ATTEMPT_ID_COLUMN = "attempt_id";
  public static final String BATCH_GENERATED_COLUMN = "batch_generated";
  public static final String BATCH_ID_ALIAS = "batchId";
  public static final String BATCH_ID_COLUMN = "batch_id";
  public static final String BUCKET_ID_ALIAS = "bucketId";
  public static final String BUCKET_ID_COLUMN = "bucket_id";
  public static final String COMMENTS = "comments";
  public static final String CURRENCY_AMOUNT = "currency_amount";
  public static final String CURRENCY_CODE = "currency_code";
  public static final String EVER_EXCLUDED_COLUMN = "ever_excluded";
  public static final String EVER_REPORTED_COLUMN = "ever_reported";
  public static final String REASON_COLUMN = "reason";
  public static final String NOT_REPORTED_REASON_COLUMN = "not_reported_reason";
  public static final String UNSPECIFIED_REASON = "Unspecified";
  // Same "top N, then Other" cutoff as DashboardRepository#topReasonsThenOther -- kept as literal
  // duplicates (not shared constants) for the same reason the whole roll/target pipeline is
  // duplicated here: this repository answers "give me the rows," DashboardRepository answers "give
  // me the count," and they need to agree on the bucketing without depending on each other.
  public static final String OTHER_REASON = "Other";
  public static final int TOP_REASON_LIMIT = 3;
  public static final String EVIDENCE_BATCH_ID = "evidence_batch_id";
  public static final String EVIDENCE_SOURCE = "evidence_source";
  public static final String EXCLUSION_REASON = "exclusion_reason";
  public static final String EXCLUSION_STRATEGY = "exclusion_strategy";
  public static final String GALACTIC_ID = "galactic_id";
  public static final String IDENTIFIER = "identifier";
  public static final String IDENTIFIER_BIGINT = "identifier_bigint";
  public static final String IS_REPORTED = "is_reported";
  public static final String MATCHED_IDENTIFIER = "matched_identifier";
  public static final String MODIFIED_AT = "modified_at";
  public static final String OUTCOME = "outcome";
  public static final String OUTCOME_ERROR = "ERROR";
  public static final String OUTCOME_PENDING = "PENDING";
  public static final String OUTCOME_SUCCESS = "SUCCESS";
  public static final String PROCESSING_COMPLETE = "processing_complete";
  public static final String RECORD_KEY = "record_key";
  public static final String REPORTED_BATCH_ID = "reported_batch_id";
  public static final String REPORT_GENERATION_COMPLETED = "Report Generation Completed";
  public static final String REPORT_GROUP_ID_COLUMN = "rpt_grp_id";
  public static final String REPORT_GROUP_NAME_ALIAS = "reportGroupName";
  public static final String REPORTING_TIMESTAMP_COLUMN = "reporting_timestamp";
  public static final String RRA_KEY = "rra_key";
  public static final String RULE_HIT_MATCHES = "rule_hit_matches";
  public static final String RULE_ID_ALIAS = "ruleId";
  public static final String RULE_ID_COLUMN = "rule_id";
  public static final String SEND_DATE = "send_date";
  public static final String SKIP_REASON = "skip_reason";
  public static final String SORT_TIMESTAMP = "sort_ts";
  public static final String SOURCE_EXCLUSION_AUDIT = "EXCLUSION_AUDIT";
  public static final String SOURCE_JOURNEY = "JOURNEY";
  public static final String SOURCE_RANK = "source_rank";
  // Distinct from SOURCE_RANK (latestJourneyForTarget's own ROW_NUMBER() rank column, an unrelated
  // concept for the overview path) -- rankedEvidence()'s priority column is added on top of
  // whatever filteredEvidence already carries, and the overview path's `filtered` does carry
  // SOURCE_RANK through as leftover baggage from latestJourneyForTarget. Reusing the same name
  // there produced two same-named output columns and a genuine "column reference is ambiguous"
  // error from Postgres once pageEvidence unified the overview path through rankedEvidence.
  public static final String MERGE_SOURCE_RANK = "merge_source_rank";
  public static final String SOURCE_RULE_HIT = "RULE_HIT";
  public static final String STAGE = "stage";
  public static final String STAGE_FILTRATION = "FILTRATION";
  public static final String STATUS = "status";
  public static final String TRANSACTION_DATE = "transaction_date";
  public static final String TRANSACTION_SIDE = "transaction_side";
  public static final String TRANSACTION_SOURCE = "txn_source";
  public static final String VALUE_EXCLUDED = "EXCLUDED";
  // The one journey comment report_transformation_reconciliation.excluded_txn actually sums --
  // confirmed against real production data (AUSTRALIA IFTI, 4 batches): excluded_txn equals this
  // exact count on every batch, with zero unaccounted, while EXCLUDED_BECAUSE_SML/
  // EXCLUDED_BECAUSE_ALREADY_REPORTED/EXCLUDED_SOFT_DEDUP back their own separate reconciliation
  // scalars (txn_simulated/already_reported_count/soft_dedup_dropped_txn_count) and must stay out
  // of this bucket, not fold into it. Shared by BatchEvidenceQueries and PeriodEvidenceQueries so
  // the single-batch and batch-total "Excluded" evidence conditions can't drift apart the way they
  // did before -- a wider, comment-agnostic match in the batch-total path once over-counted by
  // exactly the SML+ALREADY_REPORTED rows this constant deliberately excludes.
  public static final String VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS = "EXCLUDED_BECAUSE_EXCLUSION_EXISTS";
  public static final String VALUE_NOT_REPORTED = "NOT_REPORTED";
  public static final String VALUE_REPORTED = "REPORTED";
  public static final String REPORTING_TIMESTAMP = "reportingTimestamp";
  public static final com.pharos.compliance.jooq.tables.ReportTransformationReconciliation RECONCILIATION =
      REPORT_TRANSFORMATION_RECONCILIATION;
  public static final com.pharos.compliance.jooq.tables.RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  public static final com.pharos.compliance.jooq.tables.RuleHit RULE_HIT_TABLE = RULE_HIT;
  public static final com.pharos.compliance.jooq.tables.RuleHitExclusionAudit EXCLUSION_AUDIT = RULE_HIT_EXCLUSION_AUDIT;
  public static final com.pharos.compliance.jooq.tables.RegReportableActivity RRA = REG_REPORTABLE_ACTIVITY;
  public static final com.pharos.compliance.jooq.tables.ReportBatchInfo BATCH_INFO = REPORT_BATCH_INFO;
  /**
   * Column list shared by the merged CTE and the outer projection -- 27 fields, in the exact order
   * the original SQL's MERGED_CTE listed them, so the two stay easy to compare side by side.
   */
  public static final List<String> MERGE_COLUMNS = List.of(RECORD_KEY, "mtcn", EVIDENCE_SOURCE, STAGE, STATUS, OUTCOME, COMMENTS,
      SKIP_REASON, RULE_ID_COLUMN, EXCLUSION_REASON, EXCLUSION_STRATEGY, REPORTED_BATCH_ID, REPORTING_TIMESTAMP_COLUMN, MODIFIED_AT,
      SORT_TIMESTAMP, PROCESSING_COMPLETE, CURRENCY_AMOUNT, CURRENCY_CODE, TRANSACTION_DATE, TRANSACTION_SIDE, TRANSACTION_SOURCE,
      ACTIVITY_TYPE, SEND_DATE, GALACTIC_ID, BUCKET_ID_COLUMN, ATTEMPT_ID_COLUMN, RRA_KEY);

  private EvidenceColumns() {
  }

  public static Class<?> mergeColumnType(String column) {
    return switch (column) {
      case PROCESSING_COMPLETE -> Boolean.class;
      case CURRENCY_AMOUNT -> Double.class;
      case BUCKET_ID_COLUMN -> Integer.class;
      case ATTEMPT_ID_COLUMN, RRA_KEY -> Long.class;
      case SORT_TIMESTAMP -> java.time.OffsetDateTime.class;
      default -> String.class;
    };
  }
}
