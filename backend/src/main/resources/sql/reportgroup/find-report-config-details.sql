-- rpt_grp_id/rpt_selection_version_id/transformer_version_id is this table's primary key, so this
-- always matches at most one row.
select
  rpt_grp_id as "reportGroupId",
  rpt_grp_name as "reportGroupName",
  bizgrp_name as "businessGroupName",
  upper(trim(country_code)) as "countryCode",
  coalesce(nullif(trim(country_name), ''), upper(trim(country_code))) as "countryName",
  three_letter_country_code as "threeLetterCountryCode",
  region_code as "regionCode",
  region_name as "regionName",
  report_currency as "reportCurrency",
  reg_rpt_type as "reportType",
  coalesce(rpt_config_active_flag, false) as "active",
  rpt_selection_version_id as "reportSelectionVersionId",
  transformer_version_id as "transformerVersionId",
  created_timestamp as "createdAt",
  modified_timestamp as "modifiedAt",
  coalesce(db_lookup_enabled, false) as "databaseLookupEnabled",
  coalesce(is_blank_report, false) as "blankReport",
  coalesce(is_non_transactional_report, false) as "nonTransactionalReport",
  coalesce(is_partial_report, false) as "partialReport",
  rpt_period as "reportPeriod",
  additional_data as "additionalData",
  mapping_project_key as "mappingProjectKey",
  mapping_service_name as "mappingServiceName",
  ack_prf_docsubtype as "acknowledgementDocumentSubtype",
  output_file_docsubtype as "outputFileDocumentSubtype",
  submission_prf_docsubtype as "submissionDocumentSubtype",
  transformer_config::text as "transformerConfig",
  inbound_rule_id as "inboundRuleId",
  outbound_rule_id as "outboundRuleId",
  rpt_selection as "reportSelection",
  reg_reportable_activity_columns as "reportableActivityColumns",
  rule_hit_columns as "ruleHitColumns",
  exclusion_strategy as "exclusionStrategy",
  exclusion_reason as "exclusionReason",
  column_to_compare as "columnToCompare",
  manipulation_strategy_metadata::text as "manipulationStrategyMetadata",
  reconciliation_strategy_metadata::text as "reconciliationStrategyMetadata"
from pharos.report_group_config
where rpt_grp_id = :reportGroupId
  and rpt_selection_version_id = :reportSelectionVersionId
  and transformer_version_id = :transformerVersionId
