select
  r.rpt_grp_id,
  max(r.rpt_grp_name) as rpt_grp_name,
  count(distinct (r.batch_id, r.seq_no)) as batches_ran,
  count(distinct (r.batch_id, r.seq_no)) filter (
    where coalesce(jf.journey_transformation_failures, coalesce(r.activity_transformation_failed, 0)::bigint) > 0
      or coalesce(r.txn_missing_attempt_count, 0) > 0
      or coalesce(r.activity_missing, 0) > 0
  ) as batches_needing_attention,
  count(distinct (r.batch_id, r.seq_no)) filter (
    where coalesce(jf.journey_transformation_failures, coalesce(r.activity_transformation_failed, 0)::bigint) > 0
  ) as transformation_failure_batches,
  count(distinct (r.batch_id, r.seq_no)) filter (where coalesce(r.txn_missing_attempt_count, 0) > 0) as missing_attempt_batches,
  count(distinct (r.batch_id, r.seq_no)) filter (where coalesce(r.activity_missing, 0) > 0) as activity_missing_batches,
  coalesce(sum(r.activity_transformed), 0) as total_reported_transactions,
  coalesce(sum(r.excluded_txn), 0) as total_excluded_transactions
from pharos.report_transformation_reconciliation r
left join journey_failures_by_batch jf
  on jf.rpt_grp_id = r.rpt_grp_id
  and jf.batch_id = r.batch_id
where 1 = 1
