-- identifier/external_txn_key are the same underlying value under different names per table (see
-- TransactionSearchRepository's class Javadoc), so the journey branch guards with a digits-only
-- regex before casting identifier to bigint -- the same guard-then-cast pattern used everywhere
-- else in this codebase for that bridge.
select rpt_grp_id as report_group_id, cast(null as text) as report_group_name, batch_id, 'JOURNEY' as evidence_source, stage, status,
  comments, modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.record_transformation_journey
where identifier ~ '^[0-9]+$'
  and identifier::bigint = :externalTxnId
union all
select rpt_grp_id as report_group_id, rpt_grp_name as report_group_name, processing_batch_id as batch_id, 'EXCLUSION_AUDIT' as evidence_source,
  'EXCLUSION' as stage, 'EXCLUDED' as status, exclusion_reason_id as comments, modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.rule_hit_exclusion_audit
where external_txn_key = :externalTxnId
union all
select rpt_grp_id as report_group_id, rpt_grp_name as report_group_name, efile_batch_id as batch_id, 'RULE_HIT' as evidence_source,
  'RULE_HIT' as stage, (case when is_reported then 'REPORTED' else 'NOT_REPORTED' end) as status, rule_id as comments,
  modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.rule_hit
where external_txn_key = :externalTxnId
