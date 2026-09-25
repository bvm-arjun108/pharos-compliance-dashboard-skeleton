select
  upper(trim(country_code)) as "countryCode",
  coalesce(nullif(trim(country_name), ''), upper(trim(country_code))) as "countryName",
  rpt_grp_id as "reportGroupId"
from ranked_configs
where config_rank = 1
  and country_code is not null
  and trim(country_code) <> ''
order by "countryName", "countryCode", "reportGroupId"
