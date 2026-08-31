package com.pharos.compliance.transaction.repository;

final class TransactionReportNativeQueries {

  static final String REPORT_CONTEXT =
      """
      SELECT
          rpt_grp_id AS "reportGroupId",
          rpt_grp_name AS "reportGroupName",
          batch_id AS "batchId",
          seq_no AS "sequenceNumber",
          rpt_from_date AS "reportingPeriodFrom",
          rpt_to_date AS "reportingPeriodTo",
          COALESCE(txn_selected, 0)::bigint AS "selectedTransactions",
          GREATEST(
              COALESCE(txn_selected, 0) - COALESCE(txn_missing_attempt_count, 0),
              0)::bigint AS "attemptsFound",
          COALESCE(txn_missing_attempt_count, 0)::bigint AS "missingAttempts",
          COALESCE(expected_activity_eligible_for_transformation, 0)::bigint
              AS "expectedEligible",
          COALESCE(actual_activity_eligible_for_transformation, 0)::bigint
              AS "actualEligible",
          COALESCE(activity_transformed, 0)::bigint AS "transformed",
          COALESCE(activity_transformation_failed, 0)::bigint AS "failed",
          COALESCE(expected_reportable_txn, 0)::bigint AS "expectedReportable",
          COALESCE(actual_reportable_txn, 0)::bigint AS "actualReportable",
          COALESCE(excluded_txn, 0)::bigint AS "excluded",
          COALESCE(txn_simulated, 0)::bigint AS "simulated",
          COALESCE(already_reported_count, 0)::bigint AS "alreadyReported",
          COALESCE(soft_dedup_dropped_txn_count, 0)::bigint AS "softDedup",
          ABS(COALESCE(expected_reportable_txn, 0)
              - COALESCE(actual_reportable_txn, 0))::bigint AS "filtrationVariance",
          ABS(COALESCE(expected_activity_eligible_for_transformation, 0)
              - COALESCE(actual_activity_eligible_for_transformation, 0))::bigint
              AS "reconciliationVariance"
      FROM pharos.report_transformation_reconciliation
      WHERE rpt_grp_id = :reportGroupId
        AND batch_id = :batchId
        AND seq_no = :sequenceNumber
      """;

  /**
   * Resolves each rule_hit row to its journey identifier, preferring the identifier ->
   * external_txn_key bridge and automatically falling back to mtcn only when no identifier match
   * exists. Rule hits are matched by rpt_grp_id + external_txn_key/mtcn only — deliberately NOT
   * also restricted to rh.efile_batch_id = :batchId, because a rule hit's own processing batch can
   * differ from the batch currently under investigation (e.g. it was originally filed under an
   * earlier attempt). The identifier lookup itself still scopes journey rows to :batchId, since
   * that determines which of *this batch's* transactions a given rule hit belongs to.
   *
   * <p>The trailing {@code (:status = 'ALL' OR :status IN ('REPORTED', 'NOT_REPORTED'))} guard is
   * a pure performance short-circuit, not a filter — rule_hit evidence's "status" column
   * (the RULE_HIT branch of the evidence CTE, below) can only ever be exactly REPORTED or NOT_REPORTED
   * (it's a two-way CASE on is_reported, not a passthrough of arbitrary source data), so this can
   * never change which rows end up in the final result. What it does do is let Postgres recognize
   * — before running a single row through the two correlated identifier-lookup subqueries above,
   * the most expensive part of this whole query — that no rule_hit row can possibly survive the
   * caller's status filter, and skip the scan (and every subquery execution) entirely. Do not add
   * this same style of guard against the JOURNEY branch's "status": that column is a raw
   * passthrough of whatever the source system wrote (see the evidence CTE below), not a closed set,
   * so it cannot be safely assumed to exclude any particular status value.
   */
  private static final String RULE_HIT_MATCHES_CTE =
      """
      rule_hit_matches AS (
          SELECT
              rh.*,
              COALESCE(
                  (SELECT j.identifier
                   FROM pharos.record_transformation_journey j
                   WHERE j.rpt_grp_id = :reportGroupId
                     AND j.batch_id = :batchId
                     AND j.identifier ~ '^[0-9]+$'
                     AND j.identifier::bigint = rh.external_txn_key
                   LIMIT 1),
                  (SELECT j2.identifier
                   FROM pharos.record_transformation_journey j2
                   WHERE j2.rpt_grp_id = :reportGroupId
                     AND j2.batch_id = :batchId
                     AND j2.mtcn = rh.mtcn
                   LIMIT 1)
              ) AS matched_identifier
          FROM pharos.rule_hit rh
          WHERE rh.rpt_grp_id = :reportGroupId
            AND (:status = 'ALL' OR :status IN ('REPORTED', 'NOT_REPORTED'))
      )
      """;

