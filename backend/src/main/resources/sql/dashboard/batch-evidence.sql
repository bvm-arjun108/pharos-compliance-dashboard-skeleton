select
  bs.rpt_grp_id,
  bs.batch_id,
  coalesce(bi.compiler_status = 'Report Generation Completed' or bi.report_status in ('ALL', 'PARTIAL'), false) as batch_generated
from batch_scope bs
left join pharos.report_batch_info bi
  on bi.rpt_grp_id = bs.rpt_grp_id
  and bi.batch_id = bs.batch_id
