select
  rpt_grp_id as "reportGroupId",
  rpt_grp_name as "reportGroupName",
  rpt_selection_version_id as "reportSelectionVersionId",
  transformer_version_id as "transformerVersionId",
  upper(trim(country_code)) as "countryCode",
  coalesce(nullif(trim(country_name), ''), upper(trim(country_code))) as "countryName",
  region_name as "regionName",
  reg_rpt_type as "reportType",
  coalesce(config_active_flag, false) as "active",
  coalesce(is_partial_report, false) as "partialReport",
  coalesce(db_lookup_enabled, false) as "databaseLookupEnabled",
  mapping_service_name as "mappingServiceName",
  modified_timestamp as "modifiedAt"
from filtered_configs
order by
  "countryName" nulls last,
  "reportGroupName" nulls last,
  "reportGroupId",
  "reportSelectionVersionId" desc,
  "transformerVersionId" desc
