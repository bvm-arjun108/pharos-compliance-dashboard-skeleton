### Batch Explorer Analysis

## 1. View — Batch work queue

*(Route: `/batches/explorer`. Shows a prioritized, filterable list of every batch in the selected
date range, with a status pill (Successful / Needs Attention), an issue-count badge, and a summary
chip for each specific problem type. Clicking a row loads that batch's detail cards below the
list — see View 2.)*

### Query — Queue and summary counts

```sql
select
  cast(count(*) as bigint) as "all_batches",
  cast(count(*) filter (where "enriched_batch_metrics"."total_issues" = 0) as bigint) as "successful",
  cast(count(*) filter (where "enriched_batch_metrics"."total_issues" > 0) as bigint) as "attention",
  max("enriched_batch_metrics"."rpt_grp_name") as "report_group_name"
from (
  select
    "batch_metrics"."rpt_grp_id",
    "batch_metrics"."batch_id",
    "batch_metrics"."seq_no",
    "batch_metrics"."rpt_grp_name",
    "batch_metrics"."rpt_from_date",
    "batch_metrics"."rpt_to_date",
    "batch_metrics"."created_timestamp",
    "batch_metrics"."modified_timestamp",
    "batch_metrics"."reported_transformation_failures",
    "batch_metrics"."missing_attempts",
    "batch_metrics"."activity_missing",
    "batch_metrics"."filtration_errors",
    "batch_metrics"."reconciliation_imbalance",
    "batch_metrics"."expected_transformation_attempts",
    "batch_metrics"."actual_transformation_attempts",
    "batch_metrics"."transformed_activities",
    "batch_metrics"."transformer_output",
    "batch_metrics"."excluded_transactions",
    "batch_metrics"."duplicate_transactions",
    "batch_metrics"."simulated_transactions",
    "batch_metrics"."soft_dedup_transactions",
    coalesce(
      "journey_failures_by_batch"."journey_transformation_failures",
      "batch_metrics"."reported_transformation_failures"
    ) as "transformation_failures",
    (
      "journey_failures_by_batch"."journey_transformation_failures" is not null
      and "journey_failures_by_batch"."journey_transformation_failures" <> "batch_metrics"."reported_transformation_failures"
    ) as "transformation_failure_mismatch",
    (coalesce(
      "journey_failures_by_batch"."journey_transformation_failures",
      "batch_metrics"."reported_transformation_failures"
    ) + "batch_metrics"."missing_attempts" + "batch_metrics"."activity_missing") as "total_issues"
  from (
    select
      "pharos"."report_transformation_reconciliation"."rpt_grp_id",
      "pharos"."report_transformation_reconciliation"."batch_id",
      "pharos"."report_transformation_reconciliation"."seq_no",
      "pharos"."report_transformation_reconciliation"."rpt_grp_name",
      "pharos"."report_transformation_reconciliation"."rpt_from_date",
      "pharos"."report_transformation_reconciliation"."rpt_to_date",
      "pharos"."report_transformation_reconciliation"."created_timestamp",
      "pharos"."report_transformation_reconciliation"."modified_timestamp",
      cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformation_failed", 0) as bigint) as "reported_transformation_failures",
      cast(coalesce("pharos"."report_transformation_reconciliation"."txn_missing_attempt_count", 0) as bigint) as "missing_attempts",
      cast(coalesce("pharos"."report_transformation_reconciliation"."activity_missing", 0) as bigint) as "activity_missing",
      cast(abs((coalesce("pharos"."report_transformation_reconciliation"."expected_reportable_txn", 0) - coalesce("pharos"."report_transformation_reconciliation"."actual_reportable_txn", 0))) as bigint) as "filtration_errors",
      cast(abs((coalesce("pharos"."report_transformation_reconciliation"."expected_activity_eligible_for_transformation", 0) - coalesce("pharos"."report_transformation_reconciliation"."actual_activity_eligible_for_transformation", 0))) as bigint) as "reconciliation_imbalance",
      cast(coalesce("pharos"."report_transformation_reconciliation"."expected_activity_eligible_for_transformation", 0) as bigint) as "expected_transformation_attempts",
      cast(coalesce("pharos"."report_transformation_reconciliation"."actual_activity_eligible_for_transformation", 0) as bigint) as "actual_transformation_attempts",
      cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformed", 0) as bigint) as "transformed_activities",
      cast(coalesce("pharos"."report_transformation_reconciliation"."actual_reportable_txn", 0) as bigint) as "transformer_output",
      cast(coalesce("pharos"."report_transformation_reconciliation"."excluded_txn", 0) as bigint) as "excluded_transactions",
      cast(coalesce("pharos"."report_transformation_reconciliation"."duplicate_transformation", 0) as bigint) as "duplicate_transactions",
      cast(coalesce("pharos"."report_transformation_reconciliation"."txn_simulated", 0) as bigint) as "simulated_transactions",
      cast(coalesce("pharos"."report_transformation_reconciliation"."soft_dedup_dropped_txn_count", 0) as bigint) as "soft_dedup_transactions"
    from "pharos"."report_transformation_reconciliation"
    where (
      "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-02-01 00:00:00.0'
      and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
      and true -- country filter, "ALL" here
      and true -- report group filter, "ALL" here
      and true -- batch id search filter, empty here
    )
  ) as "batch_metrics"
    left outer join (
      select
        "pharos"."record_transformation_journey"."rpt_grp_id",
        "pharos"."record_transformation_journey"."batch_id",
        cast(count(distinct "pharos"."record_transformation_journey"."identifier") as bigint) as "journey_transformation_failures"
      from "pharos"."record_transformation_journey"
        join (
          select distinct
            "pharos"."report_transformation_reconciliation"."rpt_grp_id" as "scope_rpt_grp_id",
            "pharos"."report_transformation_reconciliation"."batch_id" as "scope_batch_id"
          from "pharos"."report_transformation_reconciliation"
          where (
            "pharos"."report_transformation_reconciliation"."created_timestamp" >= timestamp '2026-02-01 00:00:00.0'
            and "pharos"."report_transformation_reconciliation"."created_timestamp" < timestamp '2026-09-03 00:00:00.0'
            and true and true and true
          )
        ) as "scoped_batches"
          on (
            "scoped_batches"."scope_rpt_grp_id" = "pharos"."record_transformation_journey"."rpt_grp_id"
            and "scoped_batches"."scope_batch_id" = "pharos"."record_transformation_journey"."batch_id"
          )
      where (
        upper("pharos"."record_transformation_journey"."stage") = 'TRANSFORMATION'
        and upper("pharos"."record_transformation_journey"."status") in ('ERROR', 'FAILED', 'FAILURE')
      )
      group by "pharos"."record_transformation_journey"."rpt_grp_id", "pharos"."record_transformation_journey"."batch_id"
    ) as "journey_failures_by_batch"
      on (
        "journey_failures_by_batch"."rpt_grp_id" = "batch_metrics"."rpt_grp_id"
        and "journey_failures_by_batch"."batch_id" = "batch_metrics"."batch_id"
      )
) as "enriched_batch_metrics"
```

