package com.wu.compliance.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.wu.compliance.dashboard.batch.repository.BatchExplorerRepository;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlResourceLoader;
import com.wu.compliance.dashboard.dashboard.repository.DashboardRepository;
import com.wu.compliance.dashboard.reportgroup.repository.ReportGroupConfigRepository;
import com.wu.compliance.dashboard.testsupport.PostgresIntegrationTest;
import com.wu.compliance.dashboard.transaction.model.EvidenceCursor;
import com.wu.compliance.dashboard.transaction.repository.TransactionReportRepository;
import com.wu.compliance.dashboard.transaction.repository.TransactionSearchRepository;
import com.wu.compliance.dashboard.transaction.repository.evidence.EvidenceProjection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Runs every filter combination of every repository against the (empty) schema-only database. The
 * point is that each generated SQL variant is valid PostgreSQL and takes the intended branch; data
 * semantics are covered by the seeded repository tests.
 */
class RepositoryFilterMatrixIntegrationTest extends PostgresIntegrationTest {
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 1, 0, 0);
  private static final List<Integer> NO_GROUPS = List.of(-1);
  private BatchExplorerRepository batches;
  private DashboardRepository dashboard;
  private ReportGroupConfigRepository reportGroups;
  private TransactionReportRepository reports;
  private TransactionSearchRepository search;

  @BeforeEach
  void setUp() {
    SqlResourceLoader sql = new SqlResourceLoader(new DefaultResourceLoader());
    batches = new BatchExplorerRepository(tracingJdbcTemplate, sql);
    dashboard = new DashboardRepository(tracingJdbcTemplate, sql);
    reportGroups = new ReportGroupConfigRepository(tracingJdbcTemplate, sql);
    reports = new TransactionReportRepository(tracingJdbcTemplate, sql);
    search = new TransactionSearchRepository(tracingJdbcTemplate, sql);
  }

  // --- batch explorer ---
  @Test
  void batchQueueFilterCombinations() {
    for (String status : List.of("ALL", "SUCCESSFUL", "ATTENTION", "BOGUS")) {
      for (String issue :
          List.of("ALL", "ACTIVITY_MISSING", "MISSING_ATTEMPTS", "TRANSFORMATION", "DUPLICATE_TRANSFORMATION", "EXCLUSION", "SIMULATED",
              "SOFT_DEDUP", "BOGUS")) {
        for (String focus : List.of("DEFAULT", "REPORTED", "EXCLUDED", "BOGUS")) {
          assertThat(batches.getBatchQueue(FROM, TO, "b", 1, true, List.of(1, 2), status, issue, focus, 10, 0)).isEmpty();
        }
      }
    }
    assertThat(batches.getBatchQueue(FROM, TO, "", null, false, NO_GROUPS, "ALL", "ALL", "DEFAULT", 10, 0)).isEmpty();
    assertThat(batches.getBatchQueue(FROM, TO, null, null, true, List.of(), "ALL", "ALL", "DEFAULT", 10, 0)).isEmpty();
    assertThat(batches.getBatchSummary(FROM, TO, "b", 1, true, List.of(1)).allBatches()).isZero();
    assertThat(batches.getBatchSummary(FROM, TO, "", null, true, List.of()).allBatches()).isZero();
    assertThat(batches.getBatchDetails(1, "none", 1)).isEmpty();
  }

  // --- report-group configuration ---
  @Test
  void reportConfigFilterCombinations() {
    for (String status : List.of("ALL", "ACTIVE", "INACTIVE", "BOGUS")) {
      assertThat(reportGroups.findReportConfigs("US", status, "CTR", 1)).isEmpty();
      assertThat(reportGroups.getSummary("ALL", status, "ALL", null).totalConfigurations()).isZero();
    }
    assertThat(reportGroups.findReportConfigs("ALL", "ALL", "ALL", null)).isEmpty();
    assertThat(reportGroups.findReportConfigDetails(1, 1, "tv")).isEmpty();
    assertThat(reportGroups.findCountryMappings()).isNotNull();
    assertThat(reportGroups.findReportGroupOptions()).isNotNull();
    assertThat(reportGroups.findReportTypes()).isNotNull();
  }

  // --- dashboard ---
  @ParameterizedTest
  @ValueSource(strings = {"DAILY", "WEEKLY", "MONTHLY"})
  void dashboardTrendsForEveryGranularity(String granularity) {
    LocalDate from = FROM.toLocalDate();
    LocalDate to = TO.toLocalDate().minusDays(1);
    assertThat(dashboard.getBatchHealthTrend(FROM, TO, from, to, granularity, "b", true, List.of(1), true, 1)).isNotNull();
    assertThat(dashboard.getBatchHealthTrend(FROM, TO, from, to, granularity, "", false, NO_GROUPS, false, -1)).isNotNull();
    assertThat(dashboard.getTransactionVolumeTrend(FROM, TO, from, to, granularity, "b", true, List.of(1), true, 1)).isNotNull();
    assertThat(dashboard.getTransactionVolumeTrend(FROM, TO, from, to, granularity, "", false, NO_GROUPS, false, -1)).isNotNull();
  }

  @Test
  void dashboardSnapshotsForEveryScope() {
    assertThat(dashboard.getBatchDashboardSnapshot(FROM, TO, "b", true, List.of(1), true, 1).counts()).isNotNull();
    assertThat(dashboard.getBatchDashboardSnapshot(FROM, TO, "", true, List.of(), false, -1).counts()).isNotNull();
    assertThat(dashboard.getBatchDashboardSnapshot(FROM, TO, "", false, NO_GROUPS, false, -1).reportGroups()).isEmpty();
    assertThat(dashboard.getTransactionDashboardSnapshot(FROM, TO, "b", true, List.of(1), true, 1).overview()).isNotNull();
    assertThat(dashboard.getTransactionDashboardSnapshot(FROM, TO, "", true, List.of(), false, -1).overview()).isNotNull();
    assertThat(dashboard.getTransactionDashboardSnapshot(FROM, TO, "", false, NO_GROUPS, false, -1).overview()).isNotNull();
  }

  // --- batch evidence report ---
  private static final List<String> METRICS = List.of("ALL", "SELECTED", "ATTEMPTS_FOUND", "MISSING", "ACTIVITY_MISSING",
      "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE", "TRANSFORMED", "FAILED", "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE", "EXCLUDED", "SIMULATED",
      "ALREADY_REPORTED", "SOFT_DEDUP", "FILTERED", "SKIPPED", "TRANSFORMER_OUTPUT", "UNKNOWN_METRIC");

  @Test
  void batchEvidenceForEveryMetricAndFilter() {
    for (String metric : METRICS) {
      assertThat(reports.findEvidenceRecords(1, "B", metric, "", "ALL", "ALL", "ALL", "ALL", "ASC", 10, 0, null).records()).isEmpty();
      assertThat(reports.countEvidenceRecords(1, "B", metric, "", "ALL", "ALL", "ALL", "ALL")).isZero();
    }
    for (String source : List.of("ALL", "JOURNEY", "EXCLUSION_AUDIT", "RULE_HIT")) {
      for (String status : List.of("ALL", "REPORTED", "NOT_REPORTED", "EXCLUDED", "ERROR")) {
        assertThat(reports
          .findEvidenceRecords(1, "B", "ALL", "x_%", source, "TRANSFORMATION", "SUCCESS", status, "DESC", 10, 5,
              new EvidenceCursor(OffsetDateTime.parse("2026-09-01T00:00:00Z"), "rk"))
          .records())
          .isEmpty();
        assertThat(reports.countEvidenceRecords(1, "B", "FAILED", "x", source, "ALL", "ALL", status)).isZero();
      }
    }
    assertThat(reports.findReportContext(1, "B", 1)).isEmpty();
    assertThat(reports.findBatchEvidenceDetail(1, "B", "id", "ALL", "ALL", "ALL", "ALL", "ALL", "rk")).isEmpty();
    assertThat(reports.findBatchEvidenceDetail(1, "B", "id", "FAILED", "JOURNEY", "S", "SUCCESS", "REPORTED", "rk")).isEmpty();
  }

  // --- period evidence report ---
  @Test
  void periodEvidenceForEveryStatusScopeAndFlag() {
    EvidenceCursor cursor = new EvidenceCursor(OffsetDateTime.parse("2026-09-01T00:00:00Z"), "rk");
    for (String status : List.of("ALL", "SUCCESS", "FAILED", "ERROR", "EXCLUDED", "NOT_YET_REPORTED", "REPORTED", "NOT_REPORTED")) {
      for (boolean batchScoped : List.of(false, true)) {
        for (EvidenceCursor c : new EvidenceCursor[] {null, cursor}) {
          assertThat(reports
            .findPeriodEvidenceRecords(FROM, TO, true, List.of(1, 2), false, -1, "b", "q_%", "ALL", status, "reason", batchScoped, "ASC", 10,
                0, c, EvidenceProjection.LIST)
            .records())
            .isEmpty();
          assertThat(reports
            .findPeriodEvidenceRecords(FROM, TO, false, NO_GROUPS, true, 1, "", "", "SUCCESS", status, "", batchScoped, "DESC", 10, 20, c,
                EvidenceProjection.LIST)
            .records())
            .isEmpty();
        }
        assertThat(reports.countPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "b", "q", "ALL", status, "r", batchScoped)).isZero();
        assertThat(reports.countPeriodEvidenceRecords(FROM, TO, false, NO_GROUPS, true, 1, "", "", "ERROR", status, "", batchScoped)).isZero();
        assertThat(reports.findPeriodEvidenceDetail(FROM, TO, false, NO_GROUPS, true, 1, "B", "id", "ALL", status, "", batchScoped, "", "rk"))
          .isEmpty();
        assertThat(reports.findPeriodEvidenceDetail(FROM, TO, true, List.of(1), false, -1, "B", "id", "SUCCESS", status, "r", batchScoped,
            "f", "rk"))
          .isEmpty();
      }
    }
    assertThat(reports.findPeriodAggregate(FROM, TO, true, List.of(1), true, 1, "b").batchCount()).isZero();
    assertThat(reports.findPeriodAggregate(FROM, TO, false, NO_GROUPS, false, -1, "").batchCount()).isZero();
    assertThat(reports.findPeriodBatchIds(FROM, TO, true, List.of(1), true, 1)).isEmpty();
    assertThat(reports.findPeriodBatchIds(FROM, TO, false, NO_GROUPS, false, -1)).isEmpty();
  }

  // --- search ---
  @Test
  void searchForEveryFieldAndScope() {
    var scopes = List.of(new TransactionSearchRepository.SearchScope(FROM, TO, false, NO_GROUPS, false, -1),
        new TransactionSearchRepository.SearchScope(FROM, TO, true, List.of(1, 2), false, -1),
        new TransactionSearchRepository.SearchScope(FROM, TO, false, NO_GROUPS, true, 1));
    for (var field : com.wu.compliance.dashboard.transaction.model.TransactionSearchField.values()) {
      for (String query : List.of("123", "123_P", "123_s", "abc", "99999999999999999999")) {
        assertThatCode(() -> search.search(field, query)).doesNotThrowAnyException();
        for (var scope : scopes) {
          assertThatCode(() -> search.search(field, query, scope)).doesNotThrowAnyException();
        }
      }
    }
  }
}
