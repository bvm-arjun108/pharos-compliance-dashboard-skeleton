-- The "latest version per report group" ranking shared by findCountryMappings,
-- findReportGroupOptions and findReportTypes. Spliced in as the "ranked_configs" CTE by
-- ReportGroupConfigRepository -- see its rankedConfigsCte() javadoc.
select
  *,
  row_number() over (
    partition by rpt_grp_id
    order by
      modified_timestamp desc nulls last,
      created_timestamp desc nulls last,
      rpt_selection_version_id desc,
      transformer_version_id desc
  ) as config_rank
from pharos.report_group_config