*Captured live from the `PrettySqlExecuteListener` debug log, `fromDate=2026-02-01, toDate=2026-09-02, country=ALL`. The paginated list below reuses this exact same `enriched_batch_metrics` subquery — only the outer `select`/`order by`/`offset`/`fetch` differ — so both the "All / Successful / Attention" counters and the rows underneath are always counting the same thing.*

### Plain English

This is two views on one calculation, run as two nearly-identical queries so the summary chips and
the list underneath never disagree:

1. Start from `report_transformation_reconciliation` — one row per batch, with the counters that
   batch's own processing already computed (how many transactions it selected, transformed,
   excluded, and so on).
2. Separately, walk the day-to-day event log (`record_transformation_journey`) for those same
   batches and count, per batch, how many *distinct transactions* actually failed at the
   transformation step. This is the fix from earlier in the project: the reconciliation table's own
   "failed" counter can be stale or wrong (it's a number a batch reports about itself), while the
   journey log is closer to ground truth, so wherever the journey log has an answer, it wins.
3. If the two numbers disagree, flag it (`transformation_failure_mismatch`) instead of silently
   picking one — that flag is what shows up as the small "⚠ Reconciliation shows N" note under a
   batch's Failed count.
4. Add up a batch's total issue count (failures + missing attempts + activity missing) to decide if
   it belongs in "Needs Attention." Duplicates, exclusions, and simulated transactions do **not**
   count toward "Needs Attention" on their own — they're informational, not alarms (this was a
   deliberate call: a batch can have simulated/test transactions and still be perfectly healthy).
