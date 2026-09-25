-- Per-batch grouped journey-derived transformation-failure count, used by every caller that needs
-- to LEFT JOIN it on (rpt_grp_id, batch_id) and coalesce to 0 for batches with no failures. See
-- com.pharos.compliance.common.jdbc.TransformationFailureQueries#journeyFailuresByBatch. The
-- marked spot below is replaced with the caller's own reconciliation scope conditions (the exact
-- same ones applied to the caller's own reconciliation query), so this aggregate is bounded to
-- only the batches actually in view rather than every TRANSFORMATION-stage failure row that has
-- ever existed. The inner subquery's "r" alias lets a caller's scope condition be qualified (e.g.
-- "r.rpt_grp_id") when the caller's own outer query also joins this fragment's own output -- whose
-- columns share the same unqualified names -- without an ambiguous-column error; an unqualified
-- scope condition still resolves fine here too, since "r" is the only table in this subquery.
select
  journey.rpt_grp_id,
  journey.batch_id,
  count(distinct journey.identifier)::bigint as journey_transformation_failures
from pharos.record_transformation_journey journey
join (
  select distinct r.rpt_grp_id as scope_rpt_grp_id, r.batch_id as scope_batch_id
  from pharos.report_transformation_reconciliation r
  where 1 = 1
  /*SCOPE*/
) scoped_batches
  on scoped_batches.scope_rpt_grp_id = journey.rpt_grp_id
  and scoped_batches.scope_batch_id = journey.batch_id
where upper(journey.stage) = 'TRANSFORMATION'
  and upper(journey.status) in ('ERROR', 'FAILED', 'FAILURE')
group by journey.rpt_grp_id, journey.batch_id
