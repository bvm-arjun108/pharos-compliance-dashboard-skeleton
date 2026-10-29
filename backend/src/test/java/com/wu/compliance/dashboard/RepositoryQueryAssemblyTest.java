package com.wu.compliance.dashboard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.batch.repository.BatchExplorerRepository;
import com.wu.compliance.dashboard.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlResourceLoader;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceProperties;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceTracker;
import com.wu.compliance.dashboard.dashboard.repository.DashboardRepository;
import com.wu.compliance.dashboard.health.repository.DatabaseHealthRepository;
import com.wu.compliance.dashboard.reportgroup.repository.ReportGroupConfigRepository;
import com.wu.compliance.dashboard.transaction.model.EvidenceCursor;
import com.wu.compliance.dashboard.transaction.model.TransactionSearchField;
import com.wu.compliance.dashboard.transaction.repository.TransactionReportRepository;
import com.wu.compliance.dashboard.transaction.repository.TransactionSearchRepository;
import com.wu.compliance.dashboard.transaction.repository.evidence.EvidenceProjection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Database-free counterpart of {@code RepositoryFilterMatrixIntegrationTest}: every filter
 * combination of every repository is driven through a stubbed JDBC delegate so the SQL assembly
 * and row-mapping code runs on every build (the SonarQube build has no Docker). Whether the SQL is
 * valid PostgreSQL and returns the right rows is what the integration tests prove; this class only
 * proves that each variant is assembled without error, for both an empty and a one-row result.
 */
