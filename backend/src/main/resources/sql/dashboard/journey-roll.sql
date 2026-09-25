select
  journey.rpt_grp_id,
  journey.identifier,
  bool_or(upper(coalesce(journey.status, '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP')) as ever_excluded,
  bool_or(
    (journey.stage = 'REPORT_GENERATION' and upper(coalesce(journey.status, '')) = 'GENERATED')
    or (journey.stage = 'TRANSFORMATION' and upper(coalesce(journey.status, '')) = 'SUCCESS' and batch_evidence.batch_generated)
  ) as ever_reported
  %%EXTRA_COLUMN%%
from pharos.record_transformation_journey journey
join batch_evidence
  on batch_evidence.rpt_grp_id = journey.rpt_grp_id
  and batch_evidence.batch_id = journey.batch_id
group by journey.rpt_grp_id, journey.identifier
