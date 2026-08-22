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

  static final String EVIDENCE_RECORDS =
      """
      WITH evidence AS (
          SELECT
              CONCAT('JOURNEY:', journey.identifier) AS record_key,
              journey.identifier,
              journey.mtcn,
              'JOURNEY' AS evidence_source,
              journey.stage,
              journey.status,
              CASE
                  WHEN UPPER(COALESCE(journey.status, '')) IN ('ERROR', 'FAILED', 'FAILURE')
                      THEN 'ERROR'
                  WHEN UPPER(COALESCE(journey.status, '')) IN
                      ('SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED') THEN 'SUCCESS'
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
              journey.processing_complete
          FROM pharos.record_transformation_journey journey
          WHERE journey.rpt_grp_id = :reportGroupId
            AND journey.batch_id = :batchId

          UNION ALL

          SELECT
              CONCAT('EXCLUSION:', exclusion_audit.bucket_id, ':', exclusion_audit.rule_id,
                  ':', exclusion_audit.attempt_id) AS record_key,
              COALESCE(exclusion_audit.external_txn_key::text, exclusion_audit.attempt_id::text)
                  AS identifier,
              exclusion_audit.mtcn,
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
              TRUE AS processing_complete
          FROM pharos.rule_hit_exclusion_audit exclusion_audit
          WHERE exclusion_audit.rpt_grp_id = :reportGroupId
            AND exclusion_audit.processing_batch_id = :batchId
      ), filtered_evidence AS (
          SELECT *
          FROM evidence
          WHERE (:source = 'ALL' OR evidence_source = :source)
            AND (:outcome = 'ALL' OR outcome = :outcome)
            AND (:search = ''
                 OR LOWER(identifier) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(mtcn, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(rule_id, '')) LIKE LOWER(CONCAT('%', :search, '%')))
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
            )
      )
      SELECT
          record_key AS "recordKey",
          identifier AS "identifier",
          mtcn AS "mtcn",
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
          COUNT(*) OVER () AS "matchingCount"
      FROM filtered_evidence
      ORDER BY modified_at DESC NULLS LAST, record_key
      LIMIT :size OFFSET :offset
      """;

  private TransactionReportNativeQueries() {}
}
