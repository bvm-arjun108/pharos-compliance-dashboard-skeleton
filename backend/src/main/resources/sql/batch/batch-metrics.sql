-- Phase 1 only cares about completed batches from a batch perspective -- batches with a
-- reconciliation record, possibly with issues. The marked spot below is replaced in Java with the
-- caller's date-range/batchId/reportGroupId/country conditions.
select
  rpt_grp_id,
  batch_id,
  seq_no,
  rpt_grp_name,
  rpt_from_date,
  rpt_to_date,
  created_timestamp,
  modified_timestamp,
  coalesce(activity_transformation_failed, 0)::bigint as reported_transformation_failures,
  coalesce(txn_missing_attempt_count, 0)::bigint as missing_attempts,
  coalesce(activity_missing, 0)::bigint as activity_missing,
  abs(coalesce(expected_reportable_txn, 0) - coalesce(actual_reportable_txn, 0))::bigint as filtration_errors,
  abs(coalesce(expected_activity_eligible_for_transformation, 0) - coalesce(actual_activity_eligible_for_transformation, 0))::bigint
    as reconciliation_imbalance,
  coalesce(expected_activity_eligible_for_transformation, 0)::bigint as expected_transformation_attempts,
  coalesce(actual_activity_eligible_for_transformation, 0)::bigint as actual_transformation_attempts,
  coalesce(activity_transformed, 0)::bigint as transformed_activities,
  coalesce(actual_reportable_txn, 0)::bigint as transformer_output,
  coalesce(excluded_txn, 0)::bigint as excluded_transactions,
  coalesce(duplicate_transformation, 0)::bigint as duplicate_transactions,
  coalesce(txn_simulated, 0)::bigint as simulated_transactions,
  coalesce(soft_dedup_dropped_txn_count, 0)::bigint as soft_dedup_transactions
from pharos.report_transformation_reconciliation
where 1 = 1
  /*SCOPE*/
