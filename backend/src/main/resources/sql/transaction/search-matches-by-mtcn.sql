select rpt_grp_id as report_group_id, cast(null as text) as report_group_name, batch_id, 'JOURNEY' as evidence_source, stage, status,
  comments, modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.record_transformation_journey
where mtcn = :mtcn
union all
select rpt_grp_id as report_group_id, rpt_grp_name as report_group_name, processing_batch_id as batch_id, 'EXCLUSION_AUDIT' as evidence_source,
  'EXCLUSION' as stage, 'EXCLUDED' as status, exclusion_reason_id as comments, modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.rule_hit_exclusion_audit
where mtcn = :mtcn
union all
select rpt_grp_id as report_group_id, rpt_grp_name as report_group_name, efile_batch_id as batch_id, 'RULE_HIT' as evidence_source,
  'RULE_HIT' as stage, (case when is_reported then 'REPORTED' else 'NOT_REPORTED' end) as status, rule_id as comments,
  modified_timestamp::text as occurred_at, mtcn as mtcn_value
from pharos.rule_hit
where mtcn = :mtcn
