### Transaction Report (Evidence Explorer) Analysis

*(Route: `/transactions`. This page never has its own nav link — it's always reached by clicking
through from somewhere else: a KPI card or legend row on Transactions Overview, a "View
transactions →" link on a Batch Explorer card, or the "Excluded" column in the Report Groups
Requiring Attention table. It shows the actual transaction-level records behind whichever number
was clicked, so a compliance analyst can see *which* transactions make up a count, not just the
count itself. Real example captured: clicking "Excluded" on a Portugal Objective batch with 8
exclusions opens this page pre-filtered to that batch, showing 8 rows, each with its own MTCN,
batch ID, status, and comments column explaining why.)*

This page is powered by one Java class, `TransactionReportRepository`, but it actually runs
**three different query pipelines** depending on how you arrived here. They deliberately are not
unified into one, because they answer three different questions (see each section's Plain English
below) — the project went through several rounds of finding real bugs caused by earlier attempts to
share logic between them, and settled on "each pipeline owns its own definition."

## 1. Batch-scoped evidence — from a Batch Explorer "View transactions →" link

### Query — Count filtered transaction evidence records for one batch

```sql
select count(distinct ("filtered_evidence"."evidence_batch_id", "filtered_evidence"."identifier"))
from (
  select "metric_scoped".* -- every evidence column, already narrowed to the metric/search/status the page asked for
  from (
    select "evidence".*
    from (
      -- Branch 1: the day-to-day processing log
      select
        ('JOURNEY:' || "pharos"."record_transformation_journey"."identifier") as "record_key",
        "pharos"."record_transformation_journey"."identifier" as "identifier",
        "pharos"."record_transformation_journey"."mtcn" as "mtcn",
        "pharos"."record_transformation_journey"."batch_id" as "evidence_batch_id",
        'JOURNEY' as "evidence_source",
        "pharos"."record_transformation_journey"."stage" as "stage",
        "pharos"."record_transformation_journey"."status" as "status",
        case
          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) in ('ERROR', 'FAILED', 'FAILURE') then 'ERROR'
          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) in ('SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED') then 'SUCCESS'
          when upper(coalesce("pharos"."record_transformation_journey"."status", '')) = 'EXCLUDED' then 'EXCLUDED'
          else 'PENDING'
        end as "outcome",
        "pharos"."record_transformation_journey"."comments" as "comments",
        "pharos"."record_transformation_journey"."skip_reason" as "skip_reason"
        -- ...plus ~18 more columns (rule_id, exclusion_reason, timestamps, currency, sender/receiver
        -- fields all null here since a journey row doesn't carry them) --
      from "pharos"."record_transformation_journey"
      where (
        "pharos"."record_transformation_journey"."rpt_grp_id" = 1000000007
        and "pharos"."record_transformation_journey"."batch_id" = 'BIN10000000007260827220000'
      )

      union all

      -- Branch 2: the rule-engine's own exclusion audit trail
      select
        ((((('EXCLUSION:' || cast("pharos"."rule_hit_exclusion_audit"."bucket_id" as varchar)) || ':') || "pharos"."rule_hit_exclusion_audit"."rule_id") || ':') || cast("pharos"."rule_hit_exclusion_audit"."attempt_id" as varchar)) as "record_key",
        coalesce(cast("pharos"."rule_hit_exclusion_audit"."external_txn_key" as text), cast("pharos"."rule_hit_exclusion_audit"."attempt_id" as text)) as "identifier",
        "pharos"."rule_hit_exclusion_audit"."mtcn" as "mtcn",
        "pharos"."rule_hit_exclusion_audit"."processing_batch_id" as "evidence_batch_id",
        'EXCLUSION_AUDIT' as "evidence_source",
        'EXCLUSION' as "stage",
        'EXCLUDED' as "status",
        'EXCLUDED' as "outcome",
        cast(null as text) as "comments",
        cast(null as text) as "skip_reason"
        -- ...plus rule_id, exclusion_reason, exclusion_strategy, reported_batch_id, timestamps --
      from "pharos"."rule_hit_exclusion_audit"
      where (
        "pharos"."rule_hit_exclusion_audit"."rpt_grp_id" = 1000000007
        and "pharos"."rule_hit_exclusion_audit"."processing_batch_id" = 'BIN10000000007260827220000'
      )

      union all

      -- Branch 3: the rule engine's hit log, joined back to a journey identifier by external key or MTCN
      select
        ((((('RULE_HIT:' || cast("rule_hit_matches"."bucket_id" as varchar)) || ':') || "rule_hit_matches"."rule_id") || ':') || cast("rule_hit_matches"."attempt_id" as varchar)) as "record_key",
        "rule_hit_matches"."matched_identifier" as "identifier",
        "rule_hit_matches"."mtcn" as "mtcn",
        "rule_hit_matches"."efile_batch_id" as "evidence_batch_id",
        'RULE_HIT' as "evidence_source",
        'RULE_HIT' as "stage",
        case when "rule_hit_matches"."is_reported" then 'REPORTED' else 'NOT_REPORTED' end as "status",
        case when "rule_hit_matches"."is_reported" then 'SUCCESS' else 'PENDING' end as "outcome",
        cast(null as text) as "comments",
        cast(null as text) as "skip_reason"
        -- ...plus currency, transaction date/side, source, activity type, send date --
      from (
        select "pharos"."rule_hit".*, coalesce("by_identifier_lookup"."identifier", "by_mtcn_lookup"."identifier") as "matched_identifier"
        from "pharos"."rule_hit"
          left outer join (/* journey rows in this batch, keyed by numeric identifier */) as "by_identifier_lookup"
            on "by_identifier_lookup"."identifier_bigint" = "pharos"."rule_hit"."external_txn_key"
          left outer join (/* journey rows in this batch, keyed by mtcn */) as "by_mtcn_lookup"
            on "by_mtcn_lookup"."mtcn" = "pharos"."rule_hit"."mtcn"
        where ("pharos"."rule_hit"."rpt_grp_id" = 1000000007 and "pharos"."rule_hit"."efile_batch_id" = 'BIN10000000007260827220000')
      ) as "rule_hit_matches"
    ) as "evidence"
  ) as "metric_scoped"
) as "filtered_evidence"
```

*Captured live and condensed for readability — the real generated SQL repeats every column (not
just the ones shown) across all three `union all` branches and is ~650 lines long once the
final ranking/merge step (below) is included in full. The shape above is complete; what's
abbreviated is the repetition, not the logic.*

### Query — Final priority merge (one row per real-world transaction)

```sql
select
  "ranked"."evidence_batch_id",
  "ranked"."identifier",
  (array_agg("ranked"."record_key" order by "ranked"."merge_source_rank" asc, "ranked"."record_key" asc)
    filter (where "ranked"."record_key" is not null))[1] as "record_key",
  (array_agg("ranked"."mtcn" order by "ranked"."merge_source_rank" asc, "ranked"."record_key" asc)
    filter (where "ranked"."mtcn" is not null))[1] as "mtcn"
  -- ...the exact same array_agg(...)[1] pattern repeats for every one of the ~27 evidence columns
  -- (status, outcome, comments, exclusion_reason, currency_amount, sender/receiver fields, etc.) --
from ("evidence branch, ranked by merge_source_rank: JOURNEY beats EXCLUSION_AUDIT beats RULE_HIT") as "ranked"
group by "ranked"."evidence_batch_id", "ranked"."identifier"
```

*This is the "27-column priority merge" referenced throughout the project's code comments. Full,
uncollapsed SQL for both queries is available any time by running the app locally with
`JOOQ_SQL_LOG_LEVEL=DEBUG` (the default) and reading the `PrettySqlExecuteListener` console output
for a `/transactions` request — the queries are deterministic and will look exactly like this,
just longer.*

### Plain English

A single real-world transaction can leave evidence in **three different tables**, and none of them
is the full picture on its own:

- `record_transformation_journey` — the moment-by-moment processing log (selected → transformed →
  excluded/reported/failed). Most complete, but a transaction excluded by a rule may never get a
  journey row explaining *why*.
- `rule_hit_exclusion_audit` — the rule engine's own record of "I excluded this because of rule X."
  Has the *why*, but nothing else about the transaction.
- `rule_hit` — the rule engine's hit log, which knows whether a transaction was ultimately reported,
  but doesn't use the same identifier as the journey log (it uses an external transaction key or an
  MTCN instead), so it has to be joined back to a journey identifier before it can be compared.

The query stacks all three sources on top of each other (`union all`), giving every row a common
shape, then **for each real transaction, picks one best answer per column** instead of showing
three separate half-complete rows. "Best" means: prefer the journey log's answer first, fall back
to the exclusion audit, fall back to the rule hit log last — because the journey log is the most
complete and most current source when it has an opinion at all. The `array_agg(... order by rank)
[1]` pattern is just SQL's way of saying "sort my candidates by priority and take the first one,"
repeated once per column since Postgres doesn't have a built-in "coalesce across grouped rows."

The very first query (the plain `count(distinct ...)`) is simpler: it's just "how many distinct
transactions are in this merged, filtered result" — the number shown as "N matching" at the top of
the page, computed once so the page doesn't have to fetch every row just to know the total.

### Code Flow — Batch-scoped evidence

- **API**: `GET /api/v1/transactions/report` — `TransactionReportApi.getTransactionReport` (`com.pharos.compliance.transaction.api.TransactionReportApi`)
- **Controller**: `TransactionReportController` (`com.pharos.compliance.transaction.controller.TransactionReportController`)
- **Repository**: `BatchEvidenceQueries.evidenceForBatch` → `filteredEvidenceForBatch` → `findEvidenceRecords` / `countEvidenceRecords` (`com.pharos.compliance.transaction.repository.evidence.BatchEvidenceQueries`)
- **Shared helpers**: `RuleHitMatcher.ruleHitMatches` (the external-key/MTCN join), `EvidencePaginator.pageEvidence` (the ranking + merge + two-pass pagination engine every pipeline on this page reuses)
- **Facade**: `TransactionReportRepository` (`com.pharos.compliance.transaction.repository.TransactionReportRepository`) — thin delegation layer wiring all evidence pipelines together
- **Service**: `TransactionReportServiceImpl` (`com.pharos.compliance.transaction.service.impl.TransactionReportServiceImpl`)
- **Frontend**: `transaction-report.component.ts` (`batchId` set, `overviewOnly` false)

## 2. Period-scoped evidence — every status except Excluded/Not Reported

*(Reached from a Transactions Overview KPI/legend click for anything other than Excluded or Not
Reported — e.g. clicking through a "Selected" or a specific rule-hit outcome across the whole
period instead of one batch.)*

### Plain English

Structurally the same three-source union-and-merge as the batch-scoped pipeline above, just scoped
to *every batch in the date range* instead of one batch — `scope` becomes a whole set of
`(rpt_grp_id, batch_id)` pairs picked by the page's date/country/report-group filters, resolved
once up front (`batchScope`) and then reused by all three branches so they all agree on which
batches are "in." Everything else — the priority merge, the pagination — works exactly the same
way as the batch-scoped version, just over a bigger `scope`.

### Code Flow — Period-scoped evidence

- **API**: `GET /api/v1/transactions/period-report` — `TransactionReportApi.getPeriodTransactionReport` (`com.pharos.compliance.transaction.api.TransactionReportApi`)
- **Repository**: `PeriodEvidenceQueries.evidenceForPeriod` → `filteredEvidenceForPeriod` → `findEvidenceRecords` / `countEvidenceRecords` (`com.pharos.compliance.transaction.repository.evidence.PeriodEvidenceQueries`)
- **Frontend**: `transaction-report.component.ts` (`batchId` empty, `overviewOnly` false)

## On-demand detail — `GET /api/v1/transactions/detail`

*(Backs the expanded row. The list no longer carries these fields at all — the frontend fetches
them when a row is opened, and caches them per row for the life of the current result set.)*

### What the split changed, measured

| | before | after |
|---|---|---|
| Fields per list row | 44 | 14 |
| PII in the list payload | 10 party fields on every row | none |
| List payload (100 rows) | ~130 KB | ~47 KB |
| Excluded drilldown list, cold | ~205 ms | ~70 ms |
| Opening one row (detail request) | 0 ms (already shipped) | ~3–12 ms |

The list query no longer joins `reg_reportable_activity`, no longer computes the rule-hit rollup,
and no longer builds the rule-hit bridge at all — `EvidenceProjection.LIST` skips the bridge
because nothing in that projection reads it. That is why the list is now faster than it was even
before the rule-hit correctness fix, rather than merely recovering the ground that fix cost. The
detail request's own cost dropped further still once the rule-hit match narrowed to the exact
batch — see the note at the end of the starvation-fix section below.

### Plain English

The expanded panel's fields — party names, dates of birth, phone numbers, ID numbers, amounts, and
the rule-hit list — are read only when someone actually opens a transaction, so they're fetched only
then, rather than for all 100 rows of every page load. That also makes access to personal data
attributable to a specific request instead of to every page view.

Three things it has to get right, all learned the hard way in this codebase:

- **`scope` is required.** BATCH resolves rule hits inside one `efile_batch_id`; PERIOD resolves
  them across every batch of the report group in the window. These are genuinely different
  questions, so the caller passes the scope its row was listed under. `reportGroupId` follows the
  same split: required for BATCH (a batch always belongs to one), optional for PERIOD, which can
  legitimately span every report group in the window exactly as the list does — an earlier version
  of this endpoint required it unconditionally, which silently broke every detail request from a
  Transactions Overview view scoped to "All report groups" (the panel opened and rendered nothing,
  with no error).
