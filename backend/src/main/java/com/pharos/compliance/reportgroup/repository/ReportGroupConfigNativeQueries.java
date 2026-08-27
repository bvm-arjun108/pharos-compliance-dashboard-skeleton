package com.pharos.compliance.reportgroup.repository;

final class ReportGroupConfigNativeQueries {

  // A report group's ACTIVE/INACTIVE status reflects every configuration it has ever had, not
  // just its most recent one: group_active_flag is TRUE only when every row for that
  // rpt_grp_id has rpt_config_active_flag = TRUE. One inactive version — past or present — is
  // enough to mark the whole group INACTIVE, even after it's been reactivated. The row shown
  // per group (name, country, mapping, etc.) still comes from the single latest version.
  private static final String FILTERED_LATEST_CONFIGS =
      """
      WITH ranked_configs AS (
          SELECT
              config.*,
              ROW_NUMBER() OVER (
                  PARTITION BY config.rpt_grp_id
                  ORDER BY
                      config.modified_timestamp DESC NULLS LAST,
                      config.created_timestamp DESC NULLS LAST,
                      config.rpt_selection_version_id DESC,
                      config.transformer_version_id DESC) AS config_rank,
              BOOL_AND(COALESCE(config.rpt_config_active_flag, FALSE)) OVER (
                  PARTITION BY config.rpt_grp_id) AS group_active_flag
          FROM pharos.report_group_config config
      ), latest_configs AS (
          SELECT *
          FROM ranked_configs
          WHERE config_rank = 1
      ), filtered_configs AS (
          SELECT *
          FROM latest_configs
          WHERE (:country = 'ALL' OR UPPER(BTRIM(country_code)) = :country)
            AND (:status = 'ALL'
                 OR (:status = 'ACTIVE' AND group_active_flag IS TRUE)
                 OR (:status = 'INACTIVE' AND group_active_flag IS NOT TRUE))
            AND (:reportType = 'ALL' OR LOWER(BTRIM(reg_rpt_type)) = LOWER(:reportType))
            AND (:reportGroupId IS NULL OR rpt_grp_id = :reportGroupId)
      )
      """;

  static final String SUMMARY =
      FILTERED_LATEST_CONFIGS
          + """
          SELECT
              COUNT(*) AS "totalConfigurations",
              COUNT(*) FILTER (WHERE group_active_flag IS TRUE) AS "activeConfigurations",
              COUNT(DISTINCT UPPER(BTRIM(country_code)))
                  FILTER (WHERE country_code IS NOT NULL AND BTRIM(country_code) <> '')
                  AS "representedCountries",
              COUNT(*) FILTER (WHERE LOWER(BTRIM(reg_rpt_type)) = 'objective')
                  AS "objectiveConfigurations"
          FROM filtered_configs
          """;

  static final String LIST =
      FILTERED_LATEST_CONFIGS
          + """
          SELECT
              rpt_grp_id AS "reportGroupId",
              rpt_grp_name AS "reportGroupName",
              rpt_selection_version_id AS "reportSelectionVersionId",
              transformer_version_id AS "transformerVersionId",
              UPPER(BTRIM(country_code)) AS "countryCode",
              COALESCE(NULLIF(BTRIM(country_name), ''), UPPER(BTRIM(country_code)))
                  AS "countryName",
              region_name AS "regionName",
              reg_rpt_type AS "reportType",
              COALESCE(group_active_flag, FALSE) AS "active",
              COALESCE(is_partial_report, FALSE) AS "partialReport",
              COALESCE(db_lookup_enabled, FALSE) AS "databaseLookupEnabled",
              mapping_service_name AS "mappingServiceName",
              modified_timestamp AS "modifiedAt"
          FROM filtered_configs
          ORDER BY
              CASE WHEN group_active_flag IS TRUE THEN 0 ELSE 1 END,
              "countryName" NULLS LAST,
              "reportGroupName" NULLS LAST,
              "reportGroupId"
          """;

  static final String DETAILS =
      """
      SELECT
          rpt_grp_id AS "reportGroupId",
          rpt_grp_name AS "reportGroupName",
          bizgrp_name AS "businessGroupName",
          UPPER(BTRIM(country_code)) AS "countryCode",
          COALESCE(NULLIF(BTRIM(country_name), ''), UPPER(BTRIM(country_code)))
              AS "countryName",
          three_letter_country_code AS "threeLetterCountryCode",
          region_code AS "regionCode",
          region_name AS "regionName",
          report_currency AS "reportCurrency",
          reg_rpt_type AS "reportType",
          COALESCE(
              (SELECT BOOL_AND(COALESCE(sibling.rpt_config_active_flag, FALSE))
               FROM pharos.report_group_config sibling
               WHERE sibling.rpt_grp_id = config.rpt_grp_id),
              FALSE) AS "active",
          rpt_selection_version_id AS "reportSelectionVersionId",
          transformer_version_id AS "transformerVersionId",
          created_timestamp AS "createdAt",
          modified_timestamp AS "modifiedAt",
          COALESCE(db_lookup_enabled, FALSE) AS "databaseLookupEnabled",
          COALESCE(is_blank_report, FALSE) AS "blankReport",
          COALESCE(is_non_transactional_report, FALSE) AS "nonTransactionalReport",
          COALESCE(is_partial_report, FALSE) AS "partialReport",
          rpt_period AS "reportPeriod",
          additional_data AS "additionalData",
          mapping_project_key AS "mappingProjectKey",
          mapping_service_name AS "mappingServiceName",
          ack_prf_docsubtype AS "acknowledgementDocumentSubtype",
          output_file_docsubtype AS "outputFileDocumentSubtype",
          submission_prf_docsubtype AS "submissionDocumentSubtype",
          transformer_config::text AS "transformerConfig",
          inbound_rule_id AS "inboundRuleId",
          outbound_rule_id AS "outboundRuleId",
          rpt_selection AS "reportSelection",
          reg_reportable_activity_columns AS "reportableActivityColumns",
          rule_hit_columns AS "ruleHitColumns",
          exclusion_strategy AS "exclusionStrategy",
          exclusion_reason AS "exclusionReason",
          column_to_compare AS "columnToCompare",
          manipulation_strategy_metadata::text AS "manipulationStrategyMetadata",
          reconciliation_strategy_metadata::text AS "reconciliationStrategyMetadata"
      FROM pharos.report_group_config config
      WHERE rpt_grp_id = :reportGroupId
        AND rpt_selection_version_id = :reportSelectionVersionId
        AND transformer_version_id = :transformerVersionId
      """;

  static final String REPORT_TYPES =
      """
      WITH ranked_configs AS (
          SELECT
              reg_rpt_type,
              ROW_NUMBER() OVER (
                  PARTITION BY rpt_grp_id
                  ORDER BY
                      modified_timestamp DESC NULLS LAST,
                      created_timestamp DESC NULLS LAST,
                      rpt_selection_version_id DESC,
                      transformer_version_id DESC) AS config_rank
          FROM pharos.report_group_config
      )
      SELECT DISTINCT BTRIM(reg_rpt_type) AS "reportType"
      FROM ranked_configs
      WHERE config_rank = 1
        AND reg_rpt_type IS NOT NULL
        AND BTRIM(reg_rpt_type) <> ''
      ORDER BY "reportType"
      """;

  private ReportGroupConfigNativeQueries() {}
}