5. The very first query above just counts how many batches land in each bucket ("All," "Successful,"
   "Attention") using the exact same per-batch calculation, so the numbers at the top of the page
   and the rows underneath can never drift apart from running two different definitions.

### Code Flow — Queue and summary

- **API**: `GET /api/v1/batches` — `BatchExplorerApi.getBatches` (`com.pharos.compliance.batch.api.BatchExplorerApi`)
- **Controller**: `BatchExplorerController` (`com.pharos.compliance.batch.controller.BatchExplorerController`)
- **Service**: `BatchExplorerServiceImpl.getBatches` (`com.pharos.compliance.batch.service.impl.BatchExplorerServiceImpl`)
- **Repository**: `BatchExplorerRepository.getBatchSummary` / `getBatchQueue` (`com.pharos.compliance.batch.repository.BatchExplorerRepository`)
- **Shared helper**: `TransformationFailureQueries.journeyFailuresByBatch` (`com.pharos.compliance.common.jooq.TransformationFailureQueries`)

## 2. View — Selected batch detail cards (Data Selection / Data Transformation / Skipped Status)

*(Clicking a row in the queue loads this batch's own detail cards: Data Selection — how many
transactions were selected and why some were excluded/simulated/already-reported/soft-deduped;
Data Transformation — expected vs. actual transformation attempts and failures, shown independently
of downstream reporting; Skipped Status — everything that never reached a reportable outcome, split
by *why*; and a Report Configuration summary card. A real example from this project's mock data: a
Portugal Objective batch with `activity_transformation_failed = 0` in reconciliation but 4 distinct
failed identifiers in the journey log renders "Failed: 4" with a "⚠ Reconciliation shows 0" note
directly underneath — the mismatch made visible instead of hidden.)*

### Query — Aggregate counters and evidence availability

