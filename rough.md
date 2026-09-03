```sql
explain (analyze, buffers, format json)
with batch_scope as (
  select distinct rpt_grp_id, batch_id
  from pharos.report_transformation_reconciliation
  where created_timestamp >= '2026-08-04' and created_timestamp < '2026-09-03'
),
batch_evidence as (
  select b.rpt_grp_id, b.batch_id,
    coalesce(bi.compiler_status = 'Report Generation Completed' or bi.report_status in ('ALL','PARTIAL'), false) as batch_generated
  from batch_scope b
  left join pharos.report_batch_info bi
    on bi.rpt_grp_id = b.rpt_grp_id and bi.batch_id = b.batch_id
)
select j.rpt_grp_id, j.identifier,
  bool_or(upper(coalesce(j.status,'')) in ('EXCLUDED','EXCLUDED_SOFT_DEDUP')) as ever_excluded,
  bool_or(
    (j.stage='REPORT_GENERATION' and upper(coalesce(j.status,''))='GENERATED')
    or (j.stage='TRANSFORMATION' and upper(coalesce(j.status,''))='SUCCESS' and e.batch_generated)
  ) as ever_reported
from pharos.record_transformation_journey j
join batch_evidence e on e.rpt_grp_id=j.rpt_grp_id and e.batch_id=j.batch_id
group by j.rpt_grp_id, j.identifier;
```