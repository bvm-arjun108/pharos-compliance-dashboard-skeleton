-- The journey-stats-lateral.sql fragment is spliced in below (see BatchExplorerRepository),
-- correlated to this query's own "r" alias.
select
  r.rpt_grp_id as "reportGroupId",
  r.rpt_grp_name as "reportGroupName",
  r.batch_id as "batchId",
  r.seq_no as "sequenceNumber",
  r.rpt_from_date as "reportingPeriodFrom",
  r.rpt_to_date as "reportingPeriodTo",
  r.created_timestamp as "startedAt",
  r.modified_timestamp as "completedAt",
  case when journey_stats.journey_available
    then journey_stats.journey_transformation_failures
    else coalesce(r.activity_transformation_failed, 0)::bigint
  end as "transformationFailures",
  coalesce(r.activity_transformation_failed, 0)::bigint as "reportedTransformationFailures",
  (journey_stats.journey_available
    and journey_stats.journey_transformation_failures <> coalesce(r.activity_transformation_failed, 0)::bigint)
    as "transformationFailureMismatch",
  coalesce(r.txn_missing_attempt_count, 0)::bigint as "missingAttempts",
  coalesce(r.activity_missing, 0)::bigint as "activityMissing",
  coalesce(r.duplicate_transformation, 0)::bigint as "duplicateTransactions",
  abs(coalesce(r.expected_reportable_txn, 0) - coalesce(r.actual_reportable_txn, 0))::bigint as "filtrationErrors",
  abs(coalesce(r.expected_activity_eligible_for_transformation, 0) - coalesce(r.actual_activity_eligible_for_transformation, 0))::bigint
    as "reconciliationImbalance",
  coalesce(r.txn_selected, 0)::bigint as "selectedTransactions",
  greatest(coalesce(r.txn_selected, 0) - coalesce(r.txn_missing_attempt_count, 0), 0)::bigint as "transactionAttemptsFound",
  coalesce(r.expected_reportable_txn, 0)::bigint as "expectedReportableTransactions",
  coalesce(r.actual_reportable_txn, 0)::bigint as "actualReportableTransactions",
  coalesce(r.expected_activity_eligible_for_transformation, 0)::bigint as "expectedTransformationAttempts",
  coalesce(r.actual_activity_eligible_for_transformation, 0)::bigint as "actualTransformationAttempts",
  coalesce(r.activity_transformed, 0)::bigint as "transformedActivities",
  coalesce(r.actual_reportable_txn, 0)::bigint as "transformerOutput",
  coalesce(r.excluded_txn, 0)::bigint as "excludedTransactions",
  coalesce(r.txn_simulated, 0)::bigint as "simulatedTransactions",
  coalesce(r.already_reported_count, 0)::bigint as "alreadyReportedTransactions",
  coalesce(r.soft_dedup_dropped_txn_count, 0)::bigint as "softDedupTransactions",
  journey_stats.journey_available as "journeyAvailable",
  exists (
    select 1
    from pharos.rule_hit_exclusion_audit a
    where a.rpt_grp_id = r.rpt_grp_id
      and a.processing_batch_id = r.batch_id
  ) as "exclusionsAvailable",
  bi.selection_version as "reportSelectionVersionId",
  bi.transformer_mapping_version as "transformerVersionId"
from pharos.report_transformation_reconciliation r
left join pharos.report_batch_info bi
  on bi.rpt_grp_id = r.rpt_grp_id
  and bi.batch_id = r.batch_id
  and bi.seq_no = r.seq_no
cross join /*JOURNEY_STATS_LATERAL*/
where r.rpt_grp_id = :reportGroupId
  and r.batch_id = :batchId
  and r.seq_no = :sequenceNumber
