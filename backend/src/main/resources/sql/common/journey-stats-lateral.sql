-- Single-batch LATERAL derived table exposing journey_available (boolean) and
-- journey_transformation_failures (bigint), each computed exactly once per outer row -- see
-- com.pharos.compliance.common.jdbc.TransformationFailureQueries#journeyStatsLateral. The outer
-- row's own (rpt_grp_id, batch_id) column references are spliced in by the caller below (never a
-- bound value -- this is a structural correlated join, not user input).
lateral (
  select
    count(*) > 0 as journey_available,
    (count(distinct identifier) filter (
      where upper(stage) = 'TRANSFORMATION' and upper(status) in ('ERROR', 'FAILED', 'FAILURE')
    ))::bigint as journey_transformation_failures
  from pharos.record_transformation_journey
  where rpt_grp_id = /*OUTER_RPT_GRP_ID*/
    and batch_id = /*OUTER_BATCH_ID*/
) journey_stats