```sql
select
  "pharos"."report_transformation_reconciliation"."rpt_grp_id" as "reportGroupId",
  "pharos"."report_transformation_reconciliation"."rpt_grp_name" as "reportGroupName",
  "pharos"."report_transformation_reconciliation"."batch_id" as "batchId",
  "pharos"."report_transformation_reconciliation"."seq_no" as "sequenceNumber",
  "pharos"."report_transformation_reconciliation"."rpt_from_date" as "reportingPeriodFrom",
  "pharos"."report_transformation_reconciliation"."rpt_to_date" as "reportingPeriodTo",
  "pharos"."report_transformation_reconciliation"."created_timestamp" as "startedAt",
  "pharos"."report_transformation_reconciliation"."modified_timestamp" as "completedAt",
  case
    when "journey_stats"."journey_available" then "journey_stats"."journey_transformation_failures"
    else cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformation_failed", 0) as bigint)
  end as "transformationFailures",
  cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformation_failed", 0) as bigint) as "reportedTransformationFailures",
  (
    "journey_stats"."journey_available"
    and "journey_stats"."journey_transformation_failures" <> cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformation_failed", 0) as bigint)
  ) as "transformationFailureMismatch",
  cast(coalesce("pharos"."report_transformation_reconciliation"."txn_missing_attempt_count", 0) as bigint) as "missingAttempts",
  cast(coalesce("pharos"."report_transformation_reconciliation"."activity_missing", 0) as bigint) as "activityMissing",
  cast(coalesce("pharos"."report_transformation_reconciliation"."duplicate_transformation", 0) as bigint) as "duplicateTransactions",
  cast(abs((coalesce("pharos"."report_transformation_reconciliation"."expected_reportable_txn", 0) - coalesce("pharos"."report_transformation_reconciliation"."actual_reportable_txn", 0))) as bigint) as "filtrationErrors",
  cast(abs((coalesce("pharos"."report_transformation_reconciliation"."expected_activity_eligible_for_transformation", 0) - coalesce("pharos"."report_transformation_reconciliation"."actual_activity_eligible_for_transformation", 0))) as bigint) as "reconciliationImbalance",
  cast(coalesce("pharos"."report_transformation_reconciliation"."txn_selected", 0) as bigint) as "selectedTransactions",
  cast(greatest(
    (coalesce("pharos"."report_transformation_reconciliation"."txn_selected", 0) - coalesce("pharos"."report_transformation_reconciliation"."txn_missing_attempt_count", 0)),
    0
  ) as bigint) as "transactionAttemptsFound",
  cast(coalesce("pharos"."report_transformation_reconciliation"."expected_reportable_txn", 0) as bigint) as "expectedReportableTransactions",
  cast(coalesce("pharos"."report_transformation_reconciliation"."actual_reportable_txn", 0) as bigint) as "actualReportableTransactions",
  cast(coalesce("pharos"."report_transformation_reconciliation"."expected_activity_eligible_for_transformation", 0) as bigint) as "expectedTransformationAttempts",
  cast(coalesce("pharos"."report_transformation_reconciliation"."actual_activity_eligible_for_transformation", 0) as bigint) as "actualTransformationAttempts",
  cast(coalesce("pharos"."report_transformation_reconciliation"."activity_transformed", 0) as bigint) as "transformedActivities",
  cast(coalesce("pharos"."report_transformation_reconciliation"."actual_reportable_txn", 0) as bigint) as "transformerOutput",
  cast(coalesce("pharos"."report_transformation_reconciliation"."excluded_txn", 0) as bigint) as "excludedTransactions",
  cast(coalesce("pharos"."report_transformation_reconciliation"."txn_simulated", 0) as bigint) as "simulatedTransactions",
  cast(coalesce("pharos"."report_transformation_reconciliation"."already_reported_count", 0) as bigint) as "alreadyReportedTransactions",
  cast(coalesce("pharos"."report_transformation_reconciliation"."soft_dedup_dropped_txn_count", 0) as bigint) as "softDedupTransactions",
  "journey_stats"."journey_available" as "journeyAvailable",
  exists (
    select 1 as "one"
    from "pharos"."rule_hit_exclusion_audit"
    where (
      "pharos"."rule_hit_exclusion_audit"."rpt_grp_id" = "pharos"."report_transformation_reconciliation"."rpt_grp_id"
      and "pharos"."rule_hit_exclusion_audit"."processing_batch_id" = "pharos"."report_transformation_reconciliation"."batch_id"
    )
  ) as "exclusionsAvailable",
  "pharos"."report_batch_info"."selection_version" as "reportSelectionVersionId",
  "pharos"."report_batch_info"."transformer_mapping_version" as "transformerVersionId"
from "pharos"."report_transformation_reconciliation"
  left outer join "pharos"."report_batch_info"
    on (
      "pharos"."report_batch_info"."rpt_grp_id" = "pharos"."report_transformation_reconciliation"."rpt_grp_id"
      and "pharos"."report_batch_info"."batch_id" = "pharos"."report_transformation_reconciliation"."batch_id"
      and "pharos"."report_batch_info"."seq_no" = "pharos"."report_transformation_reconciliation"."seq_no"
    )
  cross join lateral (
    select
      (count(*) > 0) as "journey_available",
      cast(count(distinct "pharos"."record_transformation_journey"."identifier") filter (where (
        upper("pharos"."record_transformation_journey"."stage") = 'TRANSFORMATION'
        and upper("pharos"."record_transformation_journey"."status") in ('ERROR', 'FAILED', 'FAILURE')
      )) as bigint) as "journey_transformation_failures"
    from "pharos"."record_transformation_journey"
    where (
      "pharos"."record_transformation_journey"."rpt_grp_id" = "pharos"."report_transformation_reconciliation"."rpt_grp_id"
      and "pharos"."record_transformation_journey"."batch_id" = "pharos"."report_transformation_reconciliation"."batch_id"
    )
  ) as "journey_stats"
where (
  "pharos"."report_transformation_reconciliation"."rpt_grp_id" = 1000000007
  and "pharos"."report_transformation_reconciliation"."batch_id" = 'BIN10000000007260827220000'
  and "pharos"."report_transformation_reconciliation"."seq_no" = 1
)
```

