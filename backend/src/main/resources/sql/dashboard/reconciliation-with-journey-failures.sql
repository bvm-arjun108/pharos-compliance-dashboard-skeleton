select r.*, jf.journey_transformation_failures
from pharos.report_transformation_reconciliation r
left join journey_failures_by_batch jf
  on jf.rpt_grp_id = r.rpt_grp_id
  and jf.batch_id = r.batch_id
where 1 = 1
