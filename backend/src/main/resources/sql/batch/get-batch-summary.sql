select
  count(*)::bigint as "allBatches",
  count(*) filter (where total_issues = 0)::bigint as "successfulBatches",
  count(*) filter (where total_issues > 0)::bigint as "attentionBatches",
  count(*) filter (where activity_missing > 0)::bigint as "activityMissingBatches",
  count(*) filter (where missing_attempts > 0)::bigint as "missingAttemptBatches",
  count(*) filter (where transformation_failures > 0)::bigint as "transformationBatches",
  count(*) filter (where total_issues = 0 and duplicate_transactions > 0)::bigint as "duplicateTransactionBatches",
  count(*) filter (where total_issues = 0 and excluded_transactions > 0)::bigint as "exclusionBatches",
  count(*) filter (where total_issues = 0 and simulated_transactions > 0)::bigint as "simulatedTransactionBatches",
  count(*) filter (where total_issues = 0 and soft_dedup_transactions > 0)::bigint as "softDedupBatches",
  max(rpt_grp_name) as "reportGroupName"
from enriched_batch_metrics
