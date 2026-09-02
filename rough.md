### Query 1 — Dashboard's "41 Excluded" (Transaction Totals card)

```sql
select coalesce(sum("rtr_scope"."excluded_txn"), 0) as "total_excluded_transactions"
from (
         select "pharos"."report_transformation_reconciliation".*
         from "pharos"."report_transformation_reconciliation"
         where (
                   "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-08-04 00:00:00.0'
                       and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
                   )
     ) as "rtr_scope";
```

### Query 2 — Transaction view's "32 matching" (status = Excluded)

```sql
select count(distinct (
                       "filtered_evidence"."evidence_batch_id",
                       "filtered_evidence"."identifier"
    ))
from (
         select
             "evidence"."record_key",
             "evidence"."identifier",
             "evidence"."mtcn",
             "evidence"."evidence_batch_id",
             "evidence"."evidence_source",
             "evidence"."stage",
             "evidence"."status",
             "evidence"."outcome",
             "evidence"."comments",
             "evidence"."skip_reason",
             "evidence"."rule_id",
             "evidence"."exclusion_reason",
             "evidence"."exclusion_strategy",
             "evidence"."reported_batch_id",
             "evidence"."reporting_timestamp",
             "evidence"."modified_at",
             "evidence"."sort_ts",
             "evidence"."processing_complete",
             "evidence"."currency_amount",
             "evidence"."currency_code",
             "evidence"."transaction_date",
             "evidence"."transaction_side",
             "evidence"."txn_source",
             "evidence"."activity_type",
             "evidence"."send_date",
             "evidence"."galactic_id",
             "evidence"."bucket_id",
             "evidence"."attempt_id",
             "evidence"."rra_key"
         from (
                  select
                      ((((('JOURNEY:' || cast("pharos"."record_transformation_journey"."rpt_grp_id" as varchar)) || ':') || "pharos"."record_transformation_journey"."batch_id") || ':') || "pharos"."record_transformation_journey"."identifier") as "record_key",
                      "pharos"."record_transformation_journey"."identifier" as "identifier",
                      "pharos"."record_transformation_journey"."mtcn" as "mtcn",
                      "pharos"."record_transformation_journey"."batch_id" as "evidence_batch_id",
                      'JOURNEY' as "evidence_source",
                      "pharos"."record_transformation_journey"."stage" as "stage",
                      "pharos"."record_transformation_journey"."status" as "status",
                      case
                          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) in (
                                                                                                          'ERROR', 'FAILED', 'FAILURE'
                              ) then 'ERROR'
                          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) in (
                                                                                                          'SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED'
                              ) then 'SUCCESS'
                          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'EXCLUDED' then 'EXCLUDED'
                          else 'PENDING'
                          end as "outcome",
                      "pharos"."record_transformation_journey"."comments" as "comments",
                      "pharos"."record_transformation_journey"."skip_reason" as "skip_reason",
                      cast(null as text) as "rule_id",
                      cast(null as text) as "exclusion_reason",
                      cast(null as text) as "exclusion_strategy",
                      cast(null as text) as "reported_batch_id",
                      cast("pharos"."record_transformation_journey"."reporting_timestamp_latest" as text) as "reporting_timestamp",
                      cast("pharos"."record_transformation_journey"."modified_timestamp" as text) as "modified_at",
                      "pharos"."record_transformation_journey"."modified_timestamp" as "sort_ts",
                      "pharos"."record_transformation_journey"."processing_complete" as "processing_complete",
                      cast(null as double precision) as "currency_amount",
                      cast(null as text) as "currency_code",
                      cast(null as text) as "transaction_date",
                      cast(null as text) as "transaction_side",
                      cast(null as text) as "txn_source",
                      cast(null as text) as "activity_type",
                      cast(null as text) as "send_date",
                      cast(null as text) as "galactic_id",
                      cast(null as int) as "bucket_id",
                      cast(null as bigint) as "attempt_id",
                      case
                          when ("pharos"."record_transformation_journey"."identifier" ~ '^[0-9]+$') then cast("pharos"."record_transformation_journey"."identifier" as bigint)
                          end as "rra_key"
                  from "pharos"."record_transformation_journey"
                           join (
                      select "pharos"."report_transformation_reconciliation"."rpt_grp_id", "pharos"."report_transformation_reconciliation"."batch_id", "pharos"."report_transformation_reconciliation"."rpt_grp_name", "pharos"."report_transformation_reconciliation"."excluded_txn"
                      from "pharos"."report_transformation_reconciliation"
                      where (
                                "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-08-04 00:00:00.0'
                                    and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
                                )
                  ) as "batch_scope"
                                on (
                                    "batch_scope"."rpt_grp_id" = "pharos"."record_transformation_journey"."rpt_grp_id"
                                        and "batch_scope"."batch_id" = "pharos"."record_transformation_journey"."batch_id"
                                    )
                  union all
                  select
                      ((((('EXCLUSION:' || cast("pharos"."rule_hit_exclusion_audit"."bucket_id" as varchar)) || ':') || "pharos"."rule_hit_exclusion_audit"."rule_id") || ':') || cast("pharos"."rule_hit_exclusion_audit"."attempt_id" as varchar)) as "record_key",
                      coalesce(
                              cast("pharos"."rule_hit_exclusion_audit"."external_txn_key" as text),
                              cast("pharos"."rule_hit_exclusion_audit"."attempt_id" as text)
                      ) as "identifier",
                      "pharos"."rule_hit_exclusion_audit"."mtcn" as "mtcn",
                      "pharos"."rule_hit_exclusion_audit"."processing_batch_id" as "evidence_batch_id",
                      'EXCLUSION_AUDIT' as "evidence_source",
                      'EXCLUSION' as "stage",
                      'EXCLUDED' as "status",
                      'EXCLUDED' as "outcome",
                      cast(null as text) as "comments",
                      cast(null as text) as "skip_reason",
                      "pharos"."rule_hit_exclusion_audit"."rule_id" as "rule_id",
                      "pharos"."rule_hit_exclusion_audit"."exclusion_reason_id" as "exclusion_reason",
                      "pharos"."rule_hit_exclusion_audit"."exclusion_strategy" as "exclusion_strategy",
                      "pharos"."rule_hit_exclusion_audit"."reported_batch_id" as "reported_batch_id",
                      cast("pharos"."rule_hit_exclusion_audit"."reporting_timestamp" as text) as "reporting_timestamp",
                      cast("pharos"."rule_hit_exclusion_audit"."modified_timestamp" as text) as "modified_at",
                      "pharos"."rule_hit_exclusion_audit"."modified_timestamp" at time zone 'UTC' as "sort_ts",
                      true as "processing_complete",
                      cast(null as double precision) as "currency_amount",
                      cast(null as text) as "currency_code",
                      cast(null as text) as "transaction_date",
                      cast(null as text) as "transaction_side",
                      cast(null as text) as "txn_source",
                      cast(null as text) as "activity_type",
                      cast(null as text) as "send_date",
                      cast(null as text) as "galactic_id",
                      "pharos"."rule_hit_exclusion_audit"."bucket_id" as "bucket_id",
                      "pharos"."rule_hit_exclusion_audit"."attempt_id" as "attempt_id",
                      "pharos"."rule_hit_exclusion_audit"."external_txn_key" as "rra_key"
                  from "pharos"."rule_hit_exclusion_audit"
                           join (
                      select "pharos"."report_transformation_reconciliation"."rpt_grp_id", "pharos"."report_transformation_reconciliation"."batch_id", "pharos"."report_transformation_reconciliation"."rpt_grp_name", "pharos"."report_transformation_reconciliation"."excluded_txn"
                      from "pharos"."report_transformation_reconciliation"
                      where (
                                "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-08-04 00:00:00.0'
                                    and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
                                )
                  ) as "batch_scope"
                                on (
                                    "batch_scope"."rpt_grp_id" = "pharos"."rule_hit_exclusion_audit"."rpt_grp_id"
                                        and "batch_scope"."batch_id" = "pharos"."rule_hit_exclusion_audit"."processing_batch_id"
                                    )
                  -- RULE_HIT branch omitted: it's forced empty by the app whenever status isn't
                  -- ALL/REPORTED/NOT_REPORTED, and it can never itself carry status='EXCLUDED' anyway.
              ) as "evidence"
         where (
                   upper(coalesce("evidence"."status", '')) = 'EXCLUDED'
                   )
     ) as "filtered_evidence";
```
