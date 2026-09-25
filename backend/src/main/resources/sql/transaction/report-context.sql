-- The journey-stats-lateral.sql fragment is spliced in below (see TransactionReportRepository),
-- correlated to this query's own "r" alias. See TransformationFailureQueries' own Javadoc for why
-- "failed"/"reportedFailed"/"failedMismatch" fall back to the raw reconciliation scalar whenever
-- the batch has no journey rows at all, exactly mirroring BatchExplorerRepository#getBatchDetails.
select
  r.rpt_grp_id as "reportGroupId",
  r.rpt_grp_name as "reportGroupName",
  r.batch_id as "batchId",
  r.seq_no as "sequenceNumber",
  r.rpt_from_date as "reportingPeriodFrom",
  r.rpt_to_date as "reportingPeriodTo",
  coalesce(r.txn_selected, 0)::bigint as "selectedTransactions",
  greatest(coalesce(r.txn_selected, 0) - coalesce(r.txn_missing_attempt_count, 0), 0)::bigint as "attemptsFound",
  coalesce(r.txn_missing_attempt_count, 0)::bigint as "missingAttempts",
  coalesce(r.activity_missing, 0)::bigint as "activityMissing",
  coalesce(r.expected_activity_eligible_for_transformation, 0)::bigint as "expectedEligible",
  coalesce(r.actual_activity_eligible_for_transformation, 0)::bigint as "actualEligible",
  coalesce(r.activity_transformed, 0)::bigint as "transformed",
  case when journey_stats.journey_available
    then journey_stats.journey_transformation_failures
    else coalesce(r.activity_transformation_failed, 0)::bigint
  end as "failed",
  coalesce(r.activity_transformation_failed, 0)::bigint as "reportedFailed",
  (journey_stats.journey_available
    and journey_stats.journey_transformation_failures <> coalesce(r.activity_transformation_failed, 0)::bigint)
    as "failedMismatch",
  coalesce(r.expected_reportable_txn, 0)::bigint as "expectedReportable",
  coalesce(r.actual_reportable_txn, 0)::bigint as "actualReportable",
  coalesce(r.excluded_txn, 0)::bigint as "excluded",
  coalesce(r.txn_simulated, 0)::bigint as "simulated",
  coalesce(r.already_reported_count, 0)::bigint as "alreadyReported",
  coalesce(r.soft_dedup_dropped_txn_count, 0)::bigint as "softDedup",
  abs(coalesce(r.expected_reportable_txn, 0) - coalesce(r.actual_reportable_txn, 0))::bigint as "filtrationVariance",
  abs(coalesce(r.expected_activity_eligible_for_transformation, 0) - coalesce(r.actual_activity_eligible_for_transformation, 0))::bigint
    as "reconciliationVariance"
from pharos.report_transformation_reconciliation r
cross join /*JOURNEY_STATS_LATERAL*/
where r.rpt_grp_id = :reportGroupId
  and r.batch_id = :batchId
  and r.seq_no = :sequenceNumber
