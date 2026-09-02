package com.pharos.compliance.reportgroup.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.reportgroup.repository.projection.CountryMappingProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportTypeProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigDetailsProjection;
import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.common.jooq.JooqFields.requiredBoolean;
import static com.pharos.compliance.common.jooq.JooqFields.requiredInt;
import static com.pharos.compliance.common.jooq.JooqFields.requiredLong;
import static com.pharos.compliance.jooq.tables.ReportGroupConfig.REPORT_GROUP_CONFIG;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * always "the single latest version per report group", computed the same way everywhere: rank rows
 * by {@code rpt_grp_id} and keep rank 1, ordered newest-modified first (falling back to
 * newest-created, then highest version numbers, for rows with tied or null timestamps).
 */
@Repository
@Transactional(readOnly = true)
public class ReportGroupConfigRepository {
  private static final String ACTIVE_ALIAS = "active";
  private static final String CONFIG_RANK_COLUMN = "config_rank";
  private static final String COUNTRY_CODE_ALIAS = "countryCode";
  private static final String COUNTRY_NAME_ALIAS = "countryName";
  private static final String DATABASE_LOOKUP_ENABLED_ALIAS = "databaseLookupEnabled";
  private static final String GROUP_ACTIVE_FLAG_COLUMN = "group_active_flag";
  private static final String MAPPING_SERVICE_NAME_ALIAS = "mappingServiceName";
  private static final String MODIFIED_AT_ALIAS = "modifiedAt";
  private static final String PARTIAL_REPORT_ALIAS = "partialReport";
  private static final String RANKED_CONFIGS_TABLE = "ranked_configs";
  private static final String REGION_NAME_ALIAS = "regionName";
  private static final String REPORT_GROUP_ID_ALIAS = "reportGroupId";
  private static final String REPORT_GROUP_NAME_ALIAS = "reportGroupName";
  private static final String REPORT_SELECTION_VERSION_ID_ALIAS = "reportSelectionVersionId";
  private static final String REPORT_TYPE_ALIAS = "reportType";
  private static final String TRANSFORMER_VERSION_ID_ALIAS = "transformerVersionId";
  private static final com.pharos.compliance.jooq.tables.ReportGroupConfig CONFIG = REPORT_GROUP_CONFIG;
  private final DSLContext dsl;

  public ReportGroupConfigRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  /**
   * The "latest version per report group" ranking shared by every query in this class: {@code
   * ROW_NUMBER() OVER (PARTITION BY rpt_grp_id ORDER BY modified_timestamp DESC NULLS LAST,
   * created_timestamp DESC NULLS LAST, rpt_selection_version_id DESC, transformer_version_id
   * DESC)}.
   */
  private static Field<Integer> latestConfigRank() {
    return DSL
      .rowNumber()
      .over(DSL
        .partitionBy(CONFIG.RPT_GRP_ID)
        .orderBy(CONFIG.MODIFIED_TIMESTAMP.desc().nullsLast(), CONFIG.CREATED_TIMESTAMP.desc().nullsLast(),
            CONFIG.RPT_SELECTION_VERSION_ID.desc(), CONFIG.TRANSFORMER_VERSION_ID.desc()))
      .as(CONFIG_RANK_COLUMN);
  }

