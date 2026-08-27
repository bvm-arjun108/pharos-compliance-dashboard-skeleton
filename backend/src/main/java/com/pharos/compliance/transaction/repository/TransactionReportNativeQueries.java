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
      )
      """;

  /**
   * One row per matched identifier, holding every rule hit for that transaction as a JSON array —
   * a transaction can have more than one rule_hit row (one per rule triggered), so any evidence row
   * for that identifier (journey, exclusion, or a specific rule hit) can show the full set, not just
   * its own.
   */
  private static final String RULE_HIT_ROLLUP_CTE =
      """
      , rule_hit_rollup AS (
          SELECT
              rule_hit_matches.matched_identifier AS identifier,
              json_agg(
                  json_build_object(
                      'ruleId', rule_hit_matches.rule_id,
                      'isReported', rule_hit_matches.is_reported,
                      'reportingTimestamp', rule_hit_matches.reporting_timestamp::text,
                      'bucketId', rule_hit_matches.bucket_id,
                      'attemptId', rule_hit_matches.attempt_id
                  )
                  ORDER BY rule_hit_matches.rule_id
              )::text AS rule_hits_json
          FROM rule_hit_matches
          WHERE rule_hit_matches.matched_identifier IS NOT NULL
          GROUP BY rule_hit_matches.matched_identifier
      )
      """;

  /** Union of every evidence source into one common row shape, keyed by evidence_source. */
  private static final String EVIDENCE_CTE =
      """
      , evidence_base AS (
          SELECT
              CONCAT('JOURNEY:', journey.identifier) AS record_key,
              journey.identifier,
              journey.mtcn,
              journey.batch_id AS evidence_batch_id,
              'JOURNEY' AS evidence_source,
              journey.stage,
              journey.status,
              CASE
                  WHEN UPPER(COALESCE(journey.status, '')) IN ('ERROR', 'FAILED', 'FAILURE')
                      THEN 'ERROR'
                  WHEN UPPER(COALESCE(journey.status, '')) IN
                      ('SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED') THEN 'SUCCESS'
                  WHEN UPPER(COALESCE(journey.status, '')) = 'EXCLUDED' THEN 'EXCLUDED'
                  ELSE 'PENDING'
              END AS outcome,
              journey.comments,
              journey.skip_reason,
              NULL::text AS rule_id,
              NULL::text AS exclusion_reason,
              NULL::text AS exclusion_strategy,
              NULL::text AS reported_batch_id,
              journey.reporting_timestamp_latest::text AS reporting_timestamp,
              journey.modified_timestamp::text AS modified_at,
              journey.processing_complete,
              COALESCE(rra_journey.s_local_principal, rra_journey.r_local_principal)
                  AS currency_amount,
              COALESCE(rra_journey.s_currency, rra_journey.r_currency) AS currency_code,
              COALESCE(rra_journey.s_date, rra_journey.r_date) AS transaction_date,
              NULL::text AS transaction_side,
              NULL::text AS txn_source,
              NULL::text AS activity_type,
              rra_journey.group_send_date AS send_date,
              NULL::text AS galactic_id,
              NULL::int4 AS bucket_id,
              NULL::int8 AS attempt_id,
              rra_journey.s_party_name AS sender_name,
              rra_journey.r_party_name AS receiver_name,
              rra_journey.s_party_city AS sender_city,
              rra_journey.s_party_country_of_residence AS sender_country,
              rra_journey.s_party_phone_number AS sender_phone,
              rra_journey.s_party_date_of_birth AS sender_date_of_birth,
              rra_journey.s_party_id_type AS sender_id_type,
              rra_journey.s_party_id_number AS sender_id_number,
              rra_journey.r_party_city AS receiver_city,
              rra_journey.r_party_country_of_residence AS receiver_country,
              rra_journey.r_party_phone_number AS receiver_phone,
              rra_journey.r_party_date_of_birth AS receiver_date_of_birth,
              rra_journey.r_party_id_type AS receiver_id_type,
              rra_journey.r_party_id_number AS receiver_id_number,
              rra_journey.txn_status AS transaction_status,
              rra_journey.sub_status AS transaction_sub_status
          FROM pharos.record_transformation_journey journey
          LEFT JOIN pharos.reg_reportable_activity rra_journey
              ON journey.identifier ~ '^[0-9]+$'
             AND rra_journey.txn_sur_key = journey.identifier::bigint
          WHERE journey.rpt_grp_id = :reportGroupId
            AND journey.batch_id = :batchId

          UNION ALL

          SELECT
              CONCAT('EXCLUSION:', exclusion_audit.bucket_id, ':', exclusion_audit.rule_id,
                  ':', exclusion_audit.attempt_id) AS record_key,
              COALESCE(exclusion_audit.external_txn_key::text, exclusion_audit.attempt_id::text)
                  AS identifier,
              exclusion_audit.mtcn,
              exclusion_audit.processing_batch_id AS evidence_batch_id,
              'EXCLUSION_AUDIT' AS evidence_source,
              'EXCLUSION' AS stage,
              'EXCLUDED' AS status,
              'EXCLUDED' AS outcome,
              NULL::text AS comments,
              NULL::text AS skip_reason,
              exclusion_audit.rule_id,
              exclusion_audit.exclusion_reason_id AS exclusion_reason,
              exclusion_audit.exclusion_strategy,
              exclusion_audit.reported_batch_id,
              exclusion_audit.reporting_timestamp::text AS reporting_timestamp,
              exclusion_audit.modified_timestamp::text AS modified_at,
              TRUE AS processing_complete,
              NULL::numeric AS currency_amount,
              NULL::text AS currency_code,
              NULL::text AS transaction_date,
              NULL::text AS transaction_side,
              NULL::text AS txn_source,
              NULL::text AS activity_type,
              NULL::text AS send_date,
              NULL::text AS galactic_id,
              exclusion_audit.bucket_id,
              exclusion_audit.attempt_id,
              rra_exclusion.s_party_name AS sender_name,
              rra_exclusion.r_party_name AS receiver_name,
              rra_exclusion.s_party_city AS sender_city,
              rra_exclusion.s_party_country_of_residence AS sender_country,
              rra_exclusion.s_party_phone_number AS sender_phone,
              rra_exclusion.s_party_date_of_birth AS sender_date_of_birth,
              rra_exclusion.s_party_id_type AS sender_id_type,
              rra_exclusion.s_party_id_number AS sender_id_number,
              rra_exclusion.r_party_city AS receiver_city,
              rra_exclusion.r_party_country_of_residence AS receiver_country,
              rra_exclusion.r_party_phone_number AS receiver_phone,
              rra_exclusion.r_party_date_of_birth AS receiver_date_of_birth,
              rra_exclusion.r_party_id_type AS receiver_id_type,
              rra_exclusion.r_party_id_number AS receiver_id_number,
              rra_exclusion.txn_status AS transaction_status,
              rra_exclusion.sub_status AS transaction_sub_status
          FROM pharos.rule_hit_exclusion_audit exclusion_audit
          LEFT JOIN pharos.reg_reportable_activity rra_exclusion
              ON rra_exclusion.txn_sur_key = exclusion_audit.external_txn_key
          WHERE exclusion_audit.rpt_grp_id = :reportGroupId
            AND exclusion_audit.processing_batch_id = :batchId

          UNION ALL

          SELECT
              CONCAT('RULE_HIT:', rule_hit_matches.bucket_id, ':', rule_hit_matches.rule_id,
                  ':', rule_hit_matches.attempt_id) AS record_key,
              rule_hit_matches.matched_identifier AS identifier,
              rule_hit_matches.mtcn,
              rule_hit_matches.efile_batch_id AS evidence_batch_id,
              'RULE_HIT' AS evidence_source,
              'RULE_HIT' AS stage,
              CASE WHEN rule_hit_matches.is_reported THEN 'REPORTED' ELSE 'NOT_REPORTED' END
                  AS status,
              CASE WHEN rule_hit_matches.is_reported THEN 'SUCCESS' ELSE 'PENDING' END AS outcome,
              NULL::text AS comments,
              NULL::text AS skip_reason,
              rule_hit_matches.rule_id,
              rule_hit_matches.exclusion_reason_id AS exclusion_reason,
              NULL::text AS exclusion_strategy,
              rule_hit_matches.reported_batch_id,
              rule_hit_matches.reporting_timestamp::text AS reporting_timestamp,
              rule_hit_matches.modified_timestamp::text AS modified_at,
              TRUE AS processing_complete,
              rule_hit_matches.rule_currency_amount AS currency_amount,
              rule_hit_matches.rule_iso_currency_code AS currency_code,
              rule_hit_matches.transaction_date::text AS transaction_date,
              rule_hit_matches.transaction_side AS transaction_side,
              rule_hit_matches.source AS txn_source,
              rule_hit_matches.activity_type AS activity_type,
              rule_hit_matches.send_date::text AS send_date,
              rule_hit_matches.galactic_id AS galactic_id,
              rule_hit_matches.bucket_id AS bucket_id,
              rule_hit_matches.attempt_id AS attempt_id,
              rra_rule_hit.s_party_name AS sender_name,
              rra_rule_hit.r_party_name AS receiver_name,
              rra_rule_hit.s_party_city AS sender_city,
              rra_rule_hit.s_party_country_of_residence AS sender_country,
              rra_rule_hit.s_party_phone_number AS sender_phone,
              rra_rule_hit.s_party_date_of_birth AS sender_date_of_birth,
              rra_rule_hit.s_party_id_type AS sender_id_type,
              rra_rule_hit.s_party_id_number AS sender_id_number,
              rra_rule_hit.r_party_city AS receiver_city,
              rra_rule_hit.r_party_country_of_residence AS receiver_country,
              rra_rule_hit.r_party_phone_number AS receiver_phone,
              rra_rule_hit.r_party_date_of_birth AS receiver_date_of_birth,
              rra_rule_hit.r_party_id_type AS receiver_id_type,
              rra_rule_hit.r_party_id_number AS receiver_id_number,
              rra_rule_hit.txn_status AS transaction_status,
              rra_rule_hit.sub_status AS transaction_sub_status
          FROM rule_hit_matches
          LEFT JOIN pharos.reg_reportable_activity rra_rule_hit
              ON rra_rule_hit.txn_sur_key = rule_hit_matches.external_txn_key
          WHERE rule_hit_matches.matched_identifier IS NOT NULL
      )
      , evidence AS (
          SELECT
              evidence_base.*,
              COALESCE(rule_hit_rollup.rule_hits_json, '[]') AS rule_hits_json
          FROM evidence_base
          LEFT JOIN rule_hit_rollup
              ON rule_hit_rollup.identifier = evidence_base.identifier
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
   * metric_scoped narrowed by outcome only (not stage) — the stage-breakdown runs on this, so it
   * shows "which stages this outcome shows up in" while still varying freely across stages (the
   * dimension it charts). Deliberately NOT built from stage_scoped: if the user has already
   * filtered to one stage, the breakdown must still be able to show every other stage.
   */
  private static final String OUTCOME_SCOPED_CTE =
      """
      , outcome_scoped AS (
          SELECT *
          FROM metric_scoped
          WHERE (:outcome = 'ALL' OR outcome = :outcome)
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
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + STAGE_SCOPED_CTE
          + FILTERED_EVIDENCE_CTE
          + """
      SELECT
          record_key AS "recordKey",
          identifier AS "identifier",
          mtcn AS "mtcn",
          evidence_batch_id AS "batchId",
          evidence_source AS "evidenceSource",
          stage AS "stage",
          status AS "status",
          outcome AS "outcome",
          comments AS "comments",
          skip_reason AS "skipReason",
          rule_id AS "ruleId",
          exclusion_reason AS "exclusionReason",
          exclusion_strategy AS "exclusionStrategy",
          reported_batch_id AS "reportedBatchId",
          reporting_timestamp AS "reportingTimestamp",
          modified_at AS "modifiedAt",
          processing_complete AS "processingComplete",
          currency_amount AS "currencyAmount",
          currency_code AS "currencyCode",
          transaction_date AS "transactionDate",
          transaction_side AS "transactionSide",
          txn_source AS "txnSource",
          activity_type AS "activityType",
          send_date AS "sendDate",
          galactic_id AS "galacticId",
          bucket_id AS "bucketId",
          attempt_id AS "attemptId",
          sender_name AS "senderName",
          receiver_name AS "receiverName",
          sender_city AS "senderCity",
          sender_country AS "senderCountry",
          sender_phone AS "senderPhone",
          sender_date_of_birth AS "senderDateOfBirth",
          sender_id_type AS "senderIdType",
          sender_id_number AS "senderIdNumber",
          receiver_city AS "receiverCity",
          receiver_country AS "receiverCountry",
          receiver_phone AS "receiverPhone",
          receiver_date_of_birth AS "receiverDateOfBirth",
          receiver_id_type AS "receiverIdType",
          receiver_id_number AS "receiverIdNumber",
          transaction_status AS "transactionStatus",
          transaction_sub_status AS "transactionSubStatus",
          rule_hits_json AS "ruleHitsJson"
      FROM filtered_evidence
      ORDER BY
          CASE WHEN :sortDirection = 'ASC' THEN modified_at END ASC NULLS LAST,
          CASE WHEN :sortDirection = 'DESC' THEN modified_at END DESC NULLS LAST,
          record_key
      LIMIT :size OFFSET :offset
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
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + STAGE_SCOPED_CTE
          + FILTERED_EVIDENCE_CTE
          + """
      SELECT COUNT(*) AS "count"
      FROM filtered_evidence
      """;

  /**
   * Outcome counts within the selected stage (independent of the outcome filter itself and of
   * pagination) — always returns exactly one row, zero-filled when there is no matching evidence.
   */
  static final String OUTCOME_BREAKDOWN =
      "WITH "
          + RULE_HIT_MATCHES_CTE
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + STAGE_SCOPED_CTE
          + """
      SELECT
          COUNT(*) FILTER (WHERE outcome = 'SUCCESS') AS "successCount",
          COUNT(*) FILTER (WHERE outcome = 'ERROR') AS "errorCount",
          COUNT(*) FILTER (WHERE outcome = 'PENDING') AS "pendingCount",
          COUNT(*) FILTER (WHERE outcome = 'EXCLUDED') AS "excludedCount",
          COUNT(*) AS "totalCount"
      FROM stage_scoped
      """;

  /**
   * Outcome counts per stage within the selected outcome (independent of the stage filter itself) —
   * one row per stage that has at least one matching record, so the pipeline's problem points are
   * visible together rather than collapsing to whichever single stage is currently selected.
   */
  static final String STAGE_BREAKDOWN =
      "WITH "
          + RULE_HIT_MATCHES_CTE
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + METRIC_SCOPED_CTE
          + OUTCOME_SCOPED_CTE
          + """
      SELECT
          UPPER(COALESCE(stage, 'UNKNOWN')) AS "stage",
          COUNT(*) FILTER (WHERE outcome = 'SUCCESS') AS "successCount",
          COUNT(*) FILTER (WHERE outcome = 'ERROR') AS "errorCount",
          COUNT(*) FILTER (WHERE outcome = 'PENDING') AS "pendingCount",
          COUNT(*) FILTER (WHERE outcome = 'EXCLUDED') AS "excludedCount",
          COUNT(*) AS "totalCount"
      FROM outcome_scoped
      GROUP BY UPPER(COALESCE(stage, 'UNKNOWN'))
      """;

  private TransactionReportNativeQueries() {}
}