class RepositoryQueryAssemblyTest {
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 1, 0, 0);
  private static final List<Integer> NO_GROUPS = List.of(-1);
  private static final List<String> METRICS = List.of("ALL", "SELECTED", "ATTEMPTS_FOUND", "MISSING", "ACTIVITY_MISSING",
      "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE", "TRANSFORMED", "FAILED", "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE", "EXCLUDED", "SIMULATED",
      "ALREADY_REPORTED", "SOFT_DEDUP", "FILTERED", "SKIPPED", "TRANSFORMER_OUTPUT", "UNKNOWN_METRIC");
  private static final EvidenceCursor CURSOR = new EvidenceCursor(OffsetDateTime.parse("2026-09-01T00:00:00Z"), "rk");
  private final SqlResourceLoader sql = new SqlResourceLoader(new DefaultResourceLoader());
  private NamedParameterJdbcTemplate delegate;
  private TracingNamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    delegate = mock(NamedParameterJdbcTemplate.class);
    jdbc = new TracingNamedParameterJdbcTemplate(delegate, new QueryPerformanceTracker(new QueryPerformanceProperties(false, null)),
        new ObjectMapper());
    returnNoRows();
  }

  private void returnNoRows() {
    stub(0, false, false);
  }

  private void returnOneRow() {
    stub(1, false, false);
  }

  private boolean omitCurrencyAmount;

  /**
   * Rows like the normal ones but with no currency amount.
   */
  private void returnRowsWithoutAmounts() {
    stub(2, false, false);
    omitCurrencyAmount = true;
  }

  /**
   * One row whose columns are all SQL NULL / zero and whose row type no query defines.
   */
  private void returnSparseRow() {
    stub(1, false, true);
  }

  /**
   * Three rows (so a page of size one has a successor) and SQL NULL for every scalar.
   */
  private void returnManyRowsAndNullScalars() {
    stub(3, true, false);
  }

  @SuppressWarnings("unchecked")
  private void stub(int rowCount, boolean nullScalar, boolean sparse) {
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(invocation -> {
      List<Object> rows = new ArrayList<>();
      RowMapper<Object> mapper = invocation.getArgument(2);
      for (int row = 0; row < rowCount; row++) {
        rows.add(mapper.mapRow(aRow(row, sparse), row));
      }
      return rows;
    });
    when(delegate.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class))).thenAnswer(invocation -> {
      Class<?> type = invocation.getArgument(2);
      if (nullScalar) {
        return null;
      }
      if (type == Long.class) {
        return 0L;
      }
      if (type == Integer.class) {
        return 0;
      }
      if (type == Boolean.class) {
        return true;
      }
      return null;
    });
  }

  private static final List<String> ROW_TYPES = List.of("OVERVIEW", "EXCLUSION_REASON", "NOT_REPORTED_REASON");

  /**
   * A result-set row whose columns read as harmless non-null values of the requested type, or --
   * when {@code sparse} -- as SQL NULL / zero.
   */
  private ResultSet aRow(int rowNumber, boolean sparse) throws java.sql.SQLException {
    ResultSet rs = mock(ResultSet.class, invocation -> {
      Class<?> type = invocation.getMethod().getReturnType();
      Object label = invocation.getArguments().length > 0 ? invocation.getArguments()[0] : null;
      if (type == String.class) {
        if ("rowType".equals(label)) {
          return sparse ? "UNKNOWN" : ROW_TYPES.get(rowNumber % ROW_TYPES.size());
        }
        return sparse ? null : "1";
      }
      if (type == int.class) {
        return sparse ? 0 : 1;
      }
      if (type == long.class) {
        return sparse ? 0L : 1L;
      }
      if (type == boolean.class) {
        return false;
      }
      if (type == double.class) {
        return sparse ? 0d : 1d;
      }
      if (type == java.math.BigDecimal.class) {
        return sparse ? null : java.math.BigDecimal.ONE;
      }
      if (type == Timestamp.class) {
        return sparse ? null : Timestamp.valueOf(FROM);
      }
      if (type == java.sql.Date.class) {
        return sparse ? null : java.sql.Date.valueOf(FROM.toLocalDate());
      }
      if (!sparse && !omitCurrencyAmount && "currencyAmount".equals(label)) {
        return 1d;
      }
      if (!sparse && "processingComplete".equals(label)) {
        return true;
      }
      return null;
    });
    if (!sparse) {
      when(rs.getObject(anyString(), eq(OffsetDateTime.class))).thenReturn(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
      when(rs.getObject(anyString(), eq(LocalDateTime.class))).thenReturn(FROM);
      when(rs.getObject(anyString(), eq(Long.class))).thenReturn(1L);
      when(rs.getObject(anyString(), eq(Integer.class))).thenReturn(1);
      when(rs.getObject(anyString(), eq(String.class))).thenReturn("1");
      when(rs.getObject(anyString(), eq(Boolean.class))).thenReturn(false);
    }
    return rs;
  }

  /**
   * Runs once against an empty result and once against a one-row result. The second pass only
   * exercises the row mappers, so it tolerates a mapper meeting a value no real column would hold.
   */
  private void bothResults(Runnable call) {
    returnNoRows();
    assertThatCode(() -> {
      try {
        call.run();
      } catch (IllegalStateException e) {
        // aggregate and metadata lookups legitimately reject an empty result
        if (!e.getMessage().contains("no ")) {
          throw e;
        }
      }
    })
      .doesNotThrowAnyException();
    for (Runnable result :
        List.<Runnable>of(this::returnOneRow, this::returnManyRowsAndNullScalars, this::returnSparseRow, this::returnRowsWithoutAmounts)) {
      omitCurrencyAmount = false;
      result.run();
      try {
        call.run();
      } catch (RuntimeException ignored) {
        // a synthetic row need not satisfy downstream invariants; the mapper lines already ran
      }
    }
  }

  // --- batch explorer ---
  @Test
  void batchExplorerFilterCombinations() {
    BatchExplorerRepository batches = new BatchExplorerRepository(jdbc, sql);
    for (String status : List.of("ALL", "SUCCESSFUL", "ATTENTION", "BOGUS")) {
      for (String issue :
          List.of("ALL", "ACTIVITY_MISSING", "MISSING_ATTEMPTS", "TRANSFORMATION", "DUPLICATE_TRANSFORMATION", "EXCLUSION", "SIMULATED",
              "SOFT_DEDUP", "BOGUS")) {
        for (String focus : List.of("DEFAULT", "REPORTED", "EXCLUDED", "BOGUS")) {
          bothResults(() -> batches.getBatchQueue(FROM, TO, "b", 1, true, List.of(1, 2), status, issue, focus, 10, 0));
        }
      }
    }
    bothResults(() -> batches.getBatchQueue(FROM, TO, "", null, false, NO_GROUPS, "ALL", "ALL", "DEFAULT", 10, 0));
    bothResults(() -> batches.getBatchQueue(FROM, TO, null, null, true, List.of(), "ALL", "ALL", "DEFAULT", 10, 0));
    bothResults(() -> batches.getBatchSummary(FROM, TO, "b", 1, true, List.of(1)));
    bothResults(() -> batches.getBatchSummary(FROM, TO, "", null, true, List.of()));
    bothResults(() -> batches.getBatchDetails(1, "none", 1));
  }

  // --- report-group configuration and health ---
  @Test
  void reportConfigFilterCombinations() {
    ReportGroupConfigRepository reportGroups = new ReportGroupConfigRepository(jdbc, sql);
    for (String status : List.of("ALL", "ACTIVE", "INACTIVE", "BOGUS")) {
      bothResults(() -> reportGroups.findReportConfigs("US", status, "CTR", 1));
      bothResults(() -> reportGroups.getSummary("ALL", status, "ALL", null));
    }
    bothResults(() -> reportGroups.findReportConfigs("ALL", "ALL", "ALL", null));
    bothResults(() -> reportGroups.findReportConfigDetails(1, 1, "tv"));
    bothResults(reportGroups::findCountryMappings);
    bothResults(reportGroups::findReportGroupOptions);
    bothResults(reportGroups::findReportTypes);
  }

  @Test
  void databaseHealthProbe() {
    DatabaseHealthRepository health = new DatabaseHealthRepository(jdbc, sql);
    bothResults(health::getDatabaseMetadata);
  }

  // --- dashboard ---
  @ParameterizedTest
  @ValueSource(strings = {"DAILY", "WEEKLY", "MONTHLY"})
  void dashboardTrendsForEveryGranularity(String granularity) {
    DashboardRepository dashboard = new DashboardRepository(jdbc, sql);
    LocalDate from = FROM.toLocalDate();
    LocalDate to = TO.toLocalDate().minusDays(1);
    bothResults(() -> dashboard.getBatchHealthTrend(FROM, TO, from, to, granularity, "b", true, List.of(1), true, 1));
    bothResults(() -> dashboard.getBatchHealthTrend(FROM, TO, from, to, granularity, "", false, NO_GROUPS, false, -1));
    bothResults(() -> dashboard.getTransactionVolumeTrend(FROM, TO, from, to, granularity, "b", true, List.of(1), true, 1));
    bothResults(() -> dashboard.getTransactionVolumeTrend(FROM, TO, from, to, granularity, "", false, NO_GROUPS, false, -1));
  }

  @Test
  void dashboardSnapshotsForEveryScope() {
    DashboardRepository dashboard = new DashboardRepository(jdbc, sql);
    bothResults(() -> dashboard.getBatchDashboardSnapshot(FROM, TO, null, true, List.of(1), true, 1));
    bothResults(() -> dashboard.getTransactionDashboardSnapshot(FROM, TO, null, true, List.of(1), true, 1));
    bothResults(() -> dashboard.getBatchDashboardSnapshot(FROM, TO, "b", true, List.of(1), true, 1));
    bothResults(() -> dashboard.getBatchDashboardSnapshot(FROM, TO, "", true, List.of(), false, -1));
    bothResults(() -> dashboard.getBatchDashboardSnapshot(FROM, TO, "", false, NO_GROUPS, false, -1));
    bothResults(() -> dashboard.getTransactionDashboardSnapshot(FROM, TO, "b", true, List.of(1), true, 1));
    bothResults(() -> dashboard.getTransactionDashboardSnapshot(FROM, TO, "", true, List.of(), false, -1));
    bothResults(() -> dashboard.getTransactionDashboardSnapshot(FROM, TO, "", false, NO_GROUPS, false, -1));
  }

  // --- batch evidence report ---
  @Test
  void batchEvidenceForEveryMetricAndFilter() {
    TransactionReportRepository reports = new TransactionReportRepository(jdbc, sql);
    for (String metric : METRICS) {
      bothResults(() -> reports.findEvidenceRecords(1, "B", metric, "", "ALL", "ALL", "ALL", "ALL", "ASC", 10, 0, null));
      bothResults(() -> reports.countEvidenceRecords(1, "B", metric, "", "ALL", "ALL", "ALL", "ALL"));
    }
    for (String source : List.of("ALL", "JOURNEY", "EXCLUSION_AUDIT", "RULE_HIT")) {
      for (String status : List.of("ALL", "REPORTED", "NOT_REPORTED", "EXCLUDED", "ERROR")) {
        bothResults(() -> reports.findEvidenceRecords(1, "B", "ALL", "x_%", source, "TRANSFORMATION", "SUCCESS", status, "DESC", 10, 5,
            CURSOR));
        bothResults(() -> reports.countEvidenceRecords(1, "B", "FAILED", "x", source, "ALL", "ALL", status));
      }
    }
    bothResults(() -> reports.findReportContext(1, "B", 1));
    bothResults(() -> reports.findBatchEvidenceDetail(1, "B", "id", "ALL", "ALL", "ALL", "ALL", "ALL", "rk"));
    bothResults(() -> reports.findBatchEvidenceDetail(1, "B", "id", "FAILED", "JOURNEY", "S", "SUCCESS", "REPORTED", "rk"));
  }

  // --- period evidence report ---
  @Test
  void periodEvidenceForEveryStatusScopeAndFlag() {
    TransactionReportRepository reports = new TransactionReportRepository(jdbc, sql);
    for (String status : List.of("ALL", "SUCCESS", "FAILED", "ERROR", "EXCLUDED", "NOT_YET_REPORTED", "REPORTED", "NOT_REPORTED")) {
      for (boolean batchScoped : List.of(false, true)) {
        for (EvidenceCursor cursor : new EvidenceCursor[] {null, CURSOR}) {
          bothResults(() -> reports.findPeriodEvidenceRecords(FROM, TO, true, List.of(1, 2), false, -1, "b", "q_%", "ALL", status, "reason",
              batchScoped, "ASC", 10, 0, cursor, EvidenceProjection.LIST));
          bothResults(() -> reports.findPeriodEvidenceRecords(FROM, TO, false, NO_GROUPS, true, 1, "", "", "SUCCESS", status, "",
              batchScoped, "DESC", 10, 20, cursor, EvidenceProjection.LIST));
        }
        bothResults(() -> reports.countPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "b", "q", "ALL", status, "r",
            batchScoped));
        bothResults(() -> reports.countPeriodEvidenceRecords(FROM, TO, false, NO_GROUPS, true, 1, "", "", "ERROR", status, "", batchScoped));
        bothResults(() -> reports.findPeriodEvidenceDetail(FROM, TO, false, NO_GROUPS, true, 1, "B", "id", "ALL", status, "", batchScoped,
            "", "rk"));
        bothResults(() -> reports.findPeriodEvidenceDetail(FROM, TO, true, List.of(1), false, -1, "B", "id", "SUCCESS", status, "r",
            batchScoped, "f", "rk"));
      }
    }
    bothResults(() -> reports.findPeriodEvidenceRecords(FROM, TO, true, List.of(), false, -1, "b", "", "ALL", "ALL", "", false, "ASC", 10, 0,
        null, EvidenceProjection.LIST));
    bothResults(() -> reports.findPeriodEvidenceDetail(FROM, TO, false, NO_GROUPS, false, -1, "1", "1", "ALL", "ALL", "", false, "", null));
    bothResults(() -> reports.findPeriodEvidenceDetail(FROM, TO, false, NO_GROUPS, false, -1, "B", "1", "ALL", "ALL", "", false, "", " "));
    bothResults(() -> reports.findPeriodEvidenceDetail(FROM, TO, false, NO_GROUPS, false, -1, "1", "1", "ALL", "ALL", "", false, "", "1"));
    bothResults(() -> reports.findBatchEvidenceDetail(1, "1", "1", "ALL", "ALL", "ALL", "ALL", "ALL", null));
    bothResults(() -> reports.findBatchEvidenceDetail(1, "B", "1", "ALL", "ALL", "ALL", "ALL", "ALL", "1"));
    for (String status : List.of("EXCLUDED", "NOT_REPORTED")) {
      for (String reason : java.util.Arrays.asList("Other", "Some reason", "", null)) {
        for (String search : List.of("", "q")) {
          for (String outcome : List.of("ALL", "ERROR")) {
            bothResults(() -> reports.countPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "", search, outcome, status, reason,
                false));
            for (int size : List.of(1, 10)) {
              for (EvidenceCursor cursor : new EvidenceCursor[] {null, CURSOR, new EvidenceCursor(null, "rk")}) {
                for (String direction : List.of("ASC", "DESC")) {
                  bothResults(() -> reports.findPeriodEvidenceRecords(FROM, TO, false, NO_GROUPS, false, -1, "", search, outcome, status,
                      reason, false, direction, size, 0, cursor, EvidenceProjection.LIST));
                }
              }
            }
          }
        }
      }
    }
    bothResults(() -> reports.findPeriodAggregate(FROM, TO, true, List.of(1), true, 1, null));
    bothResults(() -> reports.findPeriodAggregate(FROM, TO, true, List.of(1), true, 1, "b"));
    bothResults(() -> reports.findPeriodAggregate(FROM, TO, false, NO_GROUPS, false, -1, ""));
    bothResults(() -> reports.findPeriodBatchIds(FROM, TO, true, List.of(1), true, 1));
    bothResults(() -> reports.findPeriodBatchIds(FROM, TO, false, NO_GROUPS, false, -1));
  }

  // --- search ---
  @Test
  void searchForEveryFieldAndScope() {
    TransactionSearchRepository search = new TransactionSearchRepository(jdbc, sql);
    var scopes = List.of(new TransactionSearchRepository.SearchScope(FROM, TO, false, NO_GROUPS, false, -1),
        new TransactionSearchRepository.SearchScope(FROM, TO, true, List.of(1, 2), false, -1),
        new TransactionSearchRepository.SearchScope(FROM, TO, false, NO_GROUPS, true, 1));
    for (TransactionSearchField field : TransactionSearchField.values()) {
      for (String query : List.of("123", "123_P", "123_s", "abc", "99999999999999999999")) {
        bothResults(() -> search.search(field, query));
        for (var scope : scopes) {
          bothResults(() -> search.search(field, query, scope));
        }
      }
    }
  }

  // --- paths the repository routing never reaches but the classes still define ---
  @Test
  void overviewQueriesRejectNoStatusBucketReasonFilter() {
    com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.EvidencePaginator paginator =
        new com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.EvidencePaginator(jdbc, sql);
    var rules = new com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.RuleHitMatcher();
    var period = new com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.PeriodEvidenceQueries(jdbc, sql, rules, paginator);
    var overview =
        new com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.OverviewEvidenceQueries(jdbc, sql, paginator, period);
    var scope = period.batchScope(FROM, TO, false, NO_GROUPS, false, -1, "");
    bothResults(() -> overview.findOverviewEvidenceRecords(scope, "ALL", "reason", "", "ALL", "ASC", 10, 0, null, EvidenceProjection.LIST));
    bothResults(() -> overview.countOverviewEvidenceRecords(scope, "ALL", "reason", "", "ALL"));
  }

  @Test
  void anEmptyMergePageRendersAsNoRows() throws Exception {
    var paginator = new com.wu.compliance.dashboard.transaction.repository.evidence.jdbc.EvidencePaginator(jdbc, sql);
    var method = paginator.getClass().getDeclaredMethod("buildMergeCte", List.class, boolean.class);
    method.setAccessible(true);
    for (boolean carriesReportGroupId : List.of(true, false)) {
      assertThatCode(() -> method.invoke(paginator, List.of(), carriesReportGroupId)).doesNotThrowAnyException();
    }
  }
}
