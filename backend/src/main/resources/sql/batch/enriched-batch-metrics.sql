-- See TransformationFailureQueries' own Javadoc for why the reconciliation column and the
-- journey-derived count can disagree. The LEFT JOIN's own NULL (no TRANSFORMATION-stage failure
-- rows at all for this batch) is exactly the "no journey evidence for this fact" signal, so
-- coalescing straight onto it needs no separate journeyAvailable flag here, unlike the single-batch
-- batch-details.sql query where a correlated COUNT can't produce that same NULL.
select
  bm.*,
  coalesce(jf.journey_transformation_failures, bm.reported_transformation_failures) as transformation_failures,
  (jf.journey_transformation_failures is not null and jf.journey_transformation_failures <> bm.reported_transformation_failures)
    as transformation_failure_mismatch,
  coalesce(jf.journey_transformation_failures, bm.reported_transformation_failures) + bm.missing_attempts + bm.activity_missing
    as total_issues
from batch_metrics bm
left join journey_failures_by_batch jf
  on jf.rpt_grp_id = bm.rpt_grp_id
  and jf.batch_id = bm.batch_id
