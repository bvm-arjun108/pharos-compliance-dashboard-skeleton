package com.pharos.compliance.transaction.repository;

/**
 * Backs the "excluded transactions across a whole filtered period" view — used when a dashboard
 * KPI (e.g. total excluded transactions) spans many batches and there is no single batch to show
 * evidence for. Scoped to the EXCLUSION_AUDIT evidence source only: that is the one source whose
 * aggregate (report_transformation_reconciliation.excluded_txn, summed here exactly the way the
 * dashboard sums it) has a matching record-level table (rule_hit_exclusion_audit) that isn't tied
 * to interpreting per-batch journey stage/comments conventions the way the other metrics are.
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

  /** Every rule_hit_exclusion_audit row whose processing batch falls in the scoped batch set. */
  private static final String EXCLUSION_EVIDENCE_CTE =
      """
      , exclusion_evidence AS (
          SELECT
              CONCAT('EXCLUSION:', ea.bucket_id, ':', ea.rule_id, ':', ea.attempt_id) AS record_key,
              COALESCE(ea.external_txn_key::text, ea.attempt_id::text) AS identifier,
              ea.mtcn,
              ea.processing_batch_id AS batch_id,
              'EXCLUSION_AUDIT' AS evidence_source,
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
              '[]' AS rule_hits_json,
              rra.s_party_name AS sender_name,
              rra.r_party_name AS receiver_name,
              rra.s_party_city AS sender_city,
              rra.s_party_country_of_residence AS sender_country,
              rra.s_party_phone_number AS sender_phone,
              rra.s_party_date_of_birth AS sender_date_of_birth,
              rra.s_party_id_type AS sender_id_type,
              rra.s_party_id_number AS sender_id_number,
              rra.r_party_city AS receiver_city,
              rra.r_party_country_of_residence AS receiver_country,
              rra.r_party_phone_number AS receiver_phone,
              rra.r_party_date_of_birth AS receiver_date_of_birth,
              rra.r_party_id_type AS receiver_id_type,
              rra.r_party_id_number AS receiver_id_number,
              rra.txn_status AS transaction_status,
              rra.sub_status AS transaction_sub_status
          FROM pharos.rule_hit_exclusion_audit ea
          JOIN batch_scope bs
              ON bs.rpt_grp_id = ea.rpt_grp_id AND bs.batch_id = ea.processing_batch_id
          LEFT JOIN pharos.reg_reportable_activity rra
              ON rra.txn_sur_key = ea.external_txn_key
      )
      """;

  private static final String FILTERED_CTE =
      """
      , filtered AS (
          SELECT *
          FROM exclusion_evidence
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
          + EXCLUSION_EVIDENCE_CTE
          + FILTERED_CTE
          + """
      SELECT
          record_key AS "recordKey",
          identifier AS "identifier",
          mtcn AS "mtcn",
          batch_id AS "batchId",
          evidence_source AS "evidenceSource",
          NULL::text AS "stage",
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
          + EXCLUSION_EVIDENCE_CTE
          + FILTERED_CTE
          + """
      SELECT COUNT(*) AS "count"
      FROM filtered
      """;

  private PeriodTransactionNativeQueries() {}
}
