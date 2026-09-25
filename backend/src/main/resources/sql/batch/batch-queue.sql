select
  rpt_grp_id as "reportGroupId",
  rpt_grp_name as "reportGroupName",
  batch_id as "batchId",
  seq_no as "sequenceNumber",
  rpt_from_date as "reportingPeriodFrom",
  rpt_to_date as "reportingPeriodTo",
  created_timestamp as "startedAt",
  modified_timestamp as "completedAt",
  transformation_failures as "transformationFailures",
  reported_transformation_failures as "reportedTransformationFailures",
  transformation_failure_mismatch as "transformationFailureMismatch",
  missing_attempts as "missingAttempts",
  activity_missing as "activityMissing",
  filtration_errors as "filtrationErrors",
  reconciliation_imbalance as "reconciliationImbalance",
  transformer_output as "transformerOutput",
  excluded_transactions as "excludedTransactions",
  duplicate_transactions as "duplicateTransactions",
  simulated_transactions as "simulatedTransactions",
  soft_dedup_transactions as "softDedupTransactions",
  total_issues as "totalIssues",
  count(*) over () as "matchingCount"
from enriched_batch_metrics
where 1 = 1
  /*STATUS_CONDITION*/
  /*ISSUE_TYPE_CONDITION*/
  /*METRIC_FOCUS_CONDITION*/
order by /*ORDER_BY*/
limit :size offset :offset
