```sql
explain (analyze, buffers, format json)
with batch_scope as (
  select distinct batch_id
  from pharos.report_transformation_reconciliation
  where rpt_grp_id = 1000000007  -- substitute one real, busy report group id from your data
    and created_timestamp >= '2026-08-04' and created_timestamp < '2026-09-03'
)
select j.identifier,
       bool_or(upper(coalesce(j.status,'')) in ('EXCLUDED','EXCLUDED_SOFT_DEDUP')) as ever_excluded,
       bool_or(
               (j.stage='REPORT_GENERATION' and upper(coalesce(j.status,''))='GENERATED')
                   or (j.stage='TRANSFORMATION' and upper(coalesce(j.status,''))='SUCCESS')
       ) as ever_reported
from pharos.record_transformation_journey j
where j.rpt_grp_id = 1000000007  -- same id as above
  and j.batch_id in (select batch_id from batch_scope)
group by j.identifier;
```