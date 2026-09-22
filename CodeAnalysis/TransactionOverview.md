### Transaction Overview Analysis

## 1. View — Transaction Totals KPI cards

*(Route: `/transaction-view`. Shows Selected / Expected / Excluded / Not Reported counts for one
report group and date range, plus two gauges (Exclusion Rate, Not Reported Rate). Real example
captured for PORTUGAL OBJECTIVE, Feb 1 – Sep 2, 2026: Selected 5,551 · Expected 5,266 ·
Excluded 285 · Not Reported 534. The Daily Batch Health and Transactions Overview trend charts on
this same page reuse the queries already documented in `Dashboard.md`'s "Daily Batch Health" and
"Transactions Overview trend" sections — not repeated here.)*

### Query — Selected / Expected / Excluded / Not Reported

```sql
select
  count(*) as "selected",
  count(*) filter (where (
    "roll"."ever_reported" = true
    or "roll"."ever_excluded" = false
  )) as "expected",
  count(*) filter (where (
    "roll"."ever_excluded" = true
    and "roll"."ever_reported" = false
  )) as "excluded",
  count(*) filter (where (
    "roll"."ever_reported" = false
    and "roll"."ever_excluded" = false
  )) as "not_reported"
from (
  select
    "pharos"."record_transformation_journey"."rpt_grp_id",
    "pharos"."record_transformation_journey"."identifier",
    bool_or((upper(coalesce("pharos"."record_transformation_journey"."status", '')) in (
      'EXCLUDED', 'EXCLUDED_SOFT_DEDUP'
    ))) as "ever_excluded",
    bool_or((
      (
        "pharos"."record_transformation_journey"."stage" = 'REPORT_GENERATION'
        and upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'GENERATED'
      )
      or (
        "pharos"."record_transformation_journey"."stage" = 'TRANSFORMATION'
        and upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'SUCCESS'
        and "batch_evidence"."batch_generated" = true
      )
    )) as "ever_reported"
  from "pharos"."record_transformation_journey"
    join (
      select
        "batch_scope"."rpt_grp_id",
        "batch_scope"."batch_id",
        coalesce(
          (
            "pharos"."report_batch_info"."compiler_status" = 'Report Generation Completed'
            or "pharos"."report_batch_info"."report_status" in ('ALL', 'PARTIAL')
          ),
          false
        ) as "batch_generated"
      from (
        select distinct "pharos"."report_transformation_reconciliation"."rpt_grp_id", "pharos"."report_transformation_reconciliation"."batch_id"
        from "pharos"."report_transformation_reconciliation"
        where (
          "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-02-01 00:00:00.0'
          and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
          and true -- country scope
          and true -- report-group-id-list scope
          and "pharos"."report_transformation_reconciliation"."rpt_grp_id" = 1000000007 -- exact report group picked
        )
      ) as "batch_scope"
        left outer join "pharos"."report_batch_info"
          on (
            "pharos"."report_batch_info"."rpt_grp_id" = "batch_scope"."rpt_grp_id"
            and "pharos"."report_batch_info"."batch_id" = "batch_scope"."batch_id"
          )
    ) as "batch_evidence"
      on (
        "batch_evidence"."rpt_grp_id" = "pharos"."record_transformation_journey"."rpt_grp_id"
        and "batch_evidence"."batch_id" = "pharos"."record_transformation_journey"."batch_id"
      )
  group by "pharos"."record_transformation_journey"."rpt_grp_id", "pharos"."record_transformation_journey"."identifier"
) as "roll"
```

*Captured live for PORTUGAL OBJECTIVE (`rpt_grp_id=1000000007`), Feb 1 – Sep 2, 2026. Result:
`{"selected": 5551, "expected": 5266, "excluded": 285, "not_reported": 534}`.*

### Plain English

