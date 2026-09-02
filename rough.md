### Query 1 — Dashboard's "41 Excluded" (Transaction Totals card)

```sql
select coalesce(sum("rtr_scope"."excluded_txn"), 0) as "total_excluded_transactions"
from (
  select "pharos"."report_transformation_reconciliation".*
  from "pharos"."report_transformation_reconciliation"
  where "created_timestamp" >= '2026-08-01' and "created_timestamp" < '2026-09-01'
) as "rtr_scope"
```

### Query 2 — Transaction view's "32 matching" (status = Excluded)

```sql
select count(distinct ("filtered_evidence"."evidence_batch_id", "filtered_evidence"."identifier"))
from (
  select * from (

    -- Branch A: journey rows, kept as-is with their own native status
    select ..., "record_transformation_journey"."status" as "status", ...
    from "record_transformation_journey"
    join (select ... from "report_transformation_reconciliation" where created_timestamp in range) as "batch_scope"
      on batch_scope.rpt_grp_id = journey.rpt_grp_id and batch_scope.batch_id = journey.batch_id

    union all

    -- Branch B: every exclusion_audit row, status hardcoded to 'EXCLUDED'
    select ..., 'EXCLUDED' as "status", ...
    from "rule_hit_exclusion_audit"
    join batch_scope on batch_scope.rpt_grp_id = ea.rpt_grp_id and batch_scope.batch_id = ea.processing_batch_id

    union all

    -- Branch C: rule_hit rows, status REPORTED/NOT_REPORTED (never EXCLUDED)
    select ..., case when is_reported then 'REPORTED' else 'NOT_REPORTED' end as "status", ...
    from rule_hit ...

  ) as "evidence"
) as "filtered_evidence"
where upper(coalesce("status", '')) = 'EXCLUDED'
```