  /**
   * Union of every evidence source into one common row shape, keyed by evidence_source.
   *
   * <p>Deliberately carries only the columns that identify, filter, and sort a row — it does NOT
   * join pharos.reg_reportable_activity for the sender/receiver/currency detail, nor
   * rule_hit_rollup for the rule-hit JSON. Both of those are display-only enrichment: no predicate
   * in metric_scoped/stage_scoped/filtered_evidence and no ORDER BY key references them, and both
   * match at most one row per evidence row (reg_reportable_activity on its txn_sur_key PRIMARY
   * KEY, rule_hit_rollup on its GROUP BY key), so neither can add, remove, or duplicate rows.
   *
   * <p>Keeping them out here is what lets EVIDENCE_COUNT and the two breakdown queries — which
   * return nothing but numbers — skip that work entirely, and lets EVIDENCE_RECORDS defer it until
   * after ORDER BY/LIMIT so it enriches one page of rows instead of every candidate row. Each
   * branch instead exposes rra_key, the single value that branch would have joined
   * reg_reportable_activity on, so the deferred join stays exactly equivalent.
   */
  private static final String EVIDENCE_CTE =
      """
      , evidence AS (
          SELECT
              CONCAT('JOURNEY:', journey.identifier) AS record_key,
      """
          + TransactionEvidenceColumns.JOURNEY_COLUMNS
          + """
          FROM pharos.record_transformation_journey journey
          WHERE journey.rpt_grp_id = :reportGroupId
            AND journey.batch_id = :batchId

          UNION ALL

          SELECT
      """
          + TransactionEvidenceColumns.EXCLUSION_COLUMNS
          + """
          FROM pharos.rule_hit_exclusion_audit exclusion_audit
          WHERE exclusion_audit.rpt_grp_id = :reportGroupId
            AND exclusion_audit.processing_batch_id = :batchId
            -- Same short-circuit rationale as rule_hit_matches above: the status column on this
            -- branch is always the literal EXCLUDED, so it can never satisfy any other concrete
            -- filter value.
            AND (:status = 'ALL' OR :status = 'EXCLUDED')

          UNION ALL

          SELECT
      """
          + TransactionEvidenceColumns.RULE_HIT_BRANCH
          + """
      )
      """;

  /** Scopes evidence by source/search/metric only — the shared base every breakdown builds on. */
  private static final String METRIC_SCOPED_CTE =
      """
      , metric_scoped AS (
          SELECT *
          FROM evidence
          WHERE (:source = 'ALL' OR evidence_source = :source)
            AND (:search = ''
                 OR LOWER(identifier) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(mtcn, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (
                :metric = 'ALL'
                OR (:metric IN ('SELECTED', 'ATTEMPTS_FOUND', 'EXPECTED_ELIGIBLE',
                    'ACTUAL_ELIGIBLE', 'EXPECTED_REPORTABLE', 'ACTUAL_REPORTABLE',
                    'TRANSFORMER_OUTPUT') AND evidence_source = 'JOURNEY')
                OR (:metric = 'TRANSFORMED' AND evidence_source = 'JOURNEY'
                    AND UPPER(COALESCE(stage, '')) = 'TRANSFORMATION' AND outcome = 'SUCCESS')
                OR (:metric = 'FAILED' AND evidence_source = 'JOURNEY'
                    AND UPPER(COALESCE(stage, '')) = 'TRANSFORMATION' AND outcome = 'ERROR')
                OR (:metric = 'EXCLUDED' AND evidence_source = 'EXCLUSION_AUDIT')
                OR (:metric = 'SIMULATED' AND evidence_source = 'JOURNEY'
                    AND UPPER(COALESCE(stage, '')) = 'FILTRATION'
                    AND UPPER(COALESCE(comments, '')) = 'EXCLUDED_BECAUSE_SML')
                OR (:metric = 'ALREADY_REPORTED' AND evidence_source = 'JOURNEY'
                    AND UPPER(COALESCE(stage, '')) = 'FILTRATION'
                    AND UPPER(COALESCE(comments, ''))
                        LIKE 'EXCLUDED_BECAUSE_ALREADY_REPORTED%')
                OR (:metric = 'SOFT_DEDUP' AND evidence_source = 'JOURNEY'
                    AND UPPER(COALESCE(stage, '')) = 'FILTRATION'
                    AND (UPPER(COALESCE(comments, '')) = 'EXCLUDED_SOFT_DEDUP'
                         OR UPPER(COALESCE(comments, '')) LIKE 'EXCLUDED_REAPPEARING_%'))
                OR (:metric IN ('ACTUAL_REPORTABLE', 'TRANSFORMER_OUTPUT')
                    AND evidence_source = 'RULE_HIT')
                -- Every filtration reason at once, backing the Filtered data tile on the Data
                -- Selection card: exclusion-audit rows plus any journey row that stopped at the
                -- filtration stage (simulated, already reported, soft dedup). Missing attempts
                -- belong to that total too but have no record-level evidence in Phase 1, so they
                -- surface through the aggregate/record gap rather than as rows.
                OR (:metric = 'FILTERED'
                    AND (evidence_source = 'EXCLUSION_AUDIT'
                         OR (evidence_source = 'JOURNEY'
                             AND UPPER(COALESCE(stage, '')) = 'FILTRATION')))
            )
      )
      """;

  /**
   * metric_scoped narrowed by stage only (not outcome) — the outcome-distribution breakdown runs on
   * this, so it shows "what outcomes look like within the selected stage" while still varying
   * freely across outcomes (the dimension it charts).
   */
  private static final String STAGE_SCOPED_CTE =
      """
      , stage_scoped AS (
          SELECT *
          FROM metric_scoped
          WHERE (:stage = 'ALL' OR UPPER(COALESCE(stage, '')) = :stage)
      )
      """;

  /**
   * stage_scoped narrowed by outcome and literal status too — every filter the record list itself
   * applies. Status is independent of outcome: outcome is a normalized bucket (SUCCESS/ERROR/
   * PENDING/EXCLUDED) while status is the literal source value (e.g. FAILED vs ERROR both bucket to
   * outcome ERROR, but are distinct statuses), so it is scoped here rather than folded into the
   * stage/outcome CTEs that the (currently unrendered) breakdown queries also depend on.
   */
  private static final String FILTERED_EVIDENCE_CTE =
      """
      , filtered_evidence AS (
          SELECT *
          FROM stage_scoped
          WHERE (:outcome = 'ALL' OR outcome = :outcome)
            AND (:status = 'ALL' OR UPPER(COALESCE(status, '')) = :status)
      )
      """;

  /**
   * Fetches one page of rows only — no COUNT(*) OVER() window function. A window function forces
   * Postgres to materialize and count the entire filtered_evidence set before any row can be
   * returned, which defeats bounded top-N sort/limit execution and gets more expensive as a batch's
   * evidence grows into the thousands. The total count is fetched separately via EVIDENCE_COUNT,
   * once per request, not once per row.
   */
  static final String EVIDENCE_RECORDS =
      "WITH "
          + RULE_HIT_MATCHES_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + STAGE_SCOPED_CTE
          + FILTERED_EVIDENCE_CTE
          + TransactionEvidenceColumns.MERGED_CTE
          + """
      , page AS (
          SELECT *
          FROM merged
          ORDER BY
              CASE WHEN :sortDirection = 'ASC' THEN sort_ts END ASC NULLS LAST,
              CASE WHEN :sortDirection = 'DESC' THEN sort_ts END DESC NULLS LAST,
              record_key
          LIMIT :size OFFSET :offset
      )
      SELECT
          record_key AS "recordKey",
          report_group_id AS "reportGroupId",
          identifier AS "identifier",
          mtcn AS "mtcn",
          evidence_batch_id AS "batchId",
          evidence_source AS "evidenceSource",
          status AS "status",
          comments AS "comments",
          skip_reason AS "skipReason",
          exclusion_reason AS "exclusionReason",
          reported_batch_id AS "reportedBatchId",
          modified_at AS "modifiedAt",
          processing_complete AS "processingComplete"
      FROM page
      ORDER BY
          CASE WHEN :sortDirection = 'ASC' THEN sort_ts END ASC NULLS LAST,
          CASE WHEN :sortDirection = 'DESC' THEN sort_ts END DESC NULLS LAST,
          record_key
      """;