*Captured live for report group `1000000007` (PORTUGAL OBJECTIVE), batch `BIN10000000007260827220000` — the batch shown in the screenshot above, which really does have this mismatch in the mock data. Result: `"transformationFailures": 4, "reportedTransformationFailures": 0, "transformationFailureMismatch": true`.*

### Plain English

This is "everything about one batch, on one screen," and it uses a `LATERAL` join (the
`cross join lateral (...) as "journey_stats"` block) as a performance trick: without it, jOOQ would
have had to re-run the "look at the journey log for this batch" subquery multiple times in the same
query (once for the failure count, once for the mismatch flag) — the `LATERAL` join computes both
numbers in a single pass instead.

Walking through what a person actually sees on the page:

- **Data Selection**: how many transactions were selected for this batch (`txn_selected`), and a
  breakdown of every reason some of them didn't go on to be reported — simulated (test) data,
  genuinely excluded, already reported in an earlier batch, dropped as a soft duplicate, missing an
  attempt entirely, or missing the underlying activity record. Each reason is its own scalar column
  the batch already reported about itself.
- **Data Transformation**: expected vs. actual transformation attempts, how many activities were
  actually transformed, and how many failed — with the same "trust the journey log over the
  reconciliation scalar, but show both and flag if they disagree" logic as the queue view above.
- **Skipped Status**: transactions that never reached a reportable outcome, split into *why*:
  never attempted (missing attempts), the expected activity was never found (activity missing), or
  attempted and failed (transformation failures) — three different failure modes that all end in
  "nothing was reported for this transaction," kept separate because the fix for each is different.
- **`exclusionsAvailable`**: a cheap yes/no check for whether this batch has any rows at all in the
  rule-hit exclusion audit table, used only to decide whether to show an "Exclusions" tab — not to
  compute a count.

### Code Flow — Selected batch detail cards

- **API**: `GET /api/v1/batches/{reportGroupId}/{batchId}/{sequenceNumber}` — `BatchExplorerApi.getBatchDetails` (`com.pharos.compliance.batch.api.BatchExplorerApi`)
- **Controller**: `BatchExplorerController` (`com.pharos.compliance.batch.controller.BatchExplorerController`)
- **Service**: `BatchExplorerServiceImpl.getBatchDetails` (`com.pharos.compliance.batch.service.impl.BatchExplorerServiceImpl`)
- **Repository**: `BatchExplorerRepository.getBatchDetails` (`com.pharos.compliance.batch.repository.BatchExplorerRepository`)
- **Shared helper**: `TransformationFailureQueries.journeyStatsLateral` (`com.pharos.compliance.common.jooq.TransformationFailureQueries`)
- **DTO**: `BatchDetailsResponse` (`com.pharos.compliance.batch.dto.BatchDetailsResponse`)

Every "View transactions →" / "View report →" link on these cards navigates to the Transaction
Report evidence explorer, scoped to this one batch and one metric — see `TransactionReport.md`.
