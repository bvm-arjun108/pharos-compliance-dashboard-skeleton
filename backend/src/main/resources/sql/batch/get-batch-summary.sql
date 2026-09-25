select
  count(*)::bigint as "allBatches",
  count(*) filter (where total_issues = 0)::bigint as "successfulBatches",
  count(*) filter (where total_issues > 0)::bigint as "attentionBatches",
  max(rpt_grp_name) as "reportGroupName"
from enriched_batch_metrics
