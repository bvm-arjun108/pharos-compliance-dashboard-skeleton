select distinct trim(reg_rpt_type) as "reportType"
from ranked_configs
where config_rank = 1
  and reg_rpt_type is not null
  and trim(reg_rpt_type) <> ''
order by "reportType"
