select
  r.rpt_grp_id,
  r.batch_id,
  r.seq_no,
  r.rpt_grp_name,
  r.created_timestamp,
  coalesce(jf.journey_transformation_failures, coalesce(r.activity_transformation_failed, 0)::bigint) as transformation_failures,
  coalesce(r.txn_missing_attempt_count, 0)::bigint as missing_attempts,
  coalesce(r.activity_missing, 0)::bigint as activity_missing,
  coalesce(r.duplicate_transformation, 0)::bigint as duplicate_transactions,
  coalesce(r.excluded_txn, 0)::bigint as excluded_transactions,
  coalesce(r.txn_simulated, 0)::bigint as simulated_transactions,
  coalesce(r.soft_dedup_dropped_txn_count, 0)::bigint as soft_dedup_transactions,
  coalesce(r.activity_transformed, 0)::bigint as reported_transactions,
  (
    coalesce(jf.journey_transformation_failures, coalesce(r.activity_transformation_failed, 0)::bigint) > 0
    or coalesce(r.txn_missing_attempt_count, 0) > 0
    or coalesce(r.activity_missing, 0) > 0
  ) as needs_attention
from pharos.report_transformation_reconciliation r
left join journey_failures_by_batch jf
  on jf.rpt_grp_id = r.rpt_grp_id
  and jf.batch_id = r.batch_id
where 1 = 1
  /*SCOPE*/
