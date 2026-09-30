-- See TransformationFailureQueries' own Javadoc for why the reconciliation column and the
-- journey-derived count can disagree. jf.journey_transformation_failures is NULL when the batch has
-- no TRANSFORMATION-stage failure rows at all -- exactly the "no journey evidence for this fact"
-- signal -- so coalescing straight onto it needs no separate journeyAvailable flag here, unlike the
-- single-batch batch-details.sql query.
--
-- The count is a per-batch lateral probe into idx_journey_transformation_failure rather than a
-- grouped subquery joined back on (rpt_grp_id, batch_id). The planner cannot estimate the group
-- count of that grouped subquery (it predicts ~1 row for thousands), and on a large date range
-- combined with ORDER BY/LIMIT it then picks a nested loop that compares every batch against every
-- group -- tens of seconds instead of milliseconds. A per-batch probe has no such plan to get
-- wrong: its cost is one index lookup per batch in scope. count(*) equals count(distinct
-- identifier) because (rpt_grp_id, batch_id, identifier) is the journey table's primary key, and
-- nullif(..., 0) reproduces the NULL that an absent group row used to produce.
select
  bm.*,
  coalesce(jf.journey_transformation_failures, bm.reported_transformation_failures) as transformation_failures,
  (jf.journey_transformation_failures is not null and jf.journey_transformation_failures <> bm.reported_transformation_failures)
    as transformation_failure_mismatch,
  coalesce(jf.journey_transformation_failures, bm.reported_transformation_failures) + bm.missing_attempts + bm.activity_missing
    as total_issues
from batch_metrics bm
left join lateral (
  select nullif(count(*), 0)::bigint as journey_transformation_failures
  from pharos.record_transformation_journey journey
  where journey.rpt_grp_id = bm.rpt_grp_id
    and journey.batch_id = bm.batch_id
    and upper(journey.stage) = 'TRANSFORMATION'
    and upper(journey.status) in ('ERROR', 'FAILED', 'FAILURE')
) jf on true
