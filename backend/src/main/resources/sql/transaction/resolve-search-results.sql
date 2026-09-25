-- Same "latest version per report group" ordering as ReportGroupConfigRepository's ranked_configs,
-- applied as a correlated scalar subquery instead of a windowed rank -- the result set here is a
-- handful of search matches, not a batch's worth of rows, so the per-row subquery cost is
-- negligible and this avoids building a ranking CTE for a one-column lookup.
select
  m.report_group_id as "reportGroupId",
  coalesce(m.report_group_name, (
    select rpt_grp_name
    from pharos.report_group_config
    where rpt_grp_id = m.report_group_id
    order by modified_timestamp desc nulls last, created_timestamp desc nulls last, rpt_selection_version_id desc, transformer_version_id desc
    limit 1
  )) as "reportGroupName",
  (
    select country_code
    from pharos.report_group_config
    where rpt_grp_id = m.report_group_id
    order by modified_timestamp desc nulls last, created_timestamp desc nulls last, rpt_selection_version_id desc, transformer_version_id desc
    limit 1
  ) as "countryCode",
  (
    select country_name
    from pharos.report_group_config
    where rpt_grp_id = m.report_group_id
    order by modified_timestamp desc nulls last, created_timestamp desc nulls last, rpt_selection_version_id desc, transformer_version_id desc
    limit 1
  ) as "countryName",
  m.batch_id as "batchId",
  m.evidence_source as "evidenceSource",
  m.stage as "stage",
  m.status as "status",
  m.comments as "comments",
  :matchedOnLabel as "matchedOn",
  m.occurred_at as "occurredAt",
  m.mtcn_value as "mtcn"
from matches m
order by m.occurred_at desc nulls last
