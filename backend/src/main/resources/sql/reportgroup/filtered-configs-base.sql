-- Every report_group_config row's own version-level active flag, columns unaliased so both
-- getSummary.sql and find-report-configs.sql can read them directly off the "filtered_configs"
-- CTE. The WHERE clause is appended in Java (see
-- ReportGroupConfigRepository#filteredConfigsFragment) from a small set of fixed, non-injectable
-- conditions -- never the caller's raw country/status/reportType strings themselves.
select
  rpt_grp_id,
  rpt_grp_name,
  rpt_selection_version_id,
  transformer_version_id,
  country_code,
  country_name,
  region_name,
  reg_rpt_type,
  coalesce(rpt_config_active_flag, false) as config_active_flag,
  is_partial_report,
  db_lookup_enabled,
  mapping_service_name,
  modified_timestamp
from pharos.report_group_config
where 1 = 1