  /**
   * The expanded-row payload for one transaction, fetched on demand.
   *
   * <p>Split out of EVIDENCE_RECORDS deliberately. The list renders six columns; this returns the
   * ~29 fields behind the Details panel, and every one of them costs a join -- the
   * reg_reportable_activity lookup for party/currency data and a rule-hit aggregation. Paying that
   * for 25 rows to render at most one expanded row was the bulk of the list query's work.
   *
   * <p>Reuses the same evidence pipeline the list is built from, narrowed to a single
   * (batch, identifier), so an opened row always shows the merged evidence its list row
   * represents rather than a separately-derived view that could disagree with it.
   */
  static final String RECORD_DETAIL =
      "WITH "
          + RULE_HIT_MATCHES_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + """
      , filtered_evidence AS (
          SELECT * FROM metric_scoped
          WHERE identifier = :identifier
            -- Scoped by the requested status exactly as the list row was, so the merge here sees
            -- the same evidence sources. Without it the panel merges sources the row did not,
            -- and fields resolved by source priority (transaction date, galactic id, source,
            -- activity type) disagree with the row that was opened.
            AND (:status = 'ALL' OR UPPER(COALESCE(status, '')) = :status)
      )
      """
          + TransactionEvidenceColumns.MERGED_CTE
          + """
      SELECT
          merged.identifier AS "identifier",
          merged.rule_id AS "ruleId",
          merged.exclusion_strategy AS "exclusionStrategy",
          merged.bucket_id AS "bucketId",
          merged.attempt_id AS "attemptId",
          merged.galactic_id AS "galacticId",
          merged.transaction_side AS "transactionSide",
          merged.txn_source AS "txnSource",
          merged.activity_type AS "activityType",
          CASE WHEN merged.evidence_source = 'JOURNEY'
               THEN COALESCE(rra.s_local_principal, rra.r_local_principal)
               ELSE merged.currency_amount END AS "currencyAmount",
          CASE WHEN merged.evidence_source = 'JOURNEY'
               THEN COALESCE(rra.s_currency, rra.r_currency)
               ELSE merged.currency_code END AS "currencyCode",
          CASE WHEN merged.evidence_source = 'JOURNEY'
               THEN COALESCE(rra.s_date, rra.r_date)
               ELSE merged.transaction_date END AS "transactionDate",
          CASE WHEN merged.evidence_source = 'JOURNEY'
               THEN rra.group_send_date
               ELSE merged.send_date END AS "sendDate",
          rra.s_party_name AS "senderName",
          rra.r_party_name AS "receiverName",
          rra.s_party_city AS "senderCity",
          rra.s_party_country_of_residence AS "senderCountry",
          rra.s_party_phone_number AS "senderPhone",
          rra.s_party_date_of_birth AS "senderDateOfBirth",
          rra.s_party_id_type AS "senderIdType",
          rra.s_party_id_number AS "senderIdNumber",
          rra.r_party_city AS "receiverCity",
          rra.r_party_country_of_residence AS "receiverCountry",
          rra.r_party_phone_number AS "receiverPhone",
          rra.r_party_date_of_birth AS "receiverDateOfBirth",
          rra.r_party_id_type AS "receiverIdType",
          rra.r_party_id_number AS "receiverIdNumber",
          rra.txn_status AS "transactionStatus",
          rra.sub_status AS "transactionSubStatus",
          COALESCE(rollup.rule_hits_json, '[]') AS "ruleHitsJson"
      FROM merged
      LEFT JOIN pharos.reg_reportable_activity rra
          ON rra.txn_sur_key = merged.rra_key
      LEFT JOIN LATERAL (
          SELECT
              json_agg(
                  json_build_object(
                      'ruleId', rhm.rule_id,
                      'isReported', rhm.is_reported,
                      'reportingTimestamp', rhm.reporting_timestamp::text,
                      'bucketId', rhm.bucket_id,
                      'attemptId', rhm.attempt_id
                  )
                  ORDER BY rhm.rule_id
              )::text AS rule_hits_json
          FROM rule_hit_matches rhm
          WHERE rhm.matched_identifier IS NOT NULL
            AND rhm.matched_identifier = merged.identifier
      ) rollup ON TRUE
      WHERE merged.evidence_batch_id = :batchId
      LIMIT 1
      """;

  /**
   * Counts rows matching the full source/stage/search/metric/outcome selection — a plain COUNT(*)
   * with no per-row columns, no sort, no pagination. Reused for both "matching this exact filter"
   * and, called a second time with default filters, "available under this metric at all" — cheaper
   * than re-running EVIDENCE_RECORDS just to read a count off its first row.
   */
  static final String EVIDENCE_COUNT =
      "WITH "
          + RULE_HIT_MATCHES_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + STAGE_SCOPED_CTE
          + FILTERED_EVIDENCE_CTE
          + """
      SELECT COUNT(*) AS "count"
      FROM (
          SELECT 1
          FROM filtered_evidence
          GROUP BY evidence_batch_id, identifier
      ) transactions
      """;

  private TransactionReportNativeQueries() {}
}