  @SqlQueryPurpose("Load the latest country-to-report-group mappings")
  public List<CountryMappingProjection> findCountryMappings() {
    Table<Record> rankedConfigs = dsl.select(CONFIG.asterisk(), latestConfigRank()).from(CONFIG).asTable(RANKED_CONFIGS_TABLE);

    Field<String> countryCode = requiredField(rankedConfigs, CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> countryName = requiredField(rankedConfigs, CONFIG.COUNTRY_NAME.getName(), String.class);
    Field<Integer> reportGroupId = requiredField(rankedConfigs, CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<Integer> configRank = requiredField(rankedConfigs, CONFIG_RANK_COLUMN, Integer.class);

    var countryCodeOut = DSL.upper(DSL.trim(countryCode)).as(COUNTRY_CODE_ALIAS);
    var countryNameOut =
        DSL.coalesce(DSL.nullif(DSL.trim(countryName), DSL.inline("")), DSL.upper(DSL.trim(countryCode))).as(COUNTRY_NAME_ALIAS);
    var reportGroupIdOut = reportGroupId.as(REPORT_GROUP_ID_ALIAS);

    return dsl
      .select(countryCodeOut, countryNameOut, reportGroupIdOut)
      .from(rankedConfigs)
      .where(configRank.eq(1))
      .and(countryCode.isNotNull())
      .and(DSL.trim(countryCode).ne(""))
      .orderBy(countryNameOut, countryCodeOut, reportGroupIdOut)
      .fetch(r -> new CountryMappingProjection(r.get(countryCodeOut), r.get(countryNameOut), requiredInt(r, reportGroupIdOut)));
  }

  @SqlQueryPurpose("Load the configured regulatory report types")
  public List<ReportTypeProjection> findReportTypes() {
    var rankedConfigs = dsl.select(CONFIG.REG_RPT_TYPE, latestConfigRank()).from(CONFIG).asTable(RANKED_CONFIGS_TABLE);

    Field<String> reportType = requiredField(rankedConfigs, CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Integer> configRank = requiredField(rankedConfigs, CONFIG_RANK_COLUMN, Integer.class);

    var reportTypeOut = DSL.trim(reportType).as(REPORT_TYPE_ALIAS);

    return dsl
      .selectDistinct(reportTypeOut)
      .from(rankedConfigs)
      .where(configRank.eq(1))
      .and(reportType.isNotNull())
      .and(DSL.trim(reportType).ne(""))
      .orderBy(reportTypeOut)
      .fetch(r -> new ReportTypeProjection(r.get(reportTypeOut)));
  }

  /**
   * Shared by {@link #getSummary} and {@link #findReportConfigs}: the latest configuration per
   * report group, with the group's overall active/inactive status computed as {@code BOOL_AND(...)}
   * across every version that group has ever had (not just the latest one), then narrowed by the
   * caller's country/status/reportType/reportGroupId filters.
   */
  private Table<Record> filteredLatestConfigs(String country, String status, String reportType, Integer reportGroupId) {
    Field<Boolean> groupActiveFlag = DSL
      .boolAnd(DSL.coalesce(CONFIG.RPT_CONFIG_ACTIVE_FLAG, DSL.inline(false)))
      .over(DSL.partitionBy(CONFIG.RPT_GRP_ID))
      .as(GROUP_ACTIVE_FLAG_COLUMN);

    Table<Record> rankedConfigs =
        dsl.select(CONFIG.asterisk(), latestConfigRank(), groupActiveFlag).from(CONFIG).asTable(RANKED_CONFIGS_TABLE);

    Field<String> countryCode = requiredField(rankedConfigs, CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> reportTypeField = requiredField(rankedConfigs, CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Integer> reportGroupIdField = requiredField(rankedConfigs, CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<Integer> configRank = requiredField(rankedConfigs, CONFIG_RANK_COLUMN, Integer.class);
    Field<Boolean> groupActiveFlagField = requiredField(rankedConfigs, GROUP_ACTIVE_FLAG_COLUMN, Boolean.class);

    return dsl
      .select(rankedConfigs.fields())
      .from(rankedConfigs)
      .where(configRank.eq(1))
      .and("ALL".equals(country) ? DSL.trueCondition() : DSL.upper(DSL.trim(countryCode)).eq(country))
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
          "ALL".equals(reportType) ? DSL.trueCondition() : DSL
            .lower(DSL.trim(reportTypeField))
            .eq(reportType.toLowerCase(java.util.Locale.ROOT)))
      .and(reportGroupId == null ? DSL.trueCondition() : reportGroupIdField.eq(reportGroupId))
      .asTable("filtered_configs");
  }

  @SqlQueryPurpose("Summarize report-group configurations matching the selected filters")
  public ReportConfigSummaryProjection getSummary(String country, String status, String reportType, Integer reportGroupId) {
    Table<Record> filteredConfigs = filteredLatestConfigs(country, status, reportType, reportGroupId);

    Field<Boolean> groupActiveFlag = requiredField(filteredConfigs, GROUP_ACTIVE_FLAG_COLUMN, Boolean.class);
    Field<String> countryCode = requiredField(filteredConfigs, CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> reportTypeField = requiredField(filteredConfigs, CONFIG.REG_RPT_TYPE.getName(), String.class);

    return dsl
      .select(DSL.count().as("totalConfigurations"), DSL.count().filterWhere(groupActiveFlag.isTrue()).as("activeConfigurations"),
          DSL
            .countDistinct(DSL.upper(DSL.trim(countryCode)))
            .filterWhere(countryCode.isNotNull().and(DSL.trim(countryCode).ne("")))
            .as("representedCountries"),
          DSL.count().filterWhere(DSL.lower(DSL.trim(reportTypeField)).eq("objective")).as("objectiveConfigurations"))
      .from(filteredConfigs)
      .fetchOptional(r -> new ReportConfigSummaryProjection(requiredLong(r, "totalConfigurations"), requiredLong(r, "activeConfigurations"),
          requiredLong(r, "representedCountries"), requiredLong(r, "objectiveConfigurations")))
      .orElseThrow(() -> new IllegalStateException("Report configuration summary aggregate returned no row"));
  }

  @SqlQueryPurpose("Load report-group configurations matching the selected filters")
  public List<ReportConfigListProjection> findReportConfigs(String country, String status, String reportType, Integer reportGroupId) {
    Table<Record> filteredConfigs = filteredLatestConfigs(country, status, reportType, reportGroupId);

    Field<Integer> reportGroupIdField = requiredField(filteredConfigs, CONFIG.RPT_GRP_ID.getName(), Integer.class);
    Field<String> reportGroupName = requiredField(filteredConfigs, CONFIG.RPT_GRP_NAME.getName(), String.class);
    Field<Integer> reportSelectionVersionId = requiredField(filteredConfigs, CONFIG.RPT_SELECTION_VERSION_ID.getName(), Integer.class);
    Field<String> transformerVersionId = requiredField(filteredConfigs, CONFIG.TRANSFORMER_VERSION_ID.getName(), String.class);
    Field<String> countryCode = requiredField(filteredConfigs, CONFIG.COUNTRY_CODE.getName(), String.class);
    Field<String> countryName = requiredField(filteredConfigs, CONFIG.COUNTRY_NAME.getName(), String.class);
    Field<String> regionName = requiredField(filteredConfigs, CONFIG.REGION_NAME.getName(), String.class);
    Field<String> reportTypeField = requiredField(filteredConfigs, CONFIG.REG_RPT_TYPE.getName(), String.class);
    Field<Boolean> groupActiveFlag = requiredField(filteredConfigs, GROUP_ACTIVE_FLAG_COLUMN, Boolean.class);
    Field<Boolean> isPartialReport = requiredField(filteredConfigs, CONFIG.IS_PARTIAL_REPORT.getName(), Boolean.class);
    Field<Boolean> dbLookupEnabled = requiredField(filteredConfigs, CONFIG.DB_LOOKUP_ENABLED.getName(), Boolean.class);
    Field<String> mappingServiceName = requiredField(filteredConfigs, CONFIG.MAPPING_SERVICE_NAME.getName(), String.class);
    Field<java.time.OffsetDateTime> modifiedAt =
        requiredField(filteredConfigs, CONFIG.MODIFIED_TIMESTAMP.getName(), java.time.OffsetDateTime.class);

    var countryCodeOut = DSL.upper(DSL.trim(countryCode)).as(COUNTRY_CODE_ALIAS);
    var countryNameOut =
        DSL.coalesce(DSL.nullif(DSL.trim(countryName), DSL.inline("")), DSL.upper(DSL.trim(countryCode))).as(COUNTRY_NAME_ALIAS);
    var reportGroupNameOut = reportGroupName.as(REPORT_GROUP_NAME_ALIAS);

    SortField<?> activeFirst = DSL.when(groupActiveFlag.isTrue(), 0).otherwise(1).asc();

    return dsl
      .select(reportGroupIdField.as(REPORT_GROUP_ID_ALIAS), reportGroupNameOut,
          reportSelectionVersionId.as(REPORT_SELECTION_VERSION_ID_ALIAS), transformerVersionId.as(TRANSFORMER_VERSION_ID_ALIAS),
          countryCodeOut, countryNameOut, regionName.as(REGION_NAME_ALIAS), reportTypeField.as(REPORT_TYPE_ALIAS),
          DSL.coalesce(groupActiveFlag, DSL.inline(false)).as(ACTIVE_ALIAS),
          DSL.coalesce(isPartialReport, DSL.inline(false)).as(PARTIAL_REPORT_ALIAS),
          DSL.coalesce(dbLookupEnabled, DSL.inline(false)).as(DATABASE_LOOKUP_ENABLED_ALIAS),
          mappingServiceName.as(MAPPING_SERVICE_NAME_ALIAS), modifiedAt.as(MODIFIED_AT_ALIAS))
      .from(filteredConfigs)
      .orderBy(activeFirst, countryNameOut.nullsLast(), reportGroupNameOut.nullsLast(), reportGroupIdField)
      .fetch(r -> new ReportConfigListProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS), r.get(REPORT_GROUP_NAME_ALIAS, String.class),
          requiredInt(r, REPORT_SELECTION_VERSION_ID_ALIAS), r.get(TRANSFORMER_VERSION_ID_ALIAS, String.class),
          r.get(COUNTRY_CODE_ALIAS, String.class), r.get(COUNTRY_NAME_ALIAS, String.class), r.get(REGION_NAME_ALIAS, String.class),
          r.get(REPORT_TYPE_ALIAS, String.class), requiredBoolean(r, ACTIVE_ALIAS), requiredBoolean(r, PARTIAL_REPORT_ALIAS),
          requiredBoolean(r, DATABASE_LOOKUP_ENABLED_ALIAS), r.get(MAPPING_SERVICE_NAME_ALIAS, String.class),
          toInstant(r.get(MODIFIED_AT_ALIAS, OffsetDateTime.class))));
  }

  @SqlQueryPurpose("Load one report-group configuration version and its strategy metadata")
  public Optional<ReportConfigDetailsProjection> findReportConfigDetails(int reportGroupId, int reportSelectionVersionId,
      String transformerVersionId) {
    com.pharos.compliance.jooq.tables.ReportGroupConfig sibling = CONFIG.as("sibling");

    Field<Boolean> active = DSL
      .coalesce(DSL.field(dsl
            .select(DSL.boolAnd(DSL.coalesce(sibling.RPT_CONFIG_ACTIVE_FLAG, DSL.inline(false))))
            .from(sibling)
            .where(sibling.RPT_GRP_ID.eq(CONFIG.RPT_GRP_ID))), DSL.inline(false))
      .as(ACTIVE_ALIAS);

    var countryCodeOut = DSL.upper(DSL.trim(CONFIG.COUNTRY_CODE)).as(COUNTRY_CODE_ALIAS);
    var countryNameOut = DSL
      .coalesce(DSL.nullif(DSL.trim(CONFIG.COUNTRY_NAME), DSL.inline("")), DSL.upper(DSL.trim(CONFIG.COUNTRY_CODE)))
      .as(COUNTRY_NAME_ALIAS);

    return dsl
      .select(CONFIG.RPT_GRP_ID.as(REPORT_GROUP_ID_ALIAS), CONFIG.RPT_GRP_NAME.as(REPORT_GROUP_NAME_ALIAS),
          CONFIG.BIZGRP_NAME.as("businessGroupName"), countryCodeOut, countryNameOut,
          CONFIG.THREE_LETTER_COUNTRY_CODE.as("threeLetterCountryCode"), CONFIG.REGION_CODE.as("regionCode"),
          CONFIG.REGION_NAME.as(REGION_NAME_ALIAS), CONFIG.REPORT_CURRENCY.as("reportCurrency"), CONFIG.REG_RPT_TYPE.as(REPORT_TYPE_ALIAS),
          active, CONFIG.RPT_SELECTION_VERSION_ID.as(REPORT_SELECTION_VERSION_ID_ALIAS),
          CONFIG.TRANSFORMER_VERSION_ID.as(TRANSFORMER_VERSION_ID_ALIAS), CONFIG.CREATED_TIMESTAMP.as("createdAt"),
          CONFIG.MODIFIED_TIMESTAMP.as(MODIFIED_AT_ALIAS),
          DSL.coalesce(CONFIG.DB_LOOKUP_ENABLED, DSL.inline(false)).as(DATABASE_LOOKUP_ENABLED_ALIAS),
          DSL.coalesce(CONFIG.IS_BLANK_REPORT, DSL.inline(false)).as("blankReport"),
          DSL.coalesce(CONFIG.IS_NON_TRANSACTIONAL_REPORT, DSL.inline(false)).as("nonTransactionalReport"),
          DSL.coalesce(CONFIG.IS_PARTIAL_REPORT, DSL.inline(false)).as(PARTIAL_REPORT_ALIAS), CONFIG.RPT_PERIOD.as("reportPeriod"),
          CONFIG.ADDITIONAL_DATA.as("additionalData"), CONFIG.MAPPING_PROJECT_KEY.as("mappingProjectKey"),
          CONFIG.MAPPING_SERVICE_NAME.as(MAPPING_SERVICE_NAME_ALIAS), CONFIG.ACK_PRF_DOCSUBTYPE.as("acknowledgementDocumentSubtype"),
          CONFIG.OUTPUT_FILE_DOCSUBTYPE.as("outputFileDocumentSubtype"), CONFIG.SUBMISSION_PRF_DOCSUBTYPE.as("submissionDocumentSubtype"),
          CONFIG.TRANSFORMER_CONFIG.cast(String.class).as("transformerConfig"), CONFIG.INBOUND_RULE_ID.as("inboundRuleId"),
          CONFIG.OUTBOUND_RULE_ID.as("outboundRuleId"), CONFIG.RPT_SELECTION.as("reportSelection"),
          CONFIG.REG_REPORTABLE_ACTIVITY_COLUMNS.as("reportableActivityColumns"), CONFIG.RULE_HIT_COLUMNS.as("ruleHitColumns"),
          CONFIG.EXCLUSION_STRATEGY.as("exclusionStrategy"), CONFIG.EXCLUSION_REASON.as("exclusionReason"),
          CONFIG.COLUMN_TO_COMPARE.as("columnToCompare"),
          CONFIG.MANIPULATION_STRATEGY_METADATA.cast(String.class).as("manipulationStrategyMetadata"),
          CONFIG.RECONCILIATION_STRATEGY_METADATA.cast(String.class).as("reconciliationStrategyMetadata"))
      .from(CONFIG)
      .where(CONFIG.RPT_GRP_ID.eq(reportGroupId))
      .and(CONFIG.RPT_SELECTION_VERSION_ID.eq(reportSelectionVersionId))
      .and(CONFIG.TRANSFORMER_VERSION_ID.eq(transformerVersionId))
      .fetchOptional(r -> new ReportConfigDetailsProjection(requiredInt(r, REPORT_GROUP_ID_ALIAS),
          r.get(REPORT_GROUP_NAME_ALIAS, String.class), r.get("businessGroupName", String.class), r.get(COUNTRY_CODE_ALIAS, String.class),
          r.get(COUNTRY_NAME_ALIAS, String.class), r.get("threeLetterCountryCode", String.class), r.get("regionCode", String.class),
          r.get(REGION_NAME_ALIAS, String.class), r.get("reportCurrency", String.class), r.get(REPORT_TYPE_ALIAS, String.class),
          requiredBoolean(r, ACTIVE_ALIAS), requiredInt(r, REPORT_SELECTION_VERSION_ID_ALIAS),
          r.get(TRANSFORMER_VERSION_ID_ALIAS, String.class), toInstant(r.get("createdAt", OffsetDateTime.class)),
          toInstant(r.get(MODIFIED_AT_ALIAS, OffsetDateTime.class)), requiredBoolean(r, DATABASE_LOOKUP_ENABLED_ALIAS),
          requiredBoolean(r, "blankReport"), requiredBoolean(r, "nonTransactionalReport"), requiredBoolean(r, PARTIAL_REPORT_ALIAS),
          r.get("reportPeriod", Integer.class), r.get("additionalData", String.class), r.get("mappingProjectKey", String.class),
          r.get(MAPPING_SERVICE_NAME_ALIAS, String.class), r.get("acknowledgementDocumentSubtype", String.class),
          r.get("outputFileDocumentSubtype", String.class), r.get("submissionDocumentSubtype", String.class),
          r.get("transformerConfig", String.class), r.get("inboundRuleId", String.class), r.get("outboundRuleId", String.class),
          r.get("reportSelection", String.class), r.get("reportableActivityColumns", String.class), r.get("ruleHitColumns", String.class),
          r.get("exclusionStrategy", String.class), r.get("exclusionReason", String.class), r.get("columnToCompare", String.class),
          r.get("manipulationStrategyMetadata", String.class), r.get("reconciliationStrategyMetadata", String.class)));
  }
}
