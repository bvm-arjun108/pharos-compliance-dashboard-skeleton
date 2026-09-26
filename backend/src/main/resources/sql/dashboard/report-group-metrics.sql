select
  rpt_grp_id,
  max(rpt_grp_name) as rpt_grp_name,
  count(*)::bigint as batches_ran,
  count(*) filter (where needs_attention)::bigint as batches_needing_attention,
  count(*) filter (where transformation_failures > 0)::bigint as transformation_failure_batches,
  count(*) filter (where missing_attempts > 0)::bigint as missing_attempt_batches,
  count(*) filter (where activity_missing > 0)::bigint as activity_missing_batches,
  count(*) filter (where not needs_attention and duplicate_transactions > 0)::bigint as duplicate_transaction_batches,
  count(*) filter (where not needs_attention and excluded_transactions > 0)::bigint as exclusion_batches,
  count(*) filter (where not needs_attention and simulated_transactions > 0)::bigint as simulated_transaction_batches,
  count(*) filter (where not needs_attention and soft_dedup_transactions > 0)::bigint as soft_dedup_batches,
  coalesce(sum(reported_transactions), 0)::bigint as total_reported_transactions,
  coalesce(sum(excluded_transactions), 0)::bigint as total_excluded_transactions
from scoped_batch_metrics
group by rpt_grp_id
