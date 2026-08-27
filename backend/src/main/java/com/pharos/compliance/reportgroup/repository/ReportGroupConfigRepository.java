package com.pharos.compliance.reportgroup.repository;

import com.pharos.compliance.reportgroup.entity.ReportGroupConfigEntity;
import com.pharos.compliance.reportgroup.entity.ReportGroupConfigId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface ReportGroupConfigRepository
    extends Repository<ReportGroupConfigEntity, ReportGroupConfigId> {

  @Query(
      value =
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
                          config.transformer_version_id DESC) AS config_rank
              FROM pharos.report_group_config config
          )
          SELECT
              UPPER(BTRIM(country_code)) AS "countryCode",
              COALESCE(
                  NULLIF(BTRIM(country_name), ''),
                  UPPER(BTRIM(country_code))) AS "countryName",
              rpt_grp_id AS "reportGroupId"
          FROM ranked_configs
          WHERE config_rank = 1
            AND country_code IS NOT NULL
            AND BTRIM(country_code) <> ''
          ORDER BY "countryName", "countryCode", "reportGroupId"
          """,
      nativeQuery = true)
  List<CountryMappingProjection> findCountryMappings();

  @Query(value = ReportGroupConfigNativeQueries.REPORT_TYPES, nativeQuery = true)
  List<ReportTypeProjection> findReportTypes();

  @Query(value = ReportGroupConfigNativeQueries.SUMMARY, nativeQuery = true)
  ReportConfigSummaryProjection getSummary(
      @Param("country") String country,
      @Param("status") String status,
      @Param("reportType") String reportType,
      @Param("reportGroupId") Integer reportGroupId);

  @Query(value = ReportGroupConfigNativeQueries.LIST, nativeQuery = true)
  List<ReportConfigListProjection> findReportConfigs(
      @Param("country") String country,
      @Param("status") String status,
      @Param("reportType") String reportType,
      @Param("reportGroupId") Integer reportGroupId);

  @Query(value = ReportGroupConfigNativeQueries.DETAILS, nativeQuery = true)
  Optional<ReportConfigDetailsProjection> findReportConfigDetails(
      @Param("reportGroupId") int reportGroupId,
      @Param("reportSelectionVersionId") int reportSelectionVersionId,
      @Param("transformerVersionId") String transformerVersionId);

  interface CountryMappingProjection {
    String getCountryCode();

    String getCountryName();

    int getReportGroupId();
  }

  interface ReportTypeProjection {
    String getReportType();
  }

  interface ReportConfigSummaryProjection {
    long getTotalConfigurations();

    long getActiveConfigurations();

    long getRepresentedCountries();

    long getObjectiveConfigurations();
  }

  interface ReportConfigListProjection {
    int getReportGroupId();

    String getReportGroupName();

    int getReportSelectionVersionId();

    String getTransformerVersionId();

    String getCountryCode();

    String getCountryName();

    String getRegionName();

    String getReportType();

    boolean getActive();

    boolean getPartialReport();

    boolean getDatabaseLookupEnabled();

    String getMappingServiceName();

    Instant getModifiedAt();
  }

  interface ReportConfigDetailsProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBusinessGroupName();

    String getCountryCode();

    String getCountryName();

    String getThreeLetterCountryCode();

    String getRegionCode();

    String getRegionName();

    String getReportCurrency();

    String getReportType();

    boolean getActive();

    int getReportSelectionVersionId();

    String getTransformerVersionId();

    Instant getCreatedAt();

    Instant getModifiedAt();

    boolean getDatabaseLookupEnabled();

    boolean getBlankReport();

    boolean getNonTransactionalReport();

    boolean getPartialReport();

    Integer getReportPeriod();

    String getAdditionalData();

    String getMappingProjectKey();

    String getMappingServiceName();

    String getAcknowledgementDocumentSubtype();

    String getOutputFileDocumentSubtype();

    String getSubmissionDocumentSubtype();

    String getTransformerConfig();

    String getInboundRuleId();

    String getOutboundRuleId();

    String getReportSelection();

    String getReportableActivityColumns();

    String getRuleHitColumns();

    String getExclusionStrategy();

    String getExclusionReason();

    String getColumnToCompare();

    String getManipulationStrategyMetadata();

    String getReconciliationStrategyMetadata();
  }
}
