package com.pharos.compliance.reportgroup.repository;

import static com.pharos.compliance.jooq.tables.ReportGroupConfig.REPORT_GROUP_CONFIG;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every query here reads the same table, {@code pharos.report_group_config}, which stores every
 * version of every report group's configuration that has ever existed -- "the current state" is
 * always "the single latest version per report group", computed the same way everywhere: rank
 * rows by {@code rpt_grp_id} and keep rank 1, ordered newest-modified first (falling back to
 * newest-created, then highest version numbers, for rows with tied or null timestamps).
 */
@Repository
@Transactional(readOnly = true)
public class ReportGroupConfigRepository {

  private static final com.pharos.compliance.jooq.tables.ReportGroupConfig CONFIG =
      REPORT_GROUP_CONFIG;

  private final DSLContext dsl;

  public ReportGroupConfigRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The "latest version per report group" ranking shared by every query in this class:
   * {@code ROW_NUMBER() OVER (PARTITION BY rpt_grp_id ORDER BY modified_timestamp DESC NULLS
   * LAST, created_timestamp DESC NULLS LAST, rpt_selection_version_id DESC,
   * transformer_version_id DESC)}.
   */
  private static Field<Integer> latestConfigRank() {
    return DSL.rowNumber()
        .over(
            DSL.partitionBy(CONFIG.RPT_GRP_ID)
                .orderBy(
                    CONFIG.MODIFIED_TIMESTAMP.desc().nullsLast(),
                    CONFIG.CREATED_TIMESTAMP.desc().nullsLast(),
                    CONFIG.RPT_SELECTION_VERSION_ID.desc(),
                    CONFIG.TRANSFORMER_VERSION_ID.desc()))
        .as("config_rank");
  }

