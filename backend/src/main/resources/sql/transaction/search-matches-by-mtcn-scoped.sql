-- MTCN search limited to the page's report period / country / report group. Assumes search_batches
-- (the distinct (rpt_grp_id, batch_id) pairs of batch_scope) is an earlier CTE in the same WITH
-- clause; every branch probes an existing (rpt_grp_id, batch_id, ...) index per in-scope batch, so
-- cost follows the batches in scope rather than the size of the evidence tables.
--
-- Each table has two disjoint branches: rows carrying this MTCN, and rows carrying no MTCN at all
-- whose transaction key is one reg_reportable_activity maps this MTCN to (its txn_sur_key is
-- journey's identifier and rule_hit/exclusion_audit's external_txn_key). A row carrying a
-- different MTCN is never pulled in.
select j.rpt_grp_id as report_group_id, cast(null as text) as report_group_name, j.batch_id, 'JOURNEY' as evidence_source,
  j.stage, j.status, j.comments, j.modified_timestamp::text as occurred_at, j.mtcn as mtcn_value, j.identifier as identifier_value
from search_batches b
join pharos.record_transformation_journey j
  on j.rpt_grp_id = b.rpt_grp_id
  and j.batch_id = b.batch_id
where j.mtcn = :mtcn
union all
select j.rpt_grp_id as report_group_id, cast(null as text) as report_group_name, j.batch_id, 'JOURNEY' as evidence_source,
  j.stage, j.status, j.comments, j.modified_timestamp::text as occurred_at, j.mtcn as mtcn_value, j.identifier as identifier_value
from search_batches b
join pharos.record_transformation_journey j
  on j.rpt_grp_id = b.rpt_grp_id
  and j.batch_id = b.batch_id
where j.mtcn is null
  and j.identifier ~ '^[0-9]+$'
  and j.identifier::bigint in (select txn_sur_key from pharos.reg_reportable_activity where mtcn = :mtcn)
union all
select ea.rpt_grp_id as report_group_id, ea.rpt_grp_name as report_group_name, ea.processing_batch_id as batch_id,
  'EXCLUSION_AUDIT' as evidence_source, 'EXCLUSION' as stage, 'EXCLUDED' as status, ea.exclusion_reason_id as comments,
  ea.modified_timestamp::text as occurred_at, ea.mtcn as mtcn_value, ea.external_txn_key::text as identifier_value
from search_batches b
join pharos.rule_hit_exclusion_audit ea
  on ea.rpt_grp_id = b.rpt_grp_id
  and ea.processing_batch_id = b.batch_id
where ea.mtcn = :mtcn
  or (ea.mtcn is null and ea.external_txn_key in (select txn_sur_key from pharos.reg_reportable_activity where mtcn = :mtcn))
union all
select rh.rpt_grp_id as report_group_id, rh.rpt_grp_name as report_group_name, rh.efile_batch_id as batch_id, 'RULE_HIT' as evidence_source,
  'RULE_HIT' as stage, (case when rh.is_reported then 'REPORTED' else 'NOT_REPORTED' end) as status, rh.rule_id as comments,
  rh.modified_timestamp::text as occurred_at, rh.mtcn as mtcn_value, rh.external_txn_key::text as identifier_value
from search_batches b
join pharos.rule_hit rh
  on rh.rpt_grp_id = b.rpt_grp_id
  and rh.efile_batch_id = b.batch_id
where rh.mtcn = :mtcn
  or (rh.mtcn is null and rh.external_txn_key in (select txn_sur_key from pharos.reg_reportable_activity where mtcn = :mtcn))
