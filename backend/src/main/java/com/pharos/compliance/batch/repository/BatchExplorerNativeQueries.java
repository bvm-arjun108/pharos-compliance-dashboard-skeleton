package com.pharos.compliance.batch.repository;

final class BatchExplorerNativeQueries {

  /**
   * Batches that have journey evidence (transactions were selected) but no matching row in
   * report_transformation_reconciliation yet — i.e. selected but not yet transformed/reported.
   * report_group_config has no seq_no concept for these, so they carry a synthetic seq_no of 0,
   * which is otherwise unused (real sequence numbers start at 1).
   */
  private static final String NOT_YET_REPORTED_CTE =
      """
      , not_yet_reported_batches AS (
          SELECT
              j.rpt_grp_id,
              j.batch_id,
              (SELECT rgc.rpt_grp_name
               FROM pharos.report_group_config rgc
               WHERE rgc.rpt_grp_id = j.rpt_grp_id
                 AND rgc.rpt_config_active_flag = TRUE
               ORDER BY rgc.modified_timestamp DESC NULLS LAST
               LIMIT 1) AS rpt_grp_name,
              MIN(j.created_timestamp) AS started_at,
              MAX(j.modified_timestamp) AS last_activity_at,
              COUNT(DISTINCT j.identifier)::bigint AS discovered_transactions
          FROM pharos.record_transformation_journey j
          WHERE j.created_timestamp >= :fromTimestamp
            AND j.created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(j.batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:reportGroupId IS NULL OR j.rpt_grp_id = :reportGroupId)
            AND (:filterByCountry = FALSE OR j.rpt_grp_id IN (:reportGroupIds))
            AND NOT EXISTS (
                SELECT 1
                FROM pharos.report_transformation_reconciliation r
                WHERE r.rpt_grp_id = j.rpt_grp_id
                  AND r.batch_id = j.batch_id
            )
          GROUP BY j.rpt_grp_id, j.batch_id
      )
      """;

  private static final String BATCH_METRICS_CTE =
      """
      WITH batch_metrics AS (
          SELECT
              rpt_grp_id,
              batch_id,
              seq_no,
              rpt_grp_name,
              rpt_from_date,
              rpt_to_date,
              created_timestamp,
              modified_timestamp,
              COALESCE(activity_transformation_failed, 0)::bigint AS transformation_failures,
              COALESCE(txn_missing_attempt_count, 0)::bigint AS missing_attempts,
              COALESCE(activity_missing, 0)::bigint AS activity_missing,
              ABS(COALESCE(expected_reportable_txn, 0)
                  - COALESCE(actual_reportable_txn, 0))::bigint AS filtration_errors,
              ABS(COALESCE(expected_activity_eligible_for_transformation, 0)
                  - COALESCE(actual_activity_eligible_for_transformation, 0))::bigint
                  AS reconciliation_imbalance,
              COALESCE(expected_activity_eligible_for_transformation, 0)::bigint
                  AS expected_transformation_attempts,
              COALESCE(actual_activity_eligible_for_transformation, 0)::bigint
                  AS actual_transformation_attempts,
              COALESCE(activity_transformed, 0)::bigint AS transformed_activities,
              COALESCE(actual_reportable_txn, 0)::bigint AS transformer_output,
              COALESCE(excluded_txn, 0)::bigint AS excluded_transactions,
              COALESCE(duplicate_transformation, 0)::bigint AS duplicate_transactions,
              COALESCE(txn_simulated, 0)::bigint AS simulated_transactions,
              COALESCE(soft_dedup_dropped_txn_count, 0)::bigint AS soft_dedup_transactions
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:reportGroupId IS NULL OR rpt_grp_id = :reportGroupId)
      ), enriched_batch_metrics AS (
          SELECT
              batch_metrics.*,
              transformation_failures + missing_attempts + activity_missing AS total_issues
          FROM batch_metrics
          WHERE (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
      )
      """;

  static final String BATCH_SUMMARY =
      BATCH_METRICS_CTE
          + NOT_YET_REPORTED_CTE
          + """
          SELECT
              (SELECT COUNT(*) FROM enriched_batch_metrics)
                  + (SELECT COUNT(*) FROM not_yet_reported_batches) AS "allBatches",
              (SELECT COUNT(*) FROM enriched_batch_metrics WHERE total_issues = 0)
                  AS "successfulBatches",
              (SELECT COUNT(*) FROM enriched_batch_metrics WHERE total_issues > 0)
                  AS "attentionBatches",
              (SELECT COUNT(*) FROM not_yet_reported_batches) AS "notYetReportedBatches",
              COALESCE(
                  (SELECT MAX(rpt_grp_name) FROM enriched_batch_metrics),
                  (SELECT MAX(rpt_grp_name) FROM not_yet_reported_batches)
              ) AS "reportGroupName"
          """;