- **It passes the list's own filters through, plus the exact `recordKey`.** The panel is an
  expansion of a *specific row*, so it is built by running the same pipeline with the same filters
  and keeping the matching row. Rebuilding the union without them was tried first and was wrong:
  with the metric filter absent, a RULE_HIT source row re-entered the merge and outranked the
  JOURNEY row the list had shown, so the panel reported a different record key, amount precision and
  bucket/attempt than the row above it. `recordKey` (namespaced as
  `SOURCE:reportGroupId:batchId:identifier`) is pushed into the query as an exact-match SQL
  predicate before pagination runs, rather than fetching a handful of candidate rows and picking the
  right one in application code — the latter was an earlier, weaker version of this same lookup and
  could in principle have matched the wrong row for an identifier that also appeared as another
  transaction's MTCN substring.
- **A missing prerequisite fails loudly.** The frontend surfaces "Could not load details for this
  transaction" with a Retry button rather than silently returning nothing — the exact bug the
  optional-`reportGroupId` case above shipped as before the guard was fixed.

Those filters narrow *which row* comes back; they never narrow its rule-hit enrichment — that
distinction is the same one the starvation bug turned on, and going through the existing
`findEvidenceRecords` preserves it automatically, since the enrichment bridge is page-bounded
independently of the filters.

Verified field-for-field against all three pipelines (batch `metric=TRANSFORMED`, batch
`metric=ALL`, and the overview `status=EXCLUDED` drilldown, with and without `reportGroupId`):
every field the panel renders, including `ruleHitsJson`, matches the list row exactly.

### A note on query strings and logging

This endpoint's query string carries `identifier` and `recordKey` on every request, and the list
endpoints carry `search` (typically an MTCN) — all customer-linkable. That is why
`JOOQ_SQL_LOG_LEVEL` defaults to `OFF` (see `Dashboard.md`), and it is also why the nginx configs
(`deploy/nginx/pharos-dashboard.conf`, `frontend/nginx.conf`) now log a custom `pharos_no_query`
format instead of the default `combined`, which would otherwise persist the full request line —
query string and all — to the access log on every row a compliance analyst opens.

## 3. Overview Excluded / Not Reported drilldown

*(Reached specifically from the Transactions Overview page's Excluded / Not Reported KPI cards or
Top Exclusion Reasons / Not Reported Breakdown legend rows — see `TransactionOverview.md`. This is
the one exception: it does **not** use the three-source union above at all.)*

### Plain English

Excluded and Not Reported on the Transactions Overview page are computed from the "ever
excluded"/"ever reported" per-transaction rollup documented in `TransactionOverview.md` — the same
`bool_or(...)` logic across a transaction's journey rows within the selected window, not a
per-batch merge of three evidence sources. So when you click through from there, the drilldown
reuses that *exact same* rollup (re-running the identical SQL, then just selecting the identifiers
that belong to the clicked bucket/reason) instead of running the three-way evidence merge — this is
deliberate: it's what makes the row count on this page always match the number that was clicked,
even though every other status on this page uses the union-and-merge pipeline instead. An earlier
attempt to make this drilldown share the union-and-merge pipeline caused a real bug — a transaction
reprocessed across several batches showed up once per batch instead of once, since the merge step
groups by `(batch, identifier)` while this rollup is deliberately per-identifier only, independent
of which batch it came from.

### Fixed bug — the Rule Hit Details panel was starved on every non-ALL status

The `ruleHitMatches` table passed to `EvidencePaginator.pageEvidence` feeds **only** the "Rule Hit
Details" enrichment — the union branch is built separately by the caller. But every caller used to
pass the *same* table it had built for the union branch, and that one is deliberately
short-circuited to zero rows (`where false`) for any status a RULE_HIT evidence row can't carry.

On this drilldown that short-circuit always fired (its status is always EXCLUDED or NOT_REPORTED),
so every expanded row rendered "No rule hits matched for this transaction" — directly beneath a
subnote promising *"Every pharos.rule_hit record matched to this transaction's identifier,
independent of this row's own evidence source."* The same defect hit the period list for
SUCCESS/FAILED/ERROR/NOT_YET_REPORTED, the Report Groups Requiring Attention excluded drilldown,
and the Batch Explorer list whenever its Status filter was set to anything but All/Reported.

