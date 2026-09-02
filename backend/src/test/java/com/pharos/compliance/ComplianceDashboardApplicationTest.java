package com.pharos.compliance;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import com.pharos.compliance.batch.service.BatchExplorerService;
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.jooq.logging.PrettySqlExecuteListener;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.config.PostgresProperties;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.service.DashboardService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.embedded.TomcatVirtualThreadsWebServerFactoryCustomizer;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureObservability
class ComplianceDashboardApplicationTest {
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private DataSource dataSource;
  @Autowired
  private DSLContext dslContext;
  @Autowired
  private PostgresProperties postgresProperties;
  @Autowired
  private ApplicationContext applicationContext;
  @Autowired
  private DashboardService dashboardService;
  @Autowired
  private BatchExplorerService batchExplorerService;
  @Autowired
  private MockMvc mockMvc;

  @Test
  void contextLoads() {}

  @Test
  void connectsToPharosPostgresAndFindsPhaseOneTables() {
    DatabaseMetadata metadata = jdbcTemplate.queryForObject("""
            SELECT
                current_database() AS database,
                to_regclass('pharos.record_transformation_journey')::text AS journey_table,
                to_regclass('pharos.report_transformation_reconciliation')::text AS reconciliation_table,
                to_regclass('pharos.rule_hit_exclusion_audit')::text AS exclusion_table,
                to_regclass('pharos.report_group_config')::text AS report_group_config_table
            """, (
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    resultSet,
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    rowNumber
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                ) -> new DatabaseMetadata(resultSet.getString(
            "database"), resultSet.getString("journey_table"), resultSet.getString("reconciliation_table"),
        resultSet.getString("exclusion_table"), resultSet.getString("report_group_config_table")));

    assertNotNull(metadata);
    assertEquals("pharosRBT", metadata.database());
    assertNotNull(metadata.journeyTable());
    assertNotNull(metadata.reconciliationTable());
    assertNotNull(metadata.exclusionTable());
    assertNotNull(metadata.reportGroupConfigTable());
  }

  @Test
  void calculatesDashboardBatchCountFromPostgres() {
    LocalDate fromDate = LocalDate.of(2026, 1, 1);
    LocalDate toDate = LocalDate.of(2026, 12, 31);

    DashboardDetailsResponse response = dashboardService.getDashboardDetails(fromDate, toDate);

    assertNotNull(response);
    assertTrue(response.batchesRan() > 0);
    assertTrue(response.batchesNotYetReported() >= 0);
    assertEquals(response.batchesRan() - response.batchesNeedingAttention() - response.batchesNotYetReported(), response.successfulBatches());
    assertTrue(response.batchesNeedingAttention() >= 0);
    assertTrue(response.batchesNeedingAttention() + response.batchesNotYetReported() <= response.batchesRan());
    assertTrue(response.transformationFailureBatches() <= response.batchesNeedingAttention());
    assertTrue(response.missingAttemptBatches() <= response.batchesNeedingAttention());
    assertTrue(response.activityMissingBatches() <= response.batchesNeedingAttention());
    assertTrue(response.totalReportedTransactions() >= 0);
    assertTrue(response.totalExcludedTransactions() >= 0);
    assertEquals(TrendGranularity.MONTHLY, response.trendGranularity());
    assertFalse(response.batchHealthTrend().isEmpty());
    assertTrue(response
      .batchHealthTrend()
      .stream()
      .allMatch(period -> period.successfulBatches() + period.batchesNeedingAttention() == period.batchesRan()
          && period.transformationFailureBatches() <= period.batchesNeedingAttention()
          && period.missingAttemptBatches() <= period.batchesNeedingAttention()
          && period.activityMissingBatches() <= period.batchesNeedingAttention() && period.attentionRate() >= 0.0
          && period.attentionRate() <= 100.0));
    for (int index = 1; index < response.batchHealthTrend().size(); index++) {
      assertTrue(response.batchHealthTrend().get(index - 1).periodStart().isBefore(response.batchHealthTrend().get(index).periodStart()));
    }
    assertFalse(response.reportGroupsRequiringAttention().isEmpty());
    assertTrue(response
      .reportGroupsRequiringAttention()
      .stream()
      .allMatch(group -> group.batchesNeedingAttention() > 0));
    for (int index = 1; index < response.reportGroupsRequiringAttention().size(); index++) {
      assertTrue(
          response.reportGroupsRequiringAttention().get(index - 1).batchesNeedingAttention() >= response
            .reportGroupsRequiringAttention()
            .get(index)
            .batchesNeedingAttention());
    }
    assertEquals(fromDate, response.fromDate());
    assertEquals(toDate, response.toDate());
  }

