select
  count(distinct rpt_grp_id) as "totalConfigurations",
  count(*) filter (where config_active_flag) as "activeConfigurations",
  count(distinct upper(trim(country_code)))
    filter (where country_code is not null and trim(country_code) <> '') as "representedCountries",
  count(*) filter (where lower(trim(reg_rpt_type)) = 'objective') as "objectiveConfigurations",
  count(*) filter (where lower(trim(reg_rpt_type)) = 'subjective') as "subjectiveConfigurations"
from filtered_configs
