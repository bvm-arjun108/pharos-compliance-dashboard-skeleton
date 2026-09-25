-- Prefer the journey-derived count (coalesce's own NULL-means-"no journey evidence for this fact"
-- fallback) over the raw reconciliation scalar, which can over- or under-count relative to what
-- record_transformation_journey actually recorded. No numeric total is displayed at this headline
-- level, only this >0 bucketing.
select
  count(distinct (rpt_grp_id, batch_id, seq_no)) as batches_ran,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) > 0
      or coalesce(txn_missing_attempt_count, 0) > 0
      or coalesce(activity_missing, 0) > 0
  ) as batches_needing_attention,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) > 0
  ) as transformation_failure_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (where coalesce(txn_missing_attempt_count, 0) > 0) as missing_attempt_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (where coalesce(activity_missing, 0) > 0) as activity_missing_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(duplicate_transformation, 0) > 0
      and coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) = 0
      and coalesce(txn_missing_attempt_count, 0) = 0
      and coalesce(activity_missing, 0) = 0
  ) as duplicate_transaction_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(excluded_txn, 0) > 0
      and coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) = 0
      and coalesce(txn_missing_attempt_count, 0) = 0
      and coalesce(activity_missing, 0) = 0
  ) as exclusion_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(txn_simulated, 0) > 0
      and coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) = 0
      and coalesce(txn_missing_attempt_count, 0) = 0
      and coalesce(activity_missing, 0) = 0
  ) as simulated_transaction_batches,
  count(distinct (rpt_grp_id, batch_id, seq_no)) filter (
    where coalesce(soft_dedup_dropped_txn_count, 0) > 0
      and coalesce(journey_transformation_failures, coalesce(activity_transformation_failed, 0)::bigint) = 0
      and coalesce(txn_missing_attempt_count, 0) = 0
      and coalesce(activity_missing, 0) = 0
  ) as soft_dedup_batches
from rtr_scope
