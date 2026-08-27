package com.pharos.compliance.dashboard.repository;

final class DashboardNativeQueries {

  static final String DASHBOARD_COUNTS =
      """
      WITH rtr_scope AS (
          SELECT *
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR rpt_grp_id = :reportGroupId)
      ),
      rtr_aggregates AS (
          SELECT
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) AS batches_ran,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
                     OR COALESCE(txn_missing_attempt_count, 0) > 0
                     OR COALESCE(activity_missing, 0) > 0
              ) AS batches_needing_attention,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
              ) AS transformation_failure_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_missing_attempt_count, 0) > 0
              ) AS missing_attempt_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_missing, 0) > 0
              ) AS activity_missing_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(duplicate_transformation, 0) > 0
                    AND COALESCE(activity_transformation_failed, 0) = 0
                    AND COALESCE(txn_missing_attempt_count, 0) = 0
                    AND COALESCE(activity_missing, 0) = 0
              ) AS duplicate_transaction_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(excluded_txn, 0) > 0
                    AND COALESCE(activity_transformation_failed, 0) = 0
                    AND COALESCE(txn_missing_attempt_count, 0) = 0
                    AND COALESCE(activity_missing, 0) = 0
              ) AS exclusion_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_simulated, 0) > 0
                    AND COALESCE(activity_transformation_failed, 0) = 0
                    AND COALESCE(txn_missing_attempt_count, 0) = 0
                    AND COALESCE(activity_missing, 0) = 0
              ) AS simulated_transaction_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(soft_dedup_dropped_txn_count, 0) > 0
                    AND COALESCE(activity_transformation_failed, 0) = 0
                    AND COALESCE(txn_missing_attempt_count, 0) = 0
                    AND COALESCE(activity_missing, 0) = 0
              ) AS soft_dedup_batches,
              COALESCE(SUM(actual_reportable_txn), 0) AS total_reported_transactions,
              COALESCE(SUM(excluded_txn), 0) AS total_excluded_transactions
          FROM rtr_scope
      ),
      not_yet_reported AS (
          SELECT COUNT(DISTINCT (j.rpt_grp_id, j.batch_id)) AS batches_not_yet_reported
          FROM pharos.record_transformation_journey j
          WHERE j.created_timestamp >= :fromTimestamp
            AND j.created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(j.batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR j.rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR j.rpt_grp_id = :reportGroupId)
            AND NOT EXISTS (
                SELECT 1
                FROM pharos.report_transformation_reconciliation r
                WHERE r.rpt_grp_id = j.rpt_grp_id
                  AND r.batch_id = j.batch_id
            )
      )
      SELECT
          a.batches_ran + n.batches_not_yet_reported AS "batchesRan",
          n.batches_not_yet_reported AS "batchesNotYetReported",
          a.batches_needing_attention AS "batchesNeedingAttention",
          a.transformation_failure_batches AS "transformationFailureBatches",
          a.missing_attempt_batches AS "missingAttemptBatches",
          a.activity_missing_batches AS "activityMissingBatches",
          a.duplicate_transaction_batches AS "duplicateTransactionBatches",
          a.exclusion_batches AS "exclusionBatches",
          a.simulated_transaction_batches AS "simulatedTransactionBatches",
          a.soft_dedup_batches AS "softDedupBatches",
          a.total_reported_transactions AS "totalReportedTransactions",
          a.total_excluded_transactions AS "totalExcludedTransactions"
      FROM rtr_aggregates a
      CROSS JOIN not_yet_reported n
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
                     OR COALESCE(activity_missing, 0) > 0
              ) AS batches_needing_attention,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
              ) AS transformation_failure_batches,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_missing_attempt_count, 0) > 0
              ) AS missing_attempt_batches,
              COUNT(DISTINCT (batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_missing, 0) > 0
              ) AS activity_missing_batches,
              COALESCE(SUM(actual_reportable_txn), 0) AS total_reported_transactions,
              COALESCE(SUM(excluded_txn), 0) AS total_excluded_transactions
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR rpt_grp_id = :reportGroupId)
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
          activity_missing_batches AS "activityMissingBatches",
          total_reported_transactions AS "totalReportedTransactions",
          total_excluded_transactions AS "totalExcludedTransactions"
      FROM report_group_metrics
      WHERE batches_needing_attention > 0
      ORDER BY batches_needing_attention DESC,
               (transformation_failure_batches + missing_attempt_batches
                + activity_missing_batches) DESC,
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
                     OR COALESCE(activity_missing, 0) > 0
              ) AS batches_needing_attention,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_transformation_failed, 0) > 0
              ) AS transformation_failure_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(txn_missing_attempt_count, 0) > 0
              ) AS missing_attempt_batches,
              COUNT(DISTINCT (rpt_grp_id, batch_id, seq_no)) FILTER (
                  WHERE COALESCE(activity_missing, 0) > 0
              ) AS activity_missing_batches,
              COALESCE(SUM(actual_reportable_txn), 0) AS total_reported_transactions,
              COALESCE(SUM(excluded_txn), 0) AS total_excluded_transactions
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR rpt_grp_id = :reportGroupId)
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
          COALESCE(period_metrics.activity_missing_batches, 0) AS "activityMissingBatches",
          COALESCE(period_metrics.total_reported_transactions, 0) AS "totalReportedTransactions",
          COALESCE(period_metrics.total_excluded_transactions, 0) AS "totalExcludedTransactions"
      FROM periods
      LEFT JOIN period_metrics USING (period_start)
      ORDER BY periods.period_start
      """;

  private DashboardNativeQueries() {}
}
