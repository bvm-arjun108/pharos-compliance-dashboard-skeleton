package com.pharos.compliance.dashboard.repository;

final class DashboardNativeQueries {

  static final String DASHBOARD_COUNTS =
      """
      SELECT
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) AS "batchesRan",
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
              WHERE COALESCE(activity_transformation_failed, 0) > 0
                 OR COALESCE(txn_missing_attempt_count, 0) > 0
                 OR COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
                 OR COALESCE(expected_activity_eligible_for_transformation, 0)
                    <> COALESCE(actual_activity_eligible_for_transformation, 0)
          ) AS "batchesNeedingAttention",
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
              WHERE COALESCE(activity_transformation_failed, 0) > 0
          ) AS "transformationFailureBatches",
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
              WHERE COALESCE(txn_missing_attempt_count, 0) > 0
          ) AS "missingAttemptBatches",
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
              WHERE COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
          ) AS "filtrationFailureBatches",
          COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
              WHERE COALESCE(expected_activity_eligible_for_transformation, 0)
                    <> COALESCE(actual_activity_eligible_for_transformation, 0)
          ) AS "reconciliationFailureBatches",
          COALESCE(SUM(actual_reportable_txn), 0) AS "totalReportedTransactions",
          COALESCE(SUM(excluded_txn), 0) AS "totalExcludedTransactions"
      FROM pharos.report_transformation_reconciliation
      WHERE created_timestamp >= :fromTimestamp
        AND created_timestamp < :toTimestampExclusive
        AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
        AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
      """;

  static final String REPORT_GROUP_ATTENTION =
      """
      WITH report_group_metrics AS (
          SELECT
              rpt_grp_id,
              MAX(rpt_grp_name) AS rpt_grp_name,
              COUNT(DISTINCT (batch_id, seq_no)) AS batches_ran,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
                     OR COALESCE(txn_missing_attempt_count, 0) > 0
                     OR COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
                     OR COALESCE(expected_activity_eligible_for_transformation, 0)
                        <> COALESCE(actual_activity_eligible_for_transformation, 0)
              ) AS batches_needing_attention,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
              ) AS transformation_failure_batches,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_missing_attempt_count, 0) > 0
              ) AS missing_attempt_batches,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
              ) AS filtration_failure_batches,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(expected_activity_eligible_for_transformation, 0)
                        <> COALESCE(actual_activity_eligible_for_transformation, 0)
              ) AS reconciliation_failure_batches,
              COALESCE(SUM(actual_reportable_txn), 0) AS total_reported_transactions,
              COALESCE(SUM(excluded_txn), 0) AS total_excluded_transactions
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
          GROUP BY rpt_grp_id
      )
      SELECT
          rpt_grp_id AS "reportGroupId",
          rpt_grp_name AS "reportGroupName",
          batches_ran AS "batchesRan",
          batches_ran - batches_needing_attention AS "successfulBatches",
          batches_needing_attention AS "batchesNeedingAttention",
          transformation_failure_batches AS "transformationFailureBatches",
          missing_attempt_batches AS "missingAttemptBatches",
          filtration_failure_batches AS "filtrationFailureBatches",
          reconciliation_failure_batches AS "reconciliationFailureBatches",
          total_reported_transactions AS "totalReportedTransactions",
          total_excluded_transactions AS "totalExcludedTransactions"
      FROM report_group_metrics
      WHERE batches_needing_attention > 0
      ORDER BY batches_needing_attention DESC,
               (transformation_failure_batches + missing_attempt_batches
                + filtration_failure_batches + reconciliation_failure_batches) DESC,
               rpt_grp_id
      """;

  static final String BATCH_HEALTH_TREND =
      """
      WITH periods AS (
          SELECT CAST(
              generate_series(
                  CASE
                      WHEN :granularity = 'MONTHLY'
                          THEN CAST(date_trunc('month', CAST(:fromDate AS date)) AS date)
                      ELSE CAST(:fromDate AS date)
                  END,
                  CAST(:toDate AS date),
                  CASE
                      WHEN :granularity = 'DAILY' THEN INTERVAL '1 day'
                      WHEN :granularity = 'WEEKLY' THEN INTERVAL '7 days'
                      ELSE INTERVAL '1 month'
                  END
              ) AS date
          ) AS period_start
      ), period_metrics AS (
          SELECT
              CASE
                  WHEN :granularity = 'DAILY' THEN CAST(created_timestamp AS date)
                  WHEN :granularity = 'WEEKLY' THEN CAST(:fromDate AS date)
                      + (((CAST(created_timestamp AS date) - CAST(:fromDate AS date)) / 7) * 7)
                  ELSE CAST(date_trunc('month', created_timestamp) AS date)
              END AS period_start,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) AS batches_ran,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
                     OR COALESCE(txn_missing_attempt_count, 0) > 0
                     OR COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
                     OR COALESCE(expected_activity_eligible_for_transformation, 0)
                        <> COALESCE(actual_activity_eligible_for_transformation, 0)
              ) AS batches_needing_attention,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
              ) AS transformation_failure_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_missing_attempt_count, 0) > 0
              ) AS missing_attempt_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(expected_reportable_txn, 0) <> COALESCE(actual_reportable_txn, 0)
              ) AS filtration_failure_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(expected_activity_eligible_for_transformation, 0)
                        <> COALESCE(actual_activity_eligible_for_transformation, 0)
              ) AS reconciliation_failure_batches
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
          GROUP BY 1
      )
      SELECT
          periods.period_start AS "periodStart",
          COALESCE(period_metrics.batches_ran, 0) AS "batchesRan",
          COALESCE(period_metrics.batches_ran, 0)
              - COALESCE(period_metrics.batches_needing_attention, 0) AS "successfulBatches",
          COALESCE(period_metrics.batches_needing_attention, 0) AS "batchesNeedingAttention",
          COALESCE(period_metrics.transformation_failure_batches, 0) AS "transformationFailureBatches",
          COALESCE(period_metrics.missing_attempt_batches, 0) AS "missingAttemptBatches",
          COALESCE(period_metrics.filtration_failure_batches, 0) AS "filtrationFailureBatches",
          COALESCE(period_metrics.reconciliation_failure_batches, 0) AS "reconciliationFailureBatches"
      FROM periods
      LEFT JOIN period_metrics USING (period_start)
      ORDER BY periods.period_start
      """;

  private DashboardNativeQueries() {}
}
