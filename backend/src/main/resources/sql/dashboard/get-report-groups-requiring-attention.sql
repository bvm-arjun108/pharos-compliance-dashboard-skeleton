select
  rpt_grp_id as "reportGroupId",
  rpt_grp_name as "reportGroupName",
  batches_ran as "batchesRan",
  batches_ran - batches_needing_attention as "successfulBatches",
  batches_needing_attention as "batchesNeedingAttention",
  transformation_failure_batches as "transformationFailureBatches",
  missing_attempt_batches as "missingAttemptBatches",
  activity_missing_batches as "activityMissingBatches",
  total_reported_transactions as "totalReportedTransactions",
  total_excluded_transactions as "totalExcludedTransactions"
from report_group_metrics
%%ATTENTION_FILTER%%
order by batches_needing_attention desc,
  transformation_failure_batches + missing_attempt_batches + activity_missing_batches desc,
  rpt_grp_id