In a compliance tool that's a misleading-evidence bug, not a cosmetic one: an analyst could
reasonably read it as "no rule ever fired on this transaction."

The fix splits the two consumers — `ruleHitMatchesFor{Period,Batch}Enrichment` is status-independent
(still scoped to the same report groups and batches), while the union branch keeps its
short-circuit, so list membership and counts are provably unchanged. `RuleHitEnrichmentScopeTest`
locks both halves in.

Making the enrichment run where it previously didn't costs something, so the bridge is also now
**page-bounded**: `pageEvidence` takes the bridge as a function and applies it only once Pass 1 has
chosen the page, passing that page's identifiers in. Previously it was built across the whole
window and then correlated against — on one real batch that meant materializing ~11,000 rows to
answer a 25-row question, with a planner row-estimate off by ~50×, which is the kind of gap that
silently flips to a nested loop under different statistics. Measured on the Excluded drilldown
(US / report group 51, 307 batches in scope) at the time: ~85 ms before the correctness fix,
~205 ms with it and the bridge unbounded, ~171 ms once page-bounded.

That residual was the `rule_hit` scan itself: `PeriodEvidenceQueries#ruleHitMatchesForPeriodEnrichment`
originally matched against every `rule_hit` row in the report group (`RULE_HIT.RPT_GRP_ID.in(...)`),
narrowed only by the journey side. It has since been tightened to `RuleHitMatcher#scopedRuleHitMatches`,
which matches on the exact `(rpt_grp_id, efile_batch_id)` pairs the batches in scope actually cover —
the same per-batch discipline `BatchEvidenceQueries#ruleHitMatchesForBatch` already applied on the
batch-scoped side. Once the detail request moved off the list path entirely (see the "On-demand
detail" section above), this stopped being a per-page-load cost and became the detail request's own
cost instead: opening one row now measures ~3–12 ms, most of it JIT/connection warm-up on the first
call of a run.

### Fixed bug — `aggregateCount` was comparing this drilldown against the wrong scalar

Every evidence page shows an `aggregateCount` next to the actual row count, as a cross-check: "does
what we found match what the system expected?" For every status except Excluded, `aggregateCount`
is simply set equal to the matching row count — there's no independent reconciliation number to
compare it against, so it trivially "matches itself." Excluded is the one status that *does* have
an independent number to check against — `report_transformation_reconciliation.excluded_txn` — but
only in the batch-scoped case (`batchScopedExcluded=true`), because that's the one query
(`PeriodEvidenceQueries#countExcludedEvidenceRecordsForBatchTotal`) that's actually defined to equal
that scalar exactly.

The code used to set `aggregateCount = aggregate.totalExcluded()` for **every** Excluded request,
regardless of the flag — including the default, non-batch-scoped case covered by this section, whose
count comes from the distinct-transaction rollup described above. That rollup answers a different,
legitimately smaller question (deduplicated per transaction, across the whole window), so pairing it
with the batch-total `excluded_txn` scalar produced a permanent false mismatch — a real example from
this project's investigation showed "8,038 matching of 8,451 in reconciliation" on every single
load of this drilldown, with `evidenceLevel`/`evidenceMessage` reporting roughly 400 transactions as
"missing evidence" that were never actually in scope for that comparison. The fix: only pull
`aggregateCount` from the reconciliation scalar when `batchScopedExcluded` is also true; otherwise
(the default case, and Not Reported, which never has this flag) `aggregateCount` mirrors the
matching count, exactly like every other status.

### Code Flow — Overview Excluded / Not Reported drilldown

- **API**: same `GET /api/v1/transactions/period-report`, with `status=EXCLUDED` or `status=NOT_REPORTED`
- **Routing**: `TransactionReportRepository.findPeriodEvidenceRecords` routes these two status values to `OverviewEvidenceQueries` instead of `PeriodEvidenceQueries`
- **Repository**: `OverviewEvidenceQueries.reportingRoll` → `reportingTarget` → `findOverviewEvidenceRecords` / `countOverviewEvidenceRecords` (`com.pharos.compliance.transaction.repository.evidence.OverviewEvidenceQueries`)
- **Exception**: `batchScopedExcluded=true` (used only by the Report Groups Requiring Attention table's own "Excluded" column) routes Excluded back to a simple, batch-scoped journey query instead — see `PeriodEvidenceQueries.filteredExcludedEvidenceForBatchTotal`, which matches `SUM(excluded_txn)` exactly rather than the distinct-transaction rollup
- **Frontend**: `transaction-report.component.ts` (`overviewOnly` true, or `batchScopedExcluded` true for the Report Groups Requiring Attention case)
