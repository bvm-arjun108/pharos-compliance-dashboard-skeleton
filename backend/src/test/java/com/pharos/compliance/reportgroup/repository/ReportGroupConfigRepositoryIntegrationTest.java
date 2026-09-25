package com.pharos.compliance.reportgroup.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigDetailsProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigListProjection;
import com.pharos.compliance.reportgroup.repository.projection.ReportConfigSummaryProjection;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Executes the Phase 1 JDBC migration of {@link ReportGroupConfigRepository} against a real
 * PostgreSQL instance (see {@link PostgresIntegrationTest}), proving execution semantics the
 * jOOQ-era test suite never covered (see the migration plan's Verification section) -- the
 * "100% same resultset" bar was primarily checked separately via a request/response diff against
 * the still-jOOQ-backed disposable backend on port 8086; these tests are the durable, repeatable
 * regression coverage that survives after that manual sweep.
 */
class ReportGroupConfigRepositoryIntegrationTest extends PostgresIntegrationTest {
  private ReportGroupConfigRepository repository;

  @BeforeEach
  void setUpRepository() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_group_config where rpt_grp_id in (7001, 7002)");
    repository = new ReportGroupConfigRepository(tracingJdbcTemplate, new SqlResourceLoader(new DefaultResourceLoader()));
    // Group 7001: two versions. v1 (older, Objective, active, blank country name, lowercase/padded
    // country code) is superseded by v2 (newer, Subjective, inactive, named country) -- v2 is
    // "latest" for every ranked query, but findReportConfigs/getSummary must still see both.
    insertConfig(7001, 1, "1.0", "Test Group 7001", " us ", "United States", " Objective ", true, "2026-01-01T00:00:00Z");
    insertConfig(7001, 2, "2.0", "Test Group 7001", "US", "United States", "Subjective", false, "2026-02-01T00:00:00Z");
    // Group 7002: one version with no country and no report type -- a real filter option that must
    // still appear in findReportGroupOptions, but is excluded from findCountryMappings/findReportTypes.
    insertConfig(7002, 1, "1.0", "Test Group 7002", null, null, null, null, "2026-01-15T00:00:00Z");
  }

  private void insertConfig(int reportGroupId, int selectionVersionId, String transformerVersionId, String groupName, String countryCode,
      String countryName, String reportType, Boolean active, String modifiedTimestamp) {
    jdbcTemplate.update("insert into pharos.report_group_config (rpt_grp_id, rpt_selection_version_id, transformer_version_id, rpt_grp_name, country_code, "
        + "country_name, reg_rpt_type, rpt_config_active_flag, modified_timestamp, created_timestamp) "
        + "values (:id, :selVer, :transVer, :name, :cc, :cn, :type, :active, :modified::timestamptz, :modified::timestamptz)",
        new MapSqlParameterSource()
          .addValue("id", reportGroupId)
          .addValue("selVer", selectionVersionId)
          .addValue("transVer", transformerVersionId)
          .addValue("name", groupName)
          .addValue("cc", countryCode)
          .addValue("cn", countryName)
          .addValue("type", reportType)
          .addValue("active", active)
          .addValue("modified", modifiedTimestamp));
  }

  @Test
  void findCountryMappingsReturnsOnlyTheLatestVersionAndDropsGroupsWithNoCountry() {
    var mappings = repository
      .findCountryMappings()
      .stream()
      .filter(m -> m.reportGroupId() == 7001 || m.reportGroupId() == 7002)
      .toList();

    assertEquals(1, mappings.size(), "group 7002 has no country and must be excluded, and only the latest version of 7001 should appear");
    assertEquals("US", mappings.get(0).countryCode());
    assertEquals("United States", mappings.get(0).countryName());
    assertEquals(7001, mappings.get(0).reportGroupId());
  }

  @Test
  void findReportGroupOptionsIncludesGroupsWithNoCountry() {
    var options = repository
      .findReportGroupOptions()
      .stream()
      .filter(o -> o.reportGroupId() == 7001 || o.reportGroupId() == 7002)
      .toList();

    assertEquals(2, options.size(), "a report group with no country is still a real filter option");
    var group7002 = options
      .stream()
      .filter(o -> o.reportGroupId() == 7002)
      .findFirst()
      .orElseThrow();
    assertEquals(null, group7002.countryCode());
    var group7001 = options
      .stream()
      .filter(o -> o.reportGroupId() == 7001)
      .findFirst()
      .orElseThrow();
    assertEquals("US", group7001.countryCode(), "should reflect the latest (v2) version's country");
  }

  @Test
  void findReportTypesReturnsOnlyTheLatestVersionsTrimmedDistinctType() {
    var types = repository
      .findReportTypes()
      .stream()
      .map(t -> t.reportType())
      .toList();

    assertTrue(types.contains("Subjective"), "v2 (latest for 7001) is Subjective: " + types);
    assertFalse(types.contains("Objective") && !types.contains("Subjective"), "v1's type must not surface once superseded");
  }

  @Test
  void getSummaryCountsEveryVersionNotJustTheLatest() {
    ReportConfigSummaryProjection summary = repository.getSummary("ALL", "ALL", "ALL", 7001);

    assertEquals(1, summary.totalConfigurations(), "one distinct report group");
    assertEquals(1, summary.activeConfigurations(), "only v1 is active; v2 is inactive");
    assertEquals(1, summary.representedCountries());
    assertEquals(1, summary.objectiveConfigurations());
    assertEquals(1, summary.subjectiveConfigurations());
  }

  @Test
  void findReportConfigsListsEveryVersionNewestFirst() {
    List<ReportConfigListProjection> configs = repository.findReportConfigs("ALL", "ALL", "ALL", 7001);

    assertEquals(2, configs.size(), "both historical versions must be listed, not deduped");
    assertEquals(2, configs.get(0).reportSelectionVersionId(), "newest version first within a group");
    assertEquals(1, configs.get(1).reportSelectionVersionId());
    assertTrue(configs.get(1).active(), "v1 is active");
    assertFalse(configs.get(0).active(), "v2 is inactive");
  }

  @Test
  void findReportConfigsFiltersByCountryStatusAndReportType() {
    assertEquals(1, repository
      .findReportConfigs("US", "ACTIVE", "Objective", null)
      .stream()
      .filter(c -> c.reportGroupId() == 7001)
      .count());
    assertEquals(0,
        repository
          .findReportConfigs("US", "INACTIVE", "Objective", null)
          .stream()
          .filter(c -> c.reportGroupId() == 7001)
          .count());
    assertEquals(1, repository
          .findReportConfigs("ALL", "ALL", "subjective", null)
          .stream()
          .filter(c -> c.reportGroupId() == 7001)
          .count(), "report type filter must be case-insensitive");
  }

  @Test
  void findReportConfigDetailsReturnsTheExactVersionRequested() {
    Optional<ReportConfigDetailsProjection> details = repository.findReportConfigDetails(7001, 1, "1.0");

    assertTrue(details.isPresent());
    assertTrue(details.get().active());
    assertEquals(" Objective ", details.get().reportType(), "this query does not trim reportType, matching the original jOOQ query exactly");
    assertEquals("Test Group 7001", details.get().reportGroupName());
  }

  @Test
  void findReportConfigDetailsReturnsEmptyForAnUnknownVersion() {
    assertTrue(repository.findReportConfigDetails(7001, 999, "nope").isEmpty());
  }
}