  static final String BATCH_QUEUE =
      BATCH_METRICS_CTE
          + NOT_YET_REPORTED_CTE
          + """
          , combined_queue AS (
              SELECT
                  rpt_grp_id, batch_id, seq_no, rpt_grp_name, rpt_from_date, rpt_to_date,
                  created_timestamp::timestamptz AS started_at,
                  modified_timestamp::timestamptz AS completed_at,
                  transformation_failures, missing_attempts, activity_missing, filtration_errors,
                  reconciliation_imbalance, transformer_output, excluded_transactions,
                  duplicate_transactions, simulated_transactions, soft_dedup_transactions,
                  total_issues, 0::bigint AS discovered_transactions,
                  'RECONCILED' AS status_bucket
              FROM enriched_batch_metrics

              UNION ALL

              SELECT
                  rpt_grp_id, batch_id, 0 AS seq_no, rpt_grp_name,
                  NULL::text AS rpt_from_date, NULL::text AS rpt_to_date,
                  started_at, NULL::timestamptz AS completed_at,
                  0::bigint, 0::bigint, 0::bigint, 0::bigint, 0::bigint, 0::bigint, 0::bigint,
                  0::bigint, 0::bigint, 0::bigint,
                  0::bigint AS total_issues, discovered_transactions,
                  'NOT_YET_REPORTED' AS status_bucket
              FROM not_yet_reported_batches
          )
          SELECT
              rpt_grp_id AS "reportGroupId",
              rpt_grp_name AS "reportGroupName",
              batch_id AS "batchId",
              seq_no AS "sequenceNumber",
              rpt_from_date AS "reportingPeriodFrom",
              rpt_to_date AS "reportingPeriodTo",
              started_at AS "startedAt",
              completed_at AS "completedAt",
              transformation_failures AS "transformationFailures",
              missing_attempts AS "missingAttempts",
              activity_missing AS "activityMissing",
              filtration_errors AS "filtrationErrors",
              reconciliation_imbalance AS "reconciliationImbalance",
              transformer_output AS "transformerOutput",
              excluded_transactions AS "excludedTransactions",
              duplicate_transactions AS "duplicateTransactions",
              simulated_transactions AS "simulatedTransactions",
              soft_dedup_transactions AS "softDedupTransactions",
              total_issues AS "totalIssues",
              discovered_transactions AS "discoveredTransactions",
              status_bucket AS "statusBucket",
              COUNT(*) OVER () AS "matchingCount"
          FROM combined_queue
          WHERE (:status = 'ALL'
                 OR (:status = 'SUCCESSFUL' AND status_bucket = 'RECONCILED' AND total_issues = 0)
                 OR (:status = 'ATTENTION' AND status_bucket = 'RECONCILED' AND total_issues > 0)
                 OR (:status = 'NOT_YET_REPORTED' AND status_bucket = 'NOT_YET_REPORTED'))
            AND (:issueType = 'ALL'
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'ACTIVITY_MISSING' AND activity_missing > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'MISSING_ATTEMPTS' AND missing_attempts > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'TRANSFORMATION' AND transformation_failures > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'DUPLICATE_TRANSFORMATION' AND duplicate_transactions > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'EXCLUSION' AND excluded_transactions > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'SIMULATED' AND simulated_transactions > 0)
                 OR (status_bucket = 'RECONCILED' AND :issueType = 'SOFT_DEDUP' AND soft_dedup_transactions > 0))
            AND (:metricFocus = 'DEFAULT'
                 OR (status_bucket = 'RECONCILED' AND :metricFocus = 'REPORTED' AND transformer_output > 0)
                 OR (status_bucket = 'RECONCILED' AND :metricFocus = 'EXCLUDED' AND excluded_transactions > 0))
          ORDER BY
              CASE WHEN :metricFocus = 'REPORTED' THEN transformer_output END DESC NULLS LAST,
              CASE WHEN :metricFocus = 'EXCLUDED' THEN excluded_transactions END DESC NULLS LAST,
              CASE WHEN status_bucket = 'NOT_YET_REPORTED' THEN 1 ELSE 0 END,
              CASE WHEN total_issues > 0 THEN 0 ELSE 1 END,
              total_issues DESC,
              completed_at DESC NULLS LAST,
              batch_id
          LIMIT :size OFFSET :offset
          """;

