select
  rpt_grp_id as "reportGroupId",
  rpt_grp_name as "reportGroupName",
  upper(trim(country_code)) as "countryCode"
from ranked_configs
where config_rank = 1
order by "reportGroupName" nulls last, "reportGroupId"