This answers "what ultimately happened to every transaction we selected in this window," counted
**once per transaction**, not once per row of history. A transaction can appear many times in the
day-to-day log (`record_transformation_journey`) as it moves through stages — that's why the inner
query groups by `(report group, identifier)` and uses `bool_or` ("was this ever true, across any of
this transaction's rows in the batches in scope?") to collapse all of that history into two
yes/no flags per transaction: *was it ever excluded* and *was it ever reported*. Then:

- **Selected** = every distinct transaction seen at all.
- **Excluded** = ever excluded, and never reported (if it was excluded but *also* got reported
  later, reporting wins — it's not stuck in limbo).
- **Not Reported** = never excluded and never reported — still pending, nothing has decided its
  fate yet.
- **Expected** = everything else: either it was reported, or it was never excluded in the first
  place (so it's still "supposed to" end up reported).

**Why this can show a different "Excluded" number than other pages**: the Report Groups Requiring
Attention table (Batch View) and the Data Selection card (Batch Explorer) both show a much simpler
number — `sum(excluded_txn)` added straight from each batch's own reconciliation row. That's "how
many exclusion *decisions* were made across these batches." This page's "Excluded" is "how many
*distinct transactions* ended up excluded and never rescued by a later report," counted once each
even if the same transaction was touched by several batches. Both numbers are correct answers to
different questions — see the on-page note under the KPI cards, and the "not part of Batch View"
callout in `Dashboard.md`.

> **Related fixed bug**: clicking through from this Excluded tile used to show a permanent, false
> "reconciliation mismatch" banner on the drilldown page — the evidence page was comparing this
> distinct-transaction count against `sum(excluded_txn)` even though the two were never supposed to
> match. See `TransactionReport.md`'s "Overview Excluded / Not Reported drilldown" section for the
> fix (`TransactionReportServiceImpl.getPeriodTransactionReport`'s `aggregateCount` calculation).

### Code Flow — Selected / Expected / Excluded / Not Reported

- **API**: `GET /dashboardDetails/transaction-view` — `DashboardApi.getTransactionDashboard` (`com.pharos.compliance.dashboard.api.DashboardApi`)
- **Controller**: `DashboardController` (`com.pharos.compliance.dashboard.controller.DashboardController`)
- **Service**: `DashboardServiceImpl.getTransactionDashboard` (`com.pharos.compliance.dashboard.service.impl.DashboardServiceImpl`)
- **Repository**: `DashboardRepository.getTransactionOverview` (`com.pharos.compliance.dashboard.repository.DashboardRepository`)
- **DTO**: `TransactionOverviewProjection` → `TransactionDashboardResponse`

## 2. View — Top Exclusion Reasons / Not Reported Breakdown

*(The two legend-and-bar-chart cards beneath the KPI cards. Real example: Top Exclusion Reasons —
Excluded Because Exclusion Exists 93.68% (267), Excluded Because Already Reported (Pharos) 4.21%
(12), Excluded Soft Dedup 2.11% (6). Not Reported Breakdown — Transformation completed 53.56%
(286), Synthetic transformation validation failure, No matching transaction attempt found, and an
"Other" catch-all for everything past the top 3.)*

### Query — Top Exclusion Reasons

```sql
select
  "bucketed_reasons"."reason",
  cast(sum("bucketed_reasons"."count") as bigint) as "count"
from (
  select
    case when "ranked_reasons"."rn" <= 3 then "ranked_reasons"."reason" else 'Other' end as "reason",
    "ranked_reasons"."count" as "count"
  from (
    select
      "reason_counts"."reason",
      "reason_counts"."count",
      row_number() over (order by "reason_counts"."count" desc, "reason_counts"."reason") as "rn"
    from (
      select
        coalesce("roll"."reason", 'Unspecified') as "reason",
        count(*) as "count"
      from (
        -- same "roll" subquery as the KPI query above: one row per (rpt_grp_id, identifier)
        -- with ever_excluded / ever_reported flags, plus one more column here --
        select
          "pharos"."record_transformation_journey"."rpt_grp_id",
          "pharos"."record_transformation_journey"."identifier",
          bool_or((upper(coalesce("pharos"."record_transformation_journey"."status", '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP'))) as "ever_excluded",
          bool_or((
            ("pharos"."record_transformation_journey"."stage" = 'REPORT_GENERATION' and upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'GENERATED')
            or ("pharos"."record_transformation_journey"."stage" = 'TRANSFORMATION' and upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'SUCCESS' and "batch_evidence"."batch_generated" = true)
          )) as "ever_reported",
          max(case
            when upper(coalesce("pharos"."record_transformation_journey"."status", '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP')
              then coalesce("pharos"."record_transformation_journey"."comments", "pharos"."record_transformation_journey"."skip_reason")
          end) as "reason"
        from "pharos"."record_transformation_journey"
          join (/* batch_evidence: same scoped-batches + report_batch_info join as the KPI query */) as "batch_evidence"
            on ("batch_evidence"."rpt_grp_id" = "pharos"."record_transformation_journey"."rpt_grp_id"
                and "batch_evidence"."batch_id" = "pharos"."record_transformation_journey"."batch_id")
        group by "pharos"."record_transformation_journey"."rpt_grp_id", "pharos"."record_transformation_journey"."identifier"
      ) as "roll"
      where ("roll"."ever_excluded" = true and "roll"."ever_reported" = false)
      group by coalesce("roll"."reason", 'Unspecified')
    ) as "reason_counts"
  ) as "ranked_reasons"
) as "bucketed_reasons"
group by "bucketed_reasons"."reason"
order by ("bucketed_reasons"."reason" = 'Other') asc, "count" desc
```

*The `batch_evidence` join is collapsed above for readability — it is byte-for-byte the same
subquery as in the KPI query. The **Not Reported Breakdown** query is identical except the outer
filter is `ever_reported = false and ever_excluded = false`, and the reason column takes
`comments`/`skip_reason` from every row (not just EXCLUDED ones), since a not-reported transaction
was never excluded and so has no exclusion status to key off. Captured result for Top Exclusion
Reasons: `EXCLUDED_BECAUSE_EXCLUSION_EXISTS: 267`, `EXCLUDED_BECAUSE_ALREADY_REPORTED(PHAROS): 12`,
`EXCLUDED_SOFT_DEDUP: 6`.*

### Plain English

This starts from the exact same per-transaction "was it ever excluded / ever reported" rollup as
the KPI query, then asks a follow-up question for the ones that landed in the Excluded (or Not
Reported) bucket: *why*? It picks the transaction's `comments` field (falling back to
`skip_reason` if comments is blank) from whichever of its own rows actually carries a reason, then
counts how many transactions share each reason. To keep the legend readable, it only shows the
top 3 reasons by count and folds everything else into a single "Other" row — a batch with a dozen
rare, one-off reasons doesn't turn into a dozen tiny, unreadable legend rows.

### Code Flow — Top Exclusion Reasons / Not Reported Breakdown

- **API**: same `GET /dashboardDetails/transaction-view` response as the KPI cards (one combined payload)
- **Service**: `DashboardServiceImpl.getTransactionDashboard` → `toExclusionReasonsResponse` / `toNotReportedReasonsResponse`
- **Repository**: `DashboardRepository.getTopExclusionReasons` / `getNotReportedReasons` (`com.pharos.compliance.dashboard.repository.DashboardRepository`)

Clicking a KPI card or a legend row navigates to the Transaction Report evidence explorer, filtered
to that exact bucket (and reason, for legend rows) — see `TransactionReport.md`'s "Overview
Excluded / Not Reported drilldown" section, which uses this same `ever_excluded`/`ever_reported`
roll-up so the drill-through's row count always matches the number that was clicked.
