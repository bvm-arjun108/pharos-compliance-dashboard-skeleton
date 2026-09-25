package com.pharos.compliance.reportgroup.repository;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.reportgroup.repository.projection.CountryMappingProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigDetailsProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportGroupOptionProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportTypeProjection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every query here reads the same table, {@code pharos.report_group_config}, which stores every
 * version of every report group's configuration that has ever existed. {@link #findReportConfigs}
 * (the configuration directory) and {@link #getSummary} (its summary tiles) show every version
 * matching the caller's filters -- a report group with two versions is two distinct configuration
 * entries, not one row deduped to "the latest." {@link #findCountryMappings} and {@link
 * #findReportTypes} (which build filter dropdown options, where only the current set of
 * countries/report types matters) still rank rows by {@code rpt_grp_id} and keep rank 1, ordered
 * newest-modified first (falling back to newest-created, then highest version numbers, for rows
 * with tied or null timestamps).
 */
@Repository
@Transactional(readOnly = true)
public class ReportGroupConfigRepository {
  private static final String RANKED_CONFIGS_SQL = "sql/reportgroup/ranked-configs.sql";
  private static final String FILTERED_CONFIGS_BASE_SQL = "sql/reportgroup/filtered-configs-base.sql";
  private static final RowMapper<CountryMappingProjection> COUNTRY_MAPPING_ROW_MAPPER =
      (rs, rowNum) -> new CountryMappingProjection(rs.getString("countryCode"), rs.getString("countryName"), rs.getInt("reportGroupId"));
  private static final RowMapper<ReportGroupOptionProjection> REPORT_GROUP_OPTION_ROW_MAPPER =
      (rs, rowNum) -> new ReportGroupOptionProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
          rs.getString("countryCode"));
  private static final RowMapper<ReportTypeProjection> REPORT_TYPE_ROW_MAPPER =
      (rs, rowNum) -> new ReportTypeProjection(rs.getString("reportType"));
  private static final RowMapper<ReportConfigSummaryProjection> SUMMARY_ROW_MAPPER =
      (rs, rowNum) -> new ReportConfigSummaryProjection(rs.getLong("totalConfigurations"), rs.getLong("activeConfigurations"),
          rs.getLong("representedCountries"), rs.getLong("objectiveConfigurations"), rs.getLong("subjectiveConfigurations"));
  private static final RowMapper<ReportConfigListProjection> LIST_ROW_MAPPER =
      (rs, rowNum) -> new ReportConfigListProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
          rs.getInt("reportSelectionVersionId"), rs.getString("transformerVersionId"), rs.getString("countryCode"),
          rs.getString("countryName"), rs.getString("regionName"), rs.getString("reportType"), rs.getBoolean("active"),
          rs.getBoolean("partialReport"), rs.getBoolean("databaseLookupEnabled"), rs.getString("mappingServiceName"),
          toInstant(rs.getObject("modifiedAt", OffsetDateTime.class)));
  private static final RowMapper<ReportConfigDetailsProjection> DETAILS_ROW_MAPPER =
      (rs, rowNum) -> new ReportConfigDetailsProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
          rs.getString("businessGroupName"), rs.getString("countryCode"), rs.getString("countryName"),
          rs.getString("threeLetterCountryCode"), rs.getString("regionCode"), rs.getString("regionName"), rs.getString("reportCurrency"),
          rs.getString("reportType"), rs.getBoolean("active"), rs.getInt("reportSelectionVersionId"), rs.getString("transformerVersionId"),
          toInstant(rs.getObject("createdAt", OffsetDateTime.class)), toInstant(rs.getObject("modifiedAt", OffsetDateTime.class)),
          rs.getBoolean("databaseLookupEnabled"), rs.getBoolean("blankReport"), rs.getBoolean("nonTransactionalReport"),
          rs.getBoolean("partialReport"), (Integer) rs.getObject("reportPeriod"), rs.getString("additionalData"),
          rs.getString("mappingProjectKey"), rs.getString("mappingServiceName"), rs.getString("acknowledgementDocumentSubtype"),
          rs.getString("outputFileDocumentSubtype"), rs.getString("submissionDocumentSubtype"), rs.getString("transformerConfig"),
          rs.getString("inboundRuleId"), rs.getString("outboundRuleId"), rs.getString("reportSelection"),
          rs.getString("reportableActivityColumns"), rs.getString("ruleHitColumns"), rs.getString("exclusionStrategy"),
          rs.getString("exclusionReason"), rs.getString("columnToCompare"), rs.getString("manipulationStrategyMetadata"),
          rs.getString("reconciliationStrategyMetadata"));
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public ReportGroupConfigRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  /**
   * The "latest version per report group" ranking shared by {@link #findCountryMappings}, {@link
   * #findReportGroupOptions} and {@link #findReportTypes}: {@code ROW_NUMBER() OVER (PARTITION BY
   * rpt_grp_id ORDER BY modified_timestamp DESC NULLS LAST, created_timestamp DESC NULLS LAST,
   * rpt_selection_version_id DESC, transformer_version_id DESC)}, loaded once from {@code
   * ranked-configs.sql} and spliced into each caller's own query as the {@code ranked_configs} CTE
   * -- the hand-written equivalent of the jOOQ derived table all three used to share.
   */
  private SqlFragment rankedConfigsCte() {
    return SqlFragment.of(sql.load(RANKED_CONFIGS_SQL)).asCte("ranked_configs");
  }

  @SqlQueryPurpose("Load the latest country-to-report-group mappings")
  public List<CountryMappingProjection> findCountryMappings() {
    SqlFragment combined =
        SqlFragment.combine(List.of(rankedConfigsCte()), SqlFragment.of(sql.load("sql/reportgroup/find-country-mappings.sql")));
    return jdbc.query(combined.sql(), combined.parameterSource(), COUNTRY_MAPPING_ROW_MAPPER);
  }

  /**
   * One row per report group (its latest version, same {@link #rankedConfigsCte} ranking as {@link
   * #findCountryMappings}) for populating report-group filter dropdowns on Batch View, Batch
   * Explorer, and Transactions Overview -- those three pages used to each independently call
   * {@link #findReportConfigs} (every historical version of every report group, every column) and
   * then dedupe to one row per group in client-side JavaScript, three separate times, just to get
   * {@code reportGroupId}/{@code reportGroupName}/{@code countryCode}. Unlike {@link
   * #findCountryMappings}, this doesn't drop report groups with a null/blank country code -- a
   * report group with no country is still a real filter option (it just never matches a specific
   * country filter), and dropping it here would silently remove it from three dropdowns that
   * previously showed it.
   */
  @SqlQueryPurpose("Load the latest report-group options for filter dropdowns")
  public List<ReportGroupOptionProjection> findReportGroupOptions() {
    SqlFragment combined =
        SqlFragment.combine(List.of(rankedConfigsCte()), SqlFragment.of(sql.load("sql/reportgroup/find-report-group-options.sql")));
    return jdbc.query(combined.sql(), combined.parameterSource(), REPORT_GROUP_OPTION_ROW_MAPPER);
  }

  @SqlQueryPurpose("Load the configured regulatory report types")
  public List<ReportTypeProjection> findReportTypes() {
    SqlFragment combined =
        SqlFragment.combine(List.of(rankedConfigsCte()), SqlFragment.of(sql.load("sql/reportgroup/find-report-types.sql")));
    return jdbc.query(combined.sql(), combined.parameterSource(), REPORT_TYPE_ROW_MAPPER);
  }

  /**
   * Shared by {@link #getSummary} and {@link #findReportConfigs}: every {@code report_group_config}
   * row matching the caller's country/status/reportType/reportGroupId filters, each with its own
   * active/inactive status taken from that specific version's own {@code rpt_config_active_flag}.
   *
   * <p>Deliberately <em>not</em> deduped to one row per report group -- a report group can have
   * several historical versions (see the class doc), and a version isn't a duplicate of its report
   * group, it's a distinct configuration that was or is live. {@link #findReportConfigs} lists every
   * one of them; {@link #getSummary} does its own {@code COUNT(DISTINCT rpt_grp_id)} where it
   * specifically wants the group count rather than the version count.
   *
   * <p>The WHERE conditions below are appended as fixed, parameterless-or-bound-parameter SQL
   * fragments chosen by a Java switch/branch -- exactly the jOOQ version's {@code
   * DSL.trueCondition()}/{@code DSL.falseCondition()} branching, just rendered as SQL text instead
   * of jOOQ {@code Condition} objects. The caller's raw strings are never concatenated into the SQL
   * itself: {@code country}/{@code reportType} only ever reach the query as bound named parameters,
   * and {@code status} only ever selects which of four fixed literal fragments is appended.
   */
  private SqlFragment filteredConfigsFragment(String country, String status, String reportType, Integer reportGroupId) {
    StringBuilder body = new StringBuilder(sql.load(FILTERED_CONFIGS_BASE_SQL));
    Map<String, Object> params = new HashMap<>();

    if (!"ALL".equals(country)) {
      body.append("\n  and upper(trim(country_code)) = :country");
      params.put("country", country);
    }

    body.append(
        switch (status) {
          case "ALL" -> "";
          case "ACTIVE" -> "\n  and coalesce(rpt_config_active_flag, false) = true";
          // Field<Boolean> has no isNotTrue() in jOOQ; "= false" here is the same "IS NOT TRUE"
          // equivalent for all three truth values, including NULL, that the original used.
          case "INACTIVE" -> "\n  and coalesce(rpt_config_active_flag, false) = false";
          default -> "\n  and 1 = 0";
        });

    if (!"ALL".equals(reportType)) {
      body.append("\n  and lower(trim(reg_rpt_type)) = :reportType");
      params.put("reportType", reportType.toLowerCase(Locale.ROOT));
    }

    if (reportGroupId != null) {
      body.append("\n  and rpt_grp_id = :reportGroupId");
      params.put("reportGroupId", reportGroupId);
    }

    return SqlFragment.of(body.toString(), params);
  }

  @SqlQueryPurpose("Summarize report-group configurations matching the selected filters")
  public ReportConfigSummaryProjection getSummary(String country, String status, String reportType, Integer reportGroupId) {
    SqlFragment cte = filteredConfigsFragment(country, status, reportType, reportGroupId).asCte("filtered_configs");
    SqlFragment combined = SqlFragment.combine(List.of(cte), SqlFragment.of(sql.load("sql/reportgroup/get-summary.sql")));
    return jdbc
      .queryForOptional(combined.sql(), combined.parameterSource(), SUMMARY_ROW_MAPPER)
      .orElseThrow(() -> new IllegalStateException("Report configuration summary aggregate returned no row"));
  }

  @SqlQueryPurpose("Load report-group configurations matching the selected filters")
  public List<ReportConfigListProjection> findReportConfigs(String country, String status, String reportType, Integer reportGroupId) {
    SqlFragment cte = filteredConfigsFragment(country, status, reportType, reportGroupId).asCte("filtered_configs");
    SqlFragment combined = SqlFragment.combine(List.of(cte), SqlFragment.of(sql.load("sql/reportgroup/find-report-configs.sql")));
    return jdbc.query(combined.sql(), combined.parameterSource(), LIST_ROW_MAPPER);
  }

  @SqlQueryPurpose("Load one report-group configuration version and its strategy metadata")
  public Optional<ReportConfigDetailsProjection> findReportConfigDetails(int reportGroupId, int reportSelectionVersionId,
      String transformerVersionId) {
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("reportGroupId", reportGroupId)
      .addValue("reportSelectionVersionId", reportSelectionVersionId)
      .addValue("transformerVersionId", transformerVersionId);
    return jdbc.queryForOptional(sql.load("sql/reportgroup/find-report-config-details.sql"), params, DETAILS_ROW_MAPPER);
  }
}