  @Test
  void choosesAdaptiveTrendGranularity() {
    LocalDate start = LocalDate.of(2026, 1, 1);

    assertEquals(TrendGranularity.DAILY, TrendGranularity.forPeriod(start, start.plusDays(30)));
    assertEquals(TrendGranularity.WEEKLY, TrendGranularity.forPeriod(start, start.plusDays(31)));
    assertEquals(TrendGranularity.WEEKLY, TrendGranularity.forPeriod(start, start.plusDays(119)));
    assertEquals(TrendGranularity.MONTHLY, TrendGranularity.forPeriod(start, start.plusDays(120)));
  }

  @Test
  void aggregatesAdaptiveTrendBucketsInPostgres() {
    LocalDate start = LocalDate.of(2026, 1, 1);

    DashboardDetailsResponse daily = dashboardService.getDashboardDetails(start, start.plusDays(30));
    DashboardDetailsResponse weekly = dashboardService.getDashboardDetails(start, start.plusDays(99));
    DashboardDetailsResponse monthly = dashboardService.getDashboardDetails(start, start.plusDays(120));

    assertNotNull(daily);
    assertNotNull(weekly);
    assertNotNull(monthly);
    assertEquals(TrendGranularity.DAILY, daily.trendGranularity());
    assertEquals(31, daily.batchHealthTrend().size());
    assertEquals(TrendGranularity.WEEKLY, weekly.trendGranularity());
    assertEquals(15, weekly.batchHealthTrend().size());
    assertEquals(TrendGranularity.MONTHLY, monthly.trendGranularity());
    assertEquals(5, monthly.batchHealthTrend().size());
  }

  @Test
  void rejectsAnInvertedDashboardPeriod() {
    assertThrows(InvalidDateRangeException.class, () -> dashboardService.getDashboardDetails(LocalDate.of(2026, 8, 31),
        LocalDate.of(2026, 8, 1)));
  }

  @Test
  @SqlQueryPurpose("Verify live jOOQ connectivity during backend integration testing")
  void usesPostgresJooqWithProductionStyleJdbcConfigurationAndVirtualThreads() {
    assertTrue(dataSource instanceof HikariDataSource);
    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertTrue(hikariDataSource.getJdbcUrl().startsWith("jdbc:postgresql://"));
    assertEquals("org.postgresql.Driver", hikariDataSource.getDriverClassName());
    assertEquals(120000L, hikariDataSource.getConnectionTimeout());
    assertEquals(postgresProperties.url(), hikariDataSource.getJdbcUrl());
    // Proves the DSLContext Spring Boot auto-configured from that same HikariDataSource is
    // correctly wired end to end: it dialect-detected Postgres, and a trivial live query round
    // trips through it -- the jOOQ-era equivalent of the old JPA/Hibernate metamodel checks this
    // replaced (jOOQ has no metamodel; a real connection round trip is the closest analogous
    // proof).
    assertEquals(SQLDialect.POSTGRES, dslContext.configuration().dialect());
    assertEquals(Boolean.FALSE, dslContext.configuration().settings().isExecuteLogging());
    assertTrue(java.util.Arrays
      .stream(dslContext.configuration().executeListenerProviders())
      .anyMatch(provider -> provider.provide() instanceof PrettySqlExecuteListener));
    assertEquals(1, dslContext.fetchValue(DSL.one()));
    // jdbcScheduler no longer exists under Spring MVC -- blocking JDBC calls run directly on
    // whichever thread is handling the request. The equivalent guarantee to prove is that Spring
    // Boot actually wired Tomcat to hand every request its own virtual thread
    // (spring.threads.virtual.enabled=true): EmbeddedWebServerFactoryCustomizerAutoConfiguration
    // only registers this customizer bean when @ConditionalOnThreading(Threading.VIRTUAL) matches.
    assertFalse(applicationContext.getBeansOfType(TomcatVirtualThreadsWebServerFactoryCustomizer.class).isEmpty());
  }

