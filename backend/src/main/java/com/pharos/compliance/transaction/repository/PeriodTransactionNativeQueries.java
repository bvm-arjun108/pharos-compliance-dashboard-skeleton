package com.pharos.compliance.transaction.repository;

/**
 * Backs the "transaction evidence across a whole filtered period" view — used when a dashboard KPI
 * (or a user just browsing a report group's activity over a date range) spans many batches and
 * there is no single batch to show evidence for. Mirrors TransactionReportNativeQueries' three
 * evidence sources (JOURNEY / EXCLUSION_AUDIT / RULE_HIT) exactly, just joined against the whole
 * set of batches matching the date range / country / report-group filter (batch_scope) instead of
 * one :batchId.
 */
final class PeriodTransactionNativeQueries {

  private static final String BATCH_SCOPE_CTE =
      """
      batch_scope AS (
          SELECT rpt_grp_id, batch_id, rpt_grp_name, excluded_txn
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR rpt_grp_id = :reportGroupId)
      )
      """;

  static final String AGGREGATE =
      "WITH "
          + BATCH_SCOPE_CTE
          + """
      SELECT
          COUNT(DISTINCT (rpt_grp_id, batch_id)) AS "batchCount",
          COALESCE(SUM(excluded_txn), 0) AS "totalExcluded",
          MAX(rpt_grp_name) AS "reportGroupName"
      FROM batch_scope
      """;

  /**
   * Resolves each rule_hit row to its journey identifier, across every batch in batch_scope
   * (rather than a single :batchId) — same identifier -> external_txn_key/mtcn bridge as
   * TransactionReportNativeQueries.RULE_HIT_MATCHES_CTE.
   */
  private static final String RULE_HIT_MATCHES_CTE =
      """
      , rule_hit_matches AS (
          SELECT
              rh.*,
              COALESCE(
                  (SELECT j.identifier
                   FROM pharos.record_transformation_journey j
                   JOIN batch_scope bsj ON bsj.rpt_grp_id = j.rpt_grp_id AND bsj.batch_id = j.batch_id
                   WHERE j.rpt_grp_id = rh.rpt_grp_id
                     AND j.identifier ~ '^[0-9]+$'
                     AND j.identifier::bigint = rh.external_txn_key
                   LIMIT 1),
                  (SELECT j2.identifier
                   FROM pharos.record_transformation_journey j2
                   JOIN batch_scope bsj2 ON bsj2.rpt_grp_id = j2.rpt_grp_id AND bsj2.batch_id = j2.batch_id
                   WHERE j2.rpt_grp_id = rh.rpt_grp_id
                     AND j2.mtcn = rh.mtcn
                   LIMIT 1)
              ) AS matched_identifier
          FROM pharos.rule_hit rh
          WHERE rh.rpt_grp_id IN (SELECT DISTINCT rpt_grp_id FROM batch_scope)
      )
      """;

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

  /** Union of every evidence source into one common row shape — the period-wide equivalent of
   *  TransactionReportNativeQueries.EVIDENCE_CTE, joined against batch_scope instead of :batchId. */
  private static final String EVIDENCE_CTE =
      """
      , evidence_base AS (
          SELECT
              CONCAT('JOURNEY:', journey.rpt_grp_id, ':', journey.batch_id, ':', journey.identifier)
                  AS record_key,
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
          JOIN batch_scope bs_journey
              ON bs_journey.rpt_grp_id = journey.rpt_grp_id AND bs_journey.batch_id = journey.batch_id
          LEFT JOIN pharos.reg_reportable_activity rra_journey
              ON journey.identifier ~ '^[0-9]+$'
             AND rra_journey.txn_sur_key = journey.identifier::bigint

          UNION ALL

          SELECT
              CONCAT('EXCLUSION:', ea.bucket_id, ':', ea.rule_id, ':', ea.attempt_id) AS record_key,
              COALESCE(ea.external_txn_key::text, ea.attempt_id::text) AS identifier,
              ea.mtcn,
              ea.processing_batch_id AS evidence_batch_id,
              'EXCLUSION_AUDIT' AS evidence_source,
              'EXCLUSION' AS stage,
              'EXCLUDED' AS status,
              'EXCLUDED' AS outcome,
              NULL::text AS comments,
              NULL::text AS skip_reason,
              ea.rule_id,
              ea.exclusion_reason_id AS exclusion_reason,
              ea.exclusion_strategy,
              ea.reported_batch_id,
              ea.reporting_timestamp::text AS reporting_timestamp,
              ea.modified_timestamp::text AS modified_at,
              TRUE AS processing_complete,
              NULL::numeric AS currency_amount,
              NULL::text AS currency_code,
              NULL::text AS transaction_date,
              NULL::text AS transaction_side,
              NULL::text AS txn_source,
              NULL::text AS activity_type,
              NULL::text AS send_date,
              NULL::text AS galactic_id,
              ea.bucket_id,
              ea.attempt_id,
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
          FROM pharos.rule_hit_exclusion_audit ea
          JOIN batch_scope bs_exclusion
              ON bs_exclusion.rpt_grp_id = ea.rpt_grp_id
             AND bs_exclusion.batch_id = ea.processing_batch_id
          LEFT JOIN pharos.reg_reportable_activity rra_exclusion
              ON rra_exclusion.txn_sur_key = ea.external_txn_key

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

  private static final String FILTERED_CTE =
      """
      , filtered AS (
          SELECT *
          FROM evidence
          WHERE (:search = ''
                 OR LOWER(identifier) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(mtcn, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:outcome = 'ALL' OR outcome = :outcome)
            AND (:status = 'ALL' OR UPPER(COALESCE(status, '')) = :status)
      )
      """;

  static final String EVIDENCE_RECORDS =
      "WITH "
          + BATCH_SCOPE_CTE
          + RULE_HIT_MATCHES_CTE
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + FILTERED_CTE
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
      FROM filtered
      ORDER BY
          CASE WHEN :sortDirection = 'ASC' THEN modified_at END ASC NULLS LAST,
          CASE WHEN :sortDirection = 'DESC' THEN modified_at END DESC NULLS LAST,
          record_key
      LIMIT :size OFFSET :offset
      """;

  static final String EVIDENCE_COUNT =
      "WITH "
          + BATCH_SCOPE_CTE
          + RULE_HIT_MATCHES_CTE
          + RULE_HIT_ROLLUP_CTE
          + EVIDENCE_CTE
          + FILTERED_CTE
          + """
      SELECT COUNT(*) AS "count"
      FROM filtered
      """;

  private PeriodTransactionNativeQueries() {}
}
