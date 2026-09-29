) scoped_batches
  on scoped_batches.scope_rpt_grp_id = journey.rpt_grp_id
  and scoped_batches.scope_batch_id = journey.batch_id
where upper(journey.stage) = 'TRANSFORMATION'
  and upper(journey.status) in ('ERROR', 'FAILED', 'FAILURE')
group by journey.rpt_grp_id, journey.batch_id
