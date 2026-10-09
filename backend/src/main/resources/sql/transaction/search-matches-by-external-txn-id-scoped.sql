-- External transaction key search limited to the page's report period / country / report group.
-- Assumes search_batches (the distinct (rpt_grp_id, batch_id) pairs of batch_scope) is an earlier
-- CTE in the same WITH clause. Journey's identifier is the same value as rule_hit/exclusion_audit's
-- external_txn_key and reg_reportable_activity's txn_sur_key; journey is probed through
-- record_transformation_journey_identifier_bigint_idx with that index's own digits-only predicate.
--
-- rule_hit/exclusion_audit rows carrying no external_txn_key at all are matched through the MTCN
-- reg_reportable_activity holds for this key. A row carrying a different key is never pulled in.
select j.rpt_grp_id as report_group_id, cast(null as text) as report_group_name, j.batch_id, 'JOURNEY' as evidence_source,
  j.stage, j.status, j.comments, j.modified_timestamp::text as occurred_at, j.mtcn as mtcn_value, j.identifier as identifier_value
from search_batches b
join pharos.record_transformation_journey j
  on j.rpt_grp_id = b.rpt_grp_id
  and j.batch_id = b.batch_id
where j.identifier ~ '^[0-9]+$'
  and j.identifier::bigint = :externalTxnId
union all
select ea.rpt_grp_id as report_group_id, ea.rpt_grp_name as report_group_name, ea.processing_batch_id as batch_id,
  'EXCLUSION_AUDIT' as evidence_source, 'EXCLUSION' as stage, 'EXCLUDED' as status, ea.exclusion_reason_id as comments,
  ea.modified_timestamp::text as occurred_at, ea.mtcn as mtcn_value, ea.external_txn_key::text as identifier_value
from search_batches b
join pharos.rule_hit_exclusion_audit ea
  on ea.rpt_grp_id = b.rpt_grp_id
  and ea.processing_batch_id = b.batch_id
where ea.external_txn_key = :externalTxnId
  or (ea.external_txn_key is null
    and ea.mtcn in (select mtcn from pharos.reg_reportable_activity where txn_sur_key = :externalTxnId and mtcn is not null))
union all
select rh.rpt_grp_id as report_group_id, rh.rpt_grp_name as report_group_name, rh.efile_batch_id as batch_id, 'RULE_HIT' as evidence_source,
  'RULE_HIT' as stage, (case when rh.is_reported then 'REPORTED' else 'NOT_REPORTED' end) as status, rh.rule_id as comments,
  rh.modified_timestamp::text as occurred_at, rh.mtcn as mtcn_value, rh.external_txn_key::text as identifier_value
from search_batches b
join pharos.rule_hit rh
  on rh.rpt_grp_id = b.rpt_grp_id
  and rh.efile_batch_id = b.batch_id
where rh.external_txn_key = :externalTxnId
  or (rh.external_txn_key is null
    and rh.mtcn in (select mtcn from pharos.reg_reportable_activity where txn_sur_key = :externalTxnId and mtcn is not null))