  static final String BATCH_DETAILS =
      """
      SELECT
          reconciliation.rpt_grp_id AS "reportGroupId",
          reconciliation.rpt_grp_name AS "reportGroupName",
          reconciliation.batch_id AS "batchId",
          reconciliation.seq_no AS "sequenceNumber",
          reconciliation.rpt_from_date AS "reportingPeriodFrom",
          reconciliation.rpt_to_date AS "reportingPeriodTo",
          reconciliation.created_timestamp AS "startedAt",
          reconciliation.modified_timestamp AS "completedAt",
          COALESCE(reconciliation.activity_transformation_failed, 0)::bigint
              AS "transformationFailures",
          COALESCE(reconciliation.txn_missing_attempt_count, 0)::bigint AS "missingAttempts",
          COALESCE(reconciliation.activity_missing, 0)::bigint AS "activityMissing",
          COALESCE(reconciliation.duplicate_transformation, 0)::bigint AS "duplicateTransactions",
          ABS(COALESCE(reconciliation.expected_reportable_txn, 0)
              - COALESCE(reconciliation.actual_reportable_txn, 0))::bigint AS "filtrationErrors",
          ABS(COALESCE(reconciliation.expected_activity_eligible_for_transformation, 0)
              - COALESCE(reconciliation.actual_activity_eligible_for_transformation, 0))::bigint
              AS "reconciliationImbalance",
          COALESCE(reconciliation.txn_selected, 0)::bigint AS "selectedTransactions",
          GREATEST(
              COALESCE(reconciliation.txn_selected, 0)
                  - COALESCE(reconciliation.txn_missing_attempt_count, 0),
              0)::bigint AS "transactionAttemptsFound",
          COALESCE(reconciliation.expected_reportable_txn, 0)::bigint
              AS "expectedReportableTransactions",
          COALESCE(reconciliation.actual_reportable_txn, 0)::bigint
              AS "actualReportableTransactions",
          COALESCE(reconciliation.expected_activity_eligible_for_transformation, 0)::bigint
              AS "expectedTransformationAttempts",
          COALESCE(reconciliation.actual_activity_eligible_for_transformation, 0)::bigint
              AS "actualTransformationAttempts",
          COALESCE(reconciliation.activity_transformed, 0)::bigint AS "transformedActivities",
          COALESCE(reconciliation.actual_reportable_txn, 0)::bigint AS "transformerOutput",
          COALESCE(reconciliation.excluded_txn, 0)::bigint AS "excludedTransactions",
          COALESCE(reconciliation.txn_simulated, 0)::bigint AS "simulatedTransactions",
          COALESCE(reconciliation.already_reported_count, 0)::bigint
              AS "alreadyReportedTransactions",
          COALESCE(reconciliation.soft_dedup_dropped_txn_count, 0)::bigint
              AS "softDedupTransactions",
          EXISTS (
              SELECT 1
              FROM pharos.record_transformation_journey journey
              WHERE journey.rpt_grp_id = reconciliation.rpt_grp_id
                AND journey.batch_id = reconciliation.batch_id
          ) AS "journeyAvailable",
          EXISTS (
              SELECT 1
              FROM pharos.rule_hit_exclusion_audit exclusion_audit
              WHERE exclusion_audit.rpt_grp_id = reconciliation.rpt_grp_id
                AND exclusion_audit.processing_batch_id = reconciliation.batch_id
          ) AS "exclusionsAvailable"
      FROM pharos.report_transformation_reconciliation reconciliation
      WHERE reconciliation.rpt_grp_id = :reportGroupId
        AND reconciliation.batch_id = :batchId
        AND reconciliation.seq_no = :sequenceNumber
      """;

  static final String BATCH_DETAILS_NOT_YET_REPORTED =
      """
      SELECT
          j.rpt_grp_id AS "reportGroupId",
          (SELECT rgc.rpt_grp_name
           FROM pharos.report_group_config rgc
           WHERE rgc.rpt_grp_id = j.rpt_grp_id
             AND rgc.rpt_config_active_flag = TRUE
           ORDER BY rgc.modified_timestamp DESC NULLS LAST
           LIMIT 1) AS "reportGroupName",
          j.batch_id AS "batchId",
          MIN(j.created_timestamp) AS "startedAt",
          MAX(j.modified_timestamp) AS "lastActivityAt",
          COUNT(DISTINCT j.identifier)::bigint AS "discoveredTransactions",
          COUNT(DISTINCT j.identifier) FILTER (
              WHERE UPPER(COALESCE(j.status, '')) = 'ERROR' AND j.processing_complete IS TRUE
          )::bigint AS "stalledTransactions",
          BOOL_OR(TRUE) AS "journeyAvailable",
          EXISTS (
              SELECT 1
              FROM pharos.rule_hit_exclusion_audit exclusion_audit
              WHERE exclusion_audit.rpt_grp_id = j.rpt_grp_id
                AND exclusion_audit.processing_batch_id = j.batch_id
          ) AS "exclusionsAvailable"
      FROM pharos.record_transformation_journey j
      WHERE j.rpt_grp_id = :reportGroupId
        AND j.batch_id = :batchId
        AND NOT EXISTS (
            SELECT 1
            FROM pharos.report_transformation_reconciliation r
            WHERE r.rpt_grp_id = j.rpt_grp_id
              AND r.batch_id = j.batch_id
        )
      GROUP BY j.rpt_grp_id, j.batch_id
      """;

  private BatchExplorerNativeQueries() {}
}