  public List<CountryMappingProjection> findCountryMappings() {
    Table<Record> rankedConfigs =
        dsl.select(CONFIG.asterisk(), latestConfigRank())
            .from(CONFIG)
            .asTable("ranked_configs");

    Field<String> countryCode = rankedConfigs.field(CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> countryName = rankedConfigs.field(CONFIG.COUNTRY_NAME.getName(), String.class);
    Field<Integer> reportGroupId =
        rankedConfigs.field(CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<Integer> configRank = rankedConfigs.field("config_rank", Integer.class);

    var countryCodeOut = DSL.upper(DSL.trim(countryCode)).as("countryCode");
    var countryNameOut =
        DSL.coalesce(DSL.nullif(DSL.trim(countryName), DSL.inline("")), DSL.upper(DSL.trim(countryCode)))
            .as("countryName");
    var reportGroupIdOut = reportGroupId.as("reportGroupId");

    return dsl.select(countryCodeOut, countryNameOut, reportGroupIdOut)
        .from(rankedConfigs)
        .where(configRank.eq(1))
        .and(countryCode.isNotNull())
        .and(DSL.trim(countryCode).ne(""))
        .orderBy(countryNameOut, countryCodeOut, reportGroupIdOut)
        .fetch(
            r ->
                new CountryMappingProjectionImpl(
                    r.get(countryCodeOut), r.get(countryNameOut), r.get(reportGroupIdOut)));
  }

  public List<ReportTypeProjection> findReportTypes() {
    var rankedConfigs =
        dsl.select(CONFIG.REG_RPT_TYPE, latestConfigRank()).from(CONFIG).asTable("ranked_configs");

    Field<String> reportType = rankedConfigs.field(CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Integer> configRank = rankedConfigs.field("config_rank", Integer.class);

    var reportTypeOut = DSL.trim(reportType).as("reportType");

    return dsl.selectDistinct(reportTypeOut)
        .from(rankedConfigs)
        .where(configRank.eq(1))
        .and(reportType.isNotNull())
        .and(DSL.trim(reportType).ne(""))
        .orderBy(reportTypeOut)
        .fetch(r -> new ReportTypeProjectionImpl(r.get(reportTypeOut)));
  }

  /**
   * Shared by {@link #getSummary} and {@link #findReportConfigs}: the latest configuration per
   * report group, with the group's overall active/inactive status computed as {@code
   * BOOL_AND(...)} across every version that group has ever had (not just the latest one), then
   * narrowed by the caller's country/status/reportType/reportGroupId filters.
   */
  private Table<Record> filteredLatestConfigs(
      String country, String status, String reportType, Integer reportGroupId) {
    Field<Boolean> groupActiveFlag =
        DSL.boolAnd(DSL.coalesce(CONFIG.RPT_CONFIG_ACTIVE_FLAG, DSL.inline(false)))
            .over(DSL.partitionBy(CONFIG.RPT_GRP_ID))
            .as("group_active_flag");

    Table<Record> rankedConfigs =
        dsl.select(CONFIG.asterisk(), latestConfigRank(), groupActiveFlag)
            .from(CONFIG)
            .asTable("ranked_configs");

    Field<String> countryCode = rankedConfigs.field(CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> reportTypeField =
        rankedConfigs.field(CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Integer> reportGroupIdField =
        rankedConfigs.field(CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<Integer> configRank = rankedConfigs.field("config_rank", Integer.class);
    Field<Boolean> groupActiveFlagField =
        rankedConfigs.field("group_active_flag", Boolean.class);

    return dsl.select(rankedConfigs.fields())
        .from(rankedConfigs)
        .where(configRank.eq(1))
        .and(
            "ALL".equals(country)
                ? DSL.trueCondition()
                : DSL.upper(DSL.trim(countryCode)).eq(country))
        .and(
            switch (status) {
              case "ALL" -> DSL.trueCondition();
              case "ACTIVE" -> groupActiveFlagField.isTrue();
              // Field<Boolean> has no isNotTrue(); DSL.not(x.isTrue()) is the exact equivalent of
              // "IS NOT TRUE" for all three truth values, including NULL.
              case "INACTIVE" -> DSL.not(groupActiveFlagField.isTrue());
              default -> DSL.falseCondition();
            })
        .and(
            "ALL".equals(reportType)
                ? DSL.trueCondition()
                : DSL.lower(DSL.trim(reportTypeField)).eq(reportType.toLowerCase(java.util.Locale.ROOT)))
        .and(reportGroupId == null ? DSL.trueCondition() : reportGroupIdField.eq(reportGroupId))
        .asTable("filtered_configs");
  }

  public ReportConfigSummaryProjection getSummary(
      String country, String status, String reportType, Integer reportGroupId) {
    Table<Record> filteredConfigs = filteredLatestConfigs(country, status, reportType, reportGroupId);

    Field<Boolean> groupActiveFlag = filteredConfigs.field("group_active_flag", Boolean.class);
    Field<String> countryCode = filteredConfigs.field(CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> reportTypeField =
        filteredConfigs.field(CONFIG.REG_RPT_TYPE.getName(), String.class);

    return dsl.select(
            DSL.count().as("totalConfigurations"),
            DSL.count().filterWhere(groupActiveFlag.isTrue()).as("activeConfigurations"),
            DSL.countDistinct(DSL.upper(DSL.trim(countryCode)))
                .filterWhere(countryCode.isNotNull().and(DSL.trim(countryCode).ne("")))
                .as("representedCountries"),
            DSL.count()
                .filterWhere(DSL.lower(DSL.trim(reportTypeField)).eq("objective"))
                .as("objectiveConfigurations"))
        .from(filteredConfigs)
        .fetchOne(
            r ->
                new ReportConfigSummaryProjectionImpl(
                    r.get("totalConfigurations", long.class),
                    r.get("activeConfigurations", long.class),
                    r.get("representedCountries", long.class),
                    r.get("objectiveConfigurations", long.class)));
  }

  public List<ReportConfigListProjection> findReportConfigs(
      String country, String status, String reportType, Integer reportGroupId) {
    Table<Record> filteredConfigs = filteredLatestConfigs(country, status, reportType, reportGroupId);

    Field<Integer> reportGroupIdField =
        filteredConfigs.field(CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<String> reportGroupName =
        filteredConfigs.field(CONFIG.RPT_GRP_NAME.getName(), String.class);
    Field<Integer> reportSelectionVersionId =
        filteredConfigs.field(CONFIG.RPT_SELECTION_VERSION_ID.getName(), Integer.class);
    Field<String> transformerVersionId =
        filteredConfigs.field(CONFIG.TRANSFORMER_VERSION_ID.getName(), String.class);
    Field<String> countryCode = filteredConfigs.field(CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> countryName = filteredConfigs.field(CONFIG.COUNTRY_NAME.getName(), String.class);
    Field<String> regionName = filteredConfigs.field(CONFIG.REGION_NAME.getName(), String.class);
    Field<String> reportTypeField =
        filteredConfigs.field(CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Boolean> groupActiveFlag = filteredConfigs.field("group_active_flag", Boolean.class);
    Field<Boolean> isPartialReport =
        filteredConfigs.field(CONFIG.IS_PARTIAL_REPORT.getName(), Boolean.class);
    Field<Boolean> dbLookupEnabled =
        filteredConfigs.field(CONFIG.DB_LOOKUP_ENABLED.getName(), Boolean.class);
    Field<String> mappingServiceName =
        filteredConfigs.field(CONFIG.MAPPING_SERVICE_NAME.getName(), String.class);
    Field<java.time.OffsetDateTime> modifiedAt =
        filteredConfigs.field(CONFIG.MODIFIED_TIMESTAMP.getName(), java.time.OffsetDateTime.class);

    var countryCodeOut = DSL.upper(DSL.trim(countryCode)).as("countryCode");
    var countryNameOut =
        DSL.coalesce(DSL.nullif(DSL.trim(countryName), DSL.inline("")), DSL.upper(DSL.trim(countryCode)))
            .as("countryName");
    var reportGroupNameOut = reportGroupName.as("reportGroupName");

    SortField<?> activeFirst = DSL.when(groupActiveFlag.isTrue(), 0).otherwise(1).asc();

    return dsl.select(
            reportGroupIdField.as("reportGroupId"),
            reportGroupNameOut,
            reportSelectionVersionId.as("reportSelectionVersionId"),
            transformerVersionId.as("transformerVersionId"),
            countryCodeOut,
            countryNameOut,
            regionName.as("regionName"),
            reportTypeField.as("reportType"),
            DSL.coalesce(groupActiveFlag, DSL.inline(false)).as("active"),
            DSL.coalesce(isPartialReport, DSL.inline(false)).as("partialReport"),
            DSL.coalesce(dbLookupEnabled, DSL.inline(false)).as("databaseLookupEnabled"),
            mappingServiceName.as("mappingServiceName"),
            modifiedAt.as("modifiedAt"))
        .from(filteredConfigs)
        .orderBy(activeFirst, countryNameOut.nullsLast(), reportGroupNameOut.nullsLast(), reportGroupIdField)
        .fetch(
            r ->
                new ReportConfigListProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("reportSelectionVersionId", int.class),
                    r.get("transformerVersionId", String.class),
                    r.get("countryCode", String.class),
                    r.get("countryName", String.class),
                    r.get("regionName", String.class),
                    r.get("reportType", String.class),
                    r.get("active", boolean.class),
                    r.get("partialReport", boolean.class),
                    r.get("databaseLookupEnabled", boolean.class),
                    r.get("mappingServiceName", String.class),
                    r.get("modifiedAt", java.time.OffsetDateTime.class).toInstant()));
  }

  public Optional<ReportConfigDetailsProjection> findReportConfigDetails(
      int reportGroupId, int reportSelectionVersionId, String transformerVersionId) {
    com.pharos.compliance.jooq.tables.ReportGroupConfig sibling = CONFIG.as("sibling");

    Field<Boolean> active =
        DSL.coalesce(
                DSL.field(
                    dsl.select(
                            DSL.boolAnd(
                                DSL.coalesce(sibling.RPT_CONFIG_ACTIVE_FLAG, DSL.inline(false))))
                        .from(sibling)
                        .where(sibling.RPT_GRP_ID.eq(CONFIG.RPT_GRP_ID))),
                DSL.inline(false))
            .as("active");

    var countryCodeOut = DSL.upper(DSL.trim(CONFIG.COUNTRY_CODE)).as("countryCode");
    var countryNameOut =
        DSL.coalesce(
                DSL.nullif(DSL.trim(CONFIG.COUNTRY_NAME), DSL.inline("")),
                DSL.upper(DSL.trim(CONFIG.COUNTRY_CODE)))
            .as("countryName");

    return dsl.select(
            CONFIG.RPT_GRP_ID.as("reportGroupId"),
            CONFIG.RPT_GRP_NAME.as("reportGroupName"),
            CONFIG.BIZGRP_NAME.as("businessGroupName"),
            countryCodeOut,
            countryNameOut,
            CONFIG.THREE_LETTER_COUNTRY_CODE.as("threeLetterCountryCode"),
            CONFIG.REGION_CODE.as("regionCode"),
            CONFIG.REGION_NAME.as("regionName"),
            CONFIG.REPORT_CURRENCY.as("reportCurrency"),
            CONFIG.REG_RPT_TYPE.as("reportType"),
            active,
            CONFIG.RPT_SELECTION_VERSION_ID.as("reportSelectionVersionId"),
            CONFIG.TRANSFORMER_VERSION_ID.as("transformerVersionId"),
            CONFIG.CREATED_TIMESTAMP.as("createdAt"),
            CONFIG.MODIFIED_TIMESTAMP.as("modifiedAt"),
            DSL.coalesce(CONFIG.DB_LOOKUP_ENABLED, DSL.inline(false)).as("databaseLookupEnabled"),
            DSL.coalesce(CONFIG.IS_BLANK_REPORT, DSL.inline(false)).as("blankReport"),
            DSL.coalesce(CONFIG.IS_NON_TRANSACTIONAL_REPORT, DSL.inline(false))
                .as("nonTransactionalReport"),
            DSL.coalesce(CONFIG.IS_PARTIAL_REPORT, DSL.inline(false)).as("partialReport"),
            CONFIG.RPT_PERIOD.as("reportPeriod"),
            CONFIG.ADDITIONAL_DATA.as("additionalData"),
            CONFIG.MAPPING_PROJECT_KEY.as("mappingProjectKey"),
            CONFIG.MAPPING_SERVICE_NAME.as("mappingServiceName"),
            CONFIG.ACK_PRF_DOCSUBTYPE.as("acknowledgementDocumentSubtype"),
            CONFIG.OUTPUT_FILE_DOCSUBTYPE.as("outputFileDocumentSubtype"),
            CONFIG.SUBMISSION_PRF_DOCSUBTYPE.as("submissionDocumentSubtype"),
            CONFIG.TRANSFORMER_CONFIG.cast(String.class).as("transformerConfig"),
            CONFIG.INBOUND_RULE_ID.as("inboundRuleId"),
            CONFIG.OUTBOUND_RULE_ID.as("outboundRuleId"),
            CONFIG.RPT_SELECTION.as("reportSelection"),
            CONFIG.REG_REPORTABLE_ACTIVITY_COLUMNS.as("reportableActivityColumns"),
            CONFIG.RULE_HIT_COLUMNS.as("ruleHitColumns"),
            CONFIG.EXCLUSION_STRATEGY.as("exclusionStrategy"),
            CONFIG.EXCLUSION_REASON.as("exclusionReason"),
            CONFIG.COLUMN_TO_COMPARE.as("columnToCompare"),
            CONFIG.MANIPULATION_STRATEGY_METADATA.cast(String.class).as("manipulationStrategyMetadata"),
            CONFIG.RECONCILIATION_STRATEGY_METADATA
                .cast(String.class)
                .as("reconciliationStrategyMetadata"))
        .from(CONFIG)
        .where(CONFIG.RPT_GRP_ID.eq(reportGroupId))
        .and(CONFIG.RPT_SELECTION_VERSION_ID.eq(reportSelectionVersionId))
        .and(CONFIG.TRANSFORMER_VERSION_ID.eq(transformerVersionId))
        .fetchOptional(
            r ->
                new ReportConfigDetailsProjectionImpl(
                    r.get("reportGroupId", int.class),
                    r.get("reportGroupName", String.class),
                    r.get("businessGroupName", String.class),
                    r.get("countryCode", String.class),
                    r.get("countryName", String.class),
                    r.get("threeLetterCountryCode", String.class),
                    r.get("regionCode", String.class),
                    r.get("regionName", String.class),
                    r.get("reportCurrency", String.class),
                    r.get("reportType", String.class),
                    r.get("active", boolean.class),
                    r.get("reportSelectionVersionId", int.class),
                    r.get("transformerVersionId", String.class),
                    r.get("createdAt", java.time.OffsetDateTime.class).toInstant(),
                    r.get("modifiedAt", java.time.OffsetDateTime.class).toInstant(),
                    r.get("databaseLookupEnabled", boolean.class),
                    r.get("blankReport", boolean.class),
                    r.get("nonTransactionalReport", boolean.class),
                    r.get("partialReport", boolean.class),
                    r.get("reportPeriod", Integer.class),
                    r.get("additionalData", String.class),
                    r.get("mappingProjectKey", String.class),
                    r.get("mappingServiceName", String.class),
                    r.get("acknowledgementDocumentSubtype", String.class),
                    r.get("outputFileDocumentSubtype", String.class),
                    r.get("submissionDocumentSubtype", String.class),
                    r.get("transformerConfig", String.class),
                    r.get("inboundRuleId", String.class),
                    r.get("outboundRuleId", String.class),
                    r.get("reportSelection", String.class),
                    r.get("reportableActivityColumns", String.class),
                    r.get("ruleHitColumns", String.class),
                    r.get("exclusionStrategy", String.class),
                    r.get("exclusionReason", String.class),
                    r.get("columnToCompare", String.class),
                    r.get("manipulationStrategyMetadata", String.class),
                    r.get("reconciliationStrategyMetadata", String.class)));
  }

  public interface CountryMappingProjection {
    String getCountryCode();

    String getCountryName();

    int getReportGroupId();
  }

  private record CountryMappingProjectionImpl(String countryCode, String countryName, int reportGroupId)
      implements CountryMappingProjection {
    @Override
    public String getCountryCode() {
      return countryCode;
    }

    @Override
    public String getCountryName() {
      return countryName;
    }

    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }
  }

  public interface ReportTypeProjection {
    String getReportType();
  }

  private record ReportTypeProjectionImpl(String reportType) implements ReportTypeProjection {
    @Override
    public String getReportType() {
      return reportType;
    }
  }

  public interface ReportConfigSummaryProjection {
    long getTotalConfigurations();

    long getActiveConfigurations();

    long getRepresentedCountries();

    long getObjectiveConfigurations();
  }

  private record ReportConfigSummaryProjectionImpl(
      long totalConfigurations, long activeConfigurations, long representedCountries, long objectiveConfigurations)
      implements ReportConfigSummaryProjection {
    @Override
    public long getTotalConfigurations() {
      return totalConfigurations;
    }

    @Override
    public long getActiveConfigurations() {
      return activeConfigurations;
    }

    @Override
    public long getRepresentedCountries() {
      return representedCountries;
    }

    @Override
    public long getObjectiveConfigurations() {
      return objectiveConfigurations;
    }
  }

  public interface ReportConfigListProjection {
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

  private record ReportConfigListProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      int reportSelectionVersionId,
      String transformerVersionId,
      String countryCode,
      String countryName,
      String regionName,
      String reportType,
      boolean active,
      boolean partialReport,
      boolean databaseLookupEnabled,
      String mappingServiceName,
      Instant modifiedAt)
      implements ReportConfigListProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public int getReportSelectionVersionId() {
      return reportSelectionVersionId;
    }

    @Override
    public String getTransformerVersionId() {
      return transformerVersionId;
    }

    @Override
    public String getCountryCode() {
      return countryCode;
    }

    @Override
    public String getCountryName() {
      return countryName;
    }

    @Override
    public String getRegionName() {
      return regionName;
    }

    @Override
    public String getReportType() {
      return reportType;
    }

    @Override
    public boolean getActive() {
      return active;
    }

    @Override
    public boolean getPartialReport() {
      return partialReport;
    }

    @Override
    public boolean getDatabaseLookupEnabled() {
      return databaseLookupEnabled;
    }

    @Override
    public String getMappingServiceName() {
      return mappingServiceName;
    }

    @Override
    public Instant getModifiedAt() {
      return modifiedAt;
    }
  }

  public interface ReportConfigDetailsProjection {
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

  private record ReportConfigDetailsProjectionImpl(
      int reportGroupId,
      String reportGroupName,
      String businessGroupName,
      String countryCode,
      String countryName,
      String threeLetterCountryCode,
      String regionCode,
      String regionName,
      String reportCurrency,
      String reportType,
      boolean active,
      int reportSelectionVersionId,
      String transformerVersionId,
      Instant createdAt,
      Instant modifiedAt,
      boolean databaseLookupEnabled,
      boolean blankReport,
      boolean nonTransactionalReport,
      boolean partialReport,
      Integer reportPeriod,
      String additionalData,
      String mappingProjectKey,
      String mappingServiceName,
      String acknowledgementDocumentSubtype,
      String outputFileDocumentSubtype,
      String submissionDocumentSubtype,
      String transformerConfig,
      String inboundRuleId,
      String outboundRuleId,
      String reportSelection,
      String reportableActivityColumns,
      String ruleHitColumns,
      String exclusionStrategy,
      String exclusionReason,
      String columnToCompare,
      String manipulationStrategyMetadata,
      String reconciliationStrategyMetadata)
      implements ReportConfigDetailsProjection {
    @Override
    public int getReportGroupId() {
      return reportGroupId;
    }

    @Override
    public String getReportGroupName() {
      return reportGroupName;
    }

    @Override
    public String getBusinessGroupName() {
      return businessGroupName;
    }

    @Override
    public String getCountryCode() {
      return countryCode;
    }

    @Override
    public String getCountryName() {
      return countryName;
    }

    @Override
    public String getThreeLetterCountryCode() {
      return threeLetterCountryCode;
    }

    @Override
    public String getRegionCode() {
      return regionCode;
    }

    @Override
    public String getRegionName() {
      return regionName;
    }

    @Override
    public String getReportCurrency() {
      return reportCurrency;
    }

    @Override
    public String getReportType() {
      return reportType;
    }

    @Override
    public boolean getActive() {
      return active;
    }

    @Override
    public int getReportSelectionVersionId() {
      return reportSelectionVersionId;
    }

    @Override
    public String getTransformerVersionId() {
      return transformerVersionId;
    }

    @Override
    public Instant getCreatedAt() {
      return createdAt;
    }

    @Override
    public Instant getModifiedAt() {
      return modifiedAt;
    }

    @Override
    public boolean getDatabaseLookupEnabled() {
      return databaseLookupEnabled;
    }

    @Override
    public boolean getBlankReport() {
      return blankReport;
    }

    @Override
    public boolean getNonTransactionalReport() {
      return nonTransactionalReport;
    }

    @Override
    public boolean getPartialReport() {
      return partialReport;
    }

    @Override
    public Integer getReportPeriod() {
      return reportPeriod;
    }

    @Override
    public String getAdditionalData() {
      return additionalData;
    }

    @Override
    public String getMappingProjectKey() {
      return mappingProjectKey;
    }

    @Override
    public String getMappingServiceName() {
      return mappingServiceName;
    }

    @Override
    public String getAcknowledgementDocumentSubtype() {
      return acknowledgementDocumentSubtype;
    }

    @Override
    public String getOutputFileDocumentSubtype() {
      return outputFileDocumentSubtype;
    }

    @Override
    public String getSubmissionDocumentSubtype() {
      return submissionDocumentSubtype;
    }

    @Override
    public String getTransformerConfig() {
      return transformerConfig;
    }

    @Override
    public String getInboundRuleId() {
      return inboundRuleId;
    }

    @Override
    public String getOutboundRuleId() {
      return outboundRuleId;
    }

    @Override
    public String getReportSelection() {
      return reportSelection;
    }

    @Override
    public String getReportableActivityColumns() {
      return reportableActivityColumns;
    }

    @Override
    public String getRuleHitColumns() {
      return ruleHitColumns;
    }

    @Override
    public String getExclusionStrategy() {
      return exclusionStrategy;
    }

    @Override
    public String getExclusionReason() {
      return exclusionReason;
    }

    @Override
    public String getColumnToCompare() {
      return columnToCompare;
    }

    @Override
    public String getManipulationStrategyMetadata() {
      return manipulationStrategyMetadata;
    }

    @Override
    public String getReconciliationStrategyMetadata() {
      return reconciliationStrategyMetadata;
    }
  }
}
