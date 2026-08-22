package com.pharos.compliance.batch.repository;

final class BatchExplorerNativeQueries {

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
              COALESCE(excluded_txn, 0)::bigint AS excluded_transactions
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:reportGroupId IS NULL OR rpt_grp_id = :reportGroupId)
      ), enriched_batch_metrics AS (
          SELECT
              batch_metrics.*,
              transformation_failures + missing_attempts + filtration_errors
                  + reconciliation_imbalance AS total_issues
          FROM batch_metrics
          WHERE (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
      )
      """;

  static final String BATCH_SUMMARY =
      BATCH_METRICS_CTE
          + """
          SELECT
              COUNT(*) AS "allBatches",
              COUNT(*) FILTER (WHERE total_issues = 0) AS "successfulBatches",
              COUNT(*) FILTER (WHERE total_issues > 0) AS "attentionBatches",
              MAX(rpt_grp_name) AS "reportGroupName"
          FROM enriched_batch_metrics
          """;

  static final String BATCH_QUEUE =
      BATCH_METRICS_CTE
          + """
          SELECT
              rpt_grp_id AS "reportGroupId",
              rpt_grp_name AS "reportGroupName",
              batch_id AS "batchId",
              seq_no AS "sequenceNumber",
              rpt_from_date AS "reportingPeriodFrom",
              rpt_to_date AS "reportingPeriodTo",
              created_timestamp AS "startedAt",
              modified_timestamp AS "completedAt",
              transformation_failures AS "transformationFailures",
              missing_attempts AS "missingAttempts",
              filtration_errors AS "filtrationErrors",
              reconciliation_imbalance AS "reconciliationImbalance",
              transformer_output AS "reportedTransactions",
              excluded_transactions AS "excludedTransactions",
              total_issues AS "totalIssues",
              COUNT(*) OVER () AS "matchingCount"
          FROM enriched_batch_metrics
          WHERE (:status = 'ALL'
                 OR (:status = 'SUCCESSFUL' AND total_issues = 0)
                 OR (:status = 'ATTENTION' AND total_issues > 0))
            AND (:issueType = 'ALL'
                 OR (:issueType = 'TRANSFORMATION' AND transformation_failures > 0)
                 OR (:issueType = 'MISSING_ATTEMPTS' AND missing_attempts > 0)
                 OR (:issueType = 'FILTRATION' AND filtration_errors > 0)
                 OR (:issueType = 'RECONCILIATION' AND reconciliation_imbalance > 0))
            AND (:metricFocus = 'DEFAULT'
                 OR (:metricFocus = 'REPORTED' AND transformer_output > 0)
                 OR (:metricFocus = 'EXCLUDED' AND excluded_transactions > 0))
          ORDER BY
              CASE WHEN :metricFocus = 'REPORTED' THEN transformer_output END DESC NULLS LAST,
              CASE WHEN :metricFocus = 'EXCLUDED' THEN excluded_transactions END DESC NULLS LAST,
              CASE WHEN total_issues > 0 THEN 0 ELSE 1 END,
              total_issues DESC,
              modified_timestamp DESC NULLS LAST,
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

  private BatchExplorerNativeQueries() {}
}