  @Test
  void keepsHeadlineAndDailyTrendDateBoundariesConsistent() {
    DashboardDetailsResponse response = dashboardService.getDashboardDetails(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 22));

    assertNotNull(response);
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());
    assertEquals(response.batchesRan(), response
      .batchHealthTrend()
      .stream()
      .mapToLong(period -> period.batchesRan())
      .sum());
  }

  @Test
  void exposesDashboardApiWithTraceHeaders() throws Exception {
    mockMvc
      .perform(get("/dashboardDetails").param("fromDate", "2026-08-16").param("toDate", "2026-08-22"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Trace-Id"))
      .andExpect(header().exists("X-Span-Id"))
      .andExpect(jsonPath("$.trendGranularity").value("DAILY"));
  }

  @Test
  void returnsBackendManagedCountryOptions() {
    BatchFilterOptionsResponse response = batchExplorerService.getFilterOptions();

    assertNotNull(response);
    assertTrue(response.countries().size() >= 7);
    assertTrue(response
      .countries()
      .stream()
      .map(country -> country.code())
      .collect(java.util.stream.Collectors.toSet())
      .containsAll(Set.of("DE", "IT", "PL", "PT", "RO", "SG", "US")));
    assertTrue(response
      .countries()
      .stream()
      .allMatch(country -> country.name() != null && !country.name().isBlank()));
  }

  @Test
  void mapsOneCountryFilterToAllConfiguredReportGroups() {
    BatchExplorerResponse explorer = batchExplorerService.getBatches(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 22), BatchStatus.ALL,
        BatchIssueType.ALL, "", "RO", null, BatchMetricFocus.DEFAULT, 0, 50);
    DashboardDetailsResponse dashboard =
        dashboardService.getDashboardDetails(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 22), "", "RO", null);

    assertNotNull(explorer);
    assertNotNull(dashboard);
    assertEquals(2, explorer.summary().allBatches());
    assertEquals(2, dashboard.batchesRan());
    assertEquals(Set.of(1130, 1573742361),
        explorer
          .batches()
          .stream()
          .map(batch -> batch.reportGroupId())
          .collect(java.util.stream.Collectors.toSet()));
    assertTrue(explorer
      .batches()
      .stream()
      .allMatch(batch -> "RO".equals(batch.countryCode())));
  }

  @Test
  void exposesDatabaseBackedReportConfigWorkspace() throws Exception {
    mockMvc
      .perform(get("/api/v1/report-configs/filter-options"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Trace-Id"))
      .andExpect(jsonPath("$.countries.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)))
      .andExpect(jsonPath("$.reportTypes.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

    mockMvc
      .perform(get("/api/v1/report-configs").param("country", "SG").param("status", "ACTIVE"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Span-Id"))
      .andExpect(jsonPath("$.summary.totalConfigurations").value(2))
      .andExpect(jsonPath("$.summary.activeConfigurations").value(2))
      .andExpect(jsonPath("$.configurations.length()").value(2))
      .andExpect(jsonPath("$.configurations[0].countryCode").value("SG"));
  }

  @Test
  void filtersReportConfigurationsByExactReportGroupId() throws Exception {
    mockMvc
      .perform(get("/api/v1/report-configs").param("reportGroupId", "1573742369"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.summary.totalConfigurations").value(1))
      .andExpect(jsonPath("$.configurations.length()").value(1))
      .andExpect(jsonPath("$.configurations[0].reportGroupId").value(1573742369))
      .andExpect(jsonPath("$.reportGroupId").value(1573742369));
  }

  @Test
  void returnsStructuredReportConfigDetails() throws Exception {
    mockMvc
      .perform(get("/api/v1/report-configs/1573742369/1/1.0"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.identity.reportGroupName").value("SINGAPORE MONTHLY OBJECTIVE"))
      .andExpect(jsonPath("$.identity.countryCode").value("SG"))
      .andExpect(jsonPath("$.versioning.transformerVersionId").value("1.0"))
      .andExpect(jsonPath("$.processingBehavior.partialReport").value(true))
      .andExpect(jsonPath("$.mapping.serviceName").value("SingaporeMonthlyObjectiveService"))
      .andExpect(jsonPath("$.strategies.reconciliationStrategyMetadata").isNotEmpty());
  }

  @Test
  void returnsPrioritizedBatchQueueAndCompositeBatchPreview() {
    BatchExplorerResponse explorer = batchExplorerService.getBatches(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 22), BatchStatus.ALL,
        BatchIssueType.ALL, "", "PT", null, BatchMetricFocus.DEFAULT, 0, 50);

    assertNotNull(explorer);
    assertFalse(explorer.batches().isEmpty());
    assertEquals(explorer.summary().allBatches(), explorer.summary().successfulBatches() + explorer.summary().attentionBatches());
    assertTrue(explorer
      .batches()
      .stream()
      .allMatch(batch -> "PT".equals(batch.countryCode()) && batch.totalIssues() >= 0));

    var selected =
        explorer
      .batches()
      .stream()
      .filter(batch -> batch.sequenceNumber() > 0 && batch.totalIssues() > 0)
      .findFirst()
      .orElseThrow();
    BatchDetailsResponse details =
        batchExplorerService.getBatchDetails(selected.reportGroupId(), selected.batchId(), selected.sequenceNumber());

    assertNotNull(details);
    assertEquals(selected.batchId(), details.batchId());
    assertEquals(selected.totalIssues(), details.totalIssues());
    assertEquals("PT", details.countryCode());
    assertEquals("COMPLETED", details.operationalStatus());
    assertEquals(null, details.finalDownstreamReported());
    assertEquals(Math.max(0, details.selectedTransactions() - details.missingAttempts()), details.transactionAttemptsFound());
    assertEquals(Math.abs(details.expectedReportableTransactions() - details.actualReportableTransactions()), details.filtrationErrors());
    assertEquals(Math.abs(details.expectedTransformationAttempts() - details.actualTransformationAttempts()),
        details.reconciliationImbalance());
  }

  @Test
  void exposesBatchExplorerApiWithTraceHeaders() throws Exception {
    mockMvc
      .perform(get("/api/v1/batches")
        .param("fromDate", "2026-08-16")
        .param("toDate", "2026-08-22")
        .param("country", "PT")
        .param("status", "ATTENTION"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Trace-Id"))
      .andExpect(header().exists("X-Span-Id"))
      .andExpect(jsonPath("$.country").value("PT"))
      .andExpect(jsonPath("$.status").value("ATTENTION"))
      .andExpect(jsonPath("$.batches[0].totalIssues").isNumber());
  }

  @Test
  void drillsIntoOneReportGroupAndSelectedMetric() {
    BatchExplorerResponse transformationBatches = batchExplorerService.getBatches(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 22),
        BatchStatus.ATTENTION, BatchIssueType.TRANSFORMATION, "", "PT", 1000000007, BatchMetricFocus.DEFAULT, 0, 50);
    BatchExplorerResponse excludedBatches = batchExplorerService.getBatches(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 22),
        BatchStatus.ALL, BatchIssueType.ALL, "", "PT", 1000000007, BatchMetricFocus.EXCLUDED, 0, 50);

    assertNotNull(transformationBatches);
    assertEquals(4, transformationBatches.matchingBatches());
    assertEquals("PORTUGAL OBJECTIVE", transformationBatches.reportGroupName());
    assertTrue(transformationBatches
      .batches()
      .stream()
      .allMatch(batch -> batch.reportGroupId() == 1000000007 && batch.transformationFailures() > 0));

    assertNotNull(excludedBatches);
    assertFalse(excludedBatches.batches().isEmpty());
    assertTrue(excludedBatches
      .batches()
      .stream()
      .allMatch(batch -> batch.excludedTransactions() > 0));
    for (int index = 1; index < excludedBatches.batches().size(); index++) {
      assertTrue(
          excludedBatches.batches().get(index - 1).excludedTransactions() >= excludedBatches.batches().get(index).excludedTransactions());
    }
  }

  @Test
  void returnsFullExclusionTransactionEvidenceForOneBatch() throws Exception {
    mockMvc
      .perform(get("/api/v1/transactions/report")
        .param("reportGroupId", "1000000007")
        .param("batchId", "BIN10000000007260822100000")
        .param("sequenceNumber", "1")
        .param("metric", "EXCLUDED"))
      .andExpect(status().isOk())
      .andExpect(header().exists("X-Trace-Id"))
      .andExpect(header().exists("X-Span-Id"))
      .andExpect(jsonPath("$.metric").value("EXCLUDED"))
      .andExpect(jsonPath("$.aggregateCount").value(5))
      .andExpect(jsonPath("$.availableRecordCount").value(5))
      .andExpect(jsonPath("$.evidenceLevel").value("RECORD_LEVEL"))
      .andExpect(jsonPath("$.transactions.length()").value(5))
      .andExpect(jsonPath("$.transactions[0].source").value("EXCLUSION_AUDIT"))
      .andExpect(jsonPath("$.transactions[0].outcome").value("EXCLUDED"));

    mockMvc
      .perform(get("/api/v1/transactions/report")
        .param("reportGroupId", "1000000007")
        .param("batchId", "BIN10000000007260822100000")
        .param("sequenceNumber", "1")
        .param("metric", "EXCLUDED")
        .param("outcome", "ERROR"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.availableRecordCount").value(5))
      .andExpect(jsonPath("$.matchingRecordCount").value(0))
      .andExpect(jsonPath("$.evidenceLevel").value("RECORD_LEVEL"));
  }

  @Test
  void identifiesAggregateOnlyTransactionEvidenceWithoutInventingRows() throws Exception {
    mockMvc
      .perform(get("/api/v1/transactions/report")
        .param("reportGroupId", "1000000007")
        .param("batchId", "BIN10000000007260819121604")
        .param("sequenceNumber", "1")
        .param("metric", "FAILED"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.aggregateCount").value(32))
      .andExpect(jsonPath("$.availableRecordCount").value(0))
      .andExpect(jsonPath("$.evidenceLevel").value("AGGREGATE_ONLY"))
      .andExpect(jsonPath("$.transactions.length()").value(0))
      .andExpect(jsonPath("$.evidenceMessage").isNotEmpty());
  }

  @Test
  void returnsStructuredTraceableErrors() throws Exception {
    mockMvc
      .perform(get("/dashboardDetails").param("fromDate", "2026-08-31").param("toDate", "2026-08-01"))
      .andExpect(status().isBadRequest())
      .andExpect(header().exists("X-Trace-Id"))
      .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
      .andExpect(jsonPath("$.traceId").isNotEmpty())
      .andExpect(jsonPath("$.spanId").isNotEmpty());
  }

  @Test
  void publishesOpenApiDocumentationFromApiInterfaces() throws Exception {
    mockMvc
      .perform(get("/v3/api-docs"))
      .andExpect(status().isOk())
      .andExpect(content().string(containsString("Pharos Compliance Operations API")))
      .andExpect(content().string(containsString("getDashboardDetails")))
      .andExpect(content().string(containsString("getBatchExplorer")))
      .andExpect(content().string(containsString("getBatchPreview")))
      .andExpect(content().string(containsString("getReportConfigs")))
      .andExpect(content().string(containsString("getReportConfigDetails")))
      .andExpect(content().string(containsString("getTransactionEvidenceReport")))
      .andExpect(content().string(containsString("X-Trace-Id")));
  }

  private record DatabaseMetadata(String database, String journeyTable, String reconciliationTable, String exclusionTable,
      String reportGroupConfigTable) {}
}
