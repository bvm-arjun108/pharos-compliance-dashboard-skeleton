package com.pharos.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import com.pharos.compliance.batch.service.BatchExplorerService;
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.config.PostgresProperties;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationEntity;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.service.DashboardService;
import com.pharos.compliance.reportgroup.entity.ReportGroupConfigEntity;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureObservability
class ComplianceDashboardApplicationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private DataSource dataSource;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private PostgresProperties postgresProperties;

  @Autowired
  @Qualifier("jdbcScheduler") private Scheduler jdbcScheduler;

  @Autowired private DashboardService dashboardService;

  @Autowired private BatchExplorerService batchExplorerService;

  @Autowired private WebTestClient webTestClient;

  @Test
  void contextLoads() {}

  @Test
  void connectsToPharosPostgresAndFindsPhaseOneTables() {
    DatabaseMetadata metadata =
        jdbcTemplate.queryForObject(
            """
            SELECT
                current_database() AS database,
                to_regclass('pharos.record_transformation_journey')::text AS journey_table,
                to_regclass('pharos.report_transformation_reconciliation')::text AS reconciliation_table,
                to_regclass('pharos.rule_hit_exclusion_audit')::text AS exclusion_table,
                to_regclass('pharos.report_group_config')::text AS report_group_config_table
            """,
            (resultSet, rowNumber) ->
                new DatabaseMetadata(
                    resultSet.getString("database"),
                    resultSet.getString("journey_table"),
                    resultSet.getString("reconciliation_table"),
                    resultSet.getString("exclusion_table"),
                    resultSet.getString("report_group_config_table")));

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

    DashboardDetailsResponse response =
        dashboardService.getDashboardDetails(fromDate, toDate).block(Duration.ofSeconds(10));

    assertNotNull(response);
    assertTrue(response.batchesRan() > 0);
    assertTrue(response.batchesNotYetReported() >= 0);
    assertEquals(
        response.batchesRan()
            - response.batchesNeedingAttention()
            - response.batchesNotYetReported(),
        response.successfulBatches());
    assertTrue(response.batchesNeedingAttention() >= 0);
    assertTrue(
        response.batchesNeedingAttention() + response.batchesNotYetReported()
            <= response.batchesRan());
    assertTrue(response.transformationFailureBatches() <= response.batchesNeedingAttention());
    assertTrue(response.missingAttemptBatches() <= response.batchesNeedingAttention());
    assertTrue(response.filtrationFailureBatches() <= response.batchesNeedingAttention());
    assertTrue(response.reconciliationFailureBatches() <= response.batchesNeedingAttention());
    assertTrue(response.totalReportedTransactions() >= 0);
    assertTrue(response.totalExcludedTransactions() >= 0);
    assertEquals(TrendGranularity.MONTHLY, response.trendGranularity());
    assertFalse(response.batchHealthTrend().isEmpty());
    assertTrue(
        response.batchHealthTrend().stream()
            .allMatch(
                period ->
                    period.successfulBatches() + period.batchesNeedingAttention()
                            == period.batchesRan()
                        && period.transformationFailureBatches() <= period.batchesNeedingAttention()
                        && period.missingAttemptBatches() <= period.batchesNeedingAttention()
                        && period.filtrationFailureBatches() <= period.batchesNeedingAttention()
                        && period.reconciliationFailureBatches() <= period.batchesNeedingAttention()
                        && period.attentionRate() >= 0.0
                        && period.attentionRate() <= 100.0));
    for (int index = 1; index < response.batchHealthTrend().size(); index++) {
      assertTrue(
          response
              .batchHealthTrend()
              .get(index - 1)
              .periodStart()
              .isBefore(response.batchHealthTrend().get(index).periodStart()));
    }
    assertFalse(response.reportGroupsRequiringAttention().isEmpty());
    assertTrue(
        response.reportGroupsRequiringAttention().stream()
            .allMatch(group -> group.batchesNeedingAttention() > 0));
    for (int index = 1; index < response.reportGroupsRequiringAttention().size(); index++) {
      assertTrue(
          response.reportGroupsRequiringAttention().get(index - 1).batchesNeedingAttention()
              >= response.reportGroupsRequiringAttention().get(index).batchesNeedingAttention());
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

    DashboardDetailsResponse daily =
        dashboardService
            .getDashboardDetails(start, start.plusDays(30))
            .block(Duration.ofSeconds(10));
    DashboardDetailsResponse weekly =
        dashboardService
            .getDashboardDetails(start, start.plusDays(99))
            .block(Duration.ofSeconds(10));
    DashboardDetailsResponse monthly =
        dashboardService
            .getDashboardDetails(start, start.plusDays(120))
            .block(Duration.ofSeconds(10));

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
    StepVerifier.create(
            dashboardService.getDashboardDetails(
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)))
        .expectError(InvalidDateRangeException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void usesPostgresJpaWithProductionStyleJdbcConfigurationAndVirtualThreads() {
    assertTrue(dataSource instanceof HikariDataSource);
    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertTrue(hikariDataSource.getJdbcUrl().startsWith("jdbc:postgresql://"));
    assertEquals("org.postgresql.Driver", hikariDataSource.getDriverClassName());
    assertEquals(120000L, hikariDataSource.getConnectionTimeout());
    assertEquals(postgresProperties.url(), hikariDataSource.getJdbcUrl());
    assertTrue(entityManagerFactory.isOpen());
    assertNotNull(
        entityManagerFactory.getMetamodel().entity(ReportTransformationReconciliationEntity.class));
    assertNotNull(entityManagerFactory.getMetamodel().entity(ReportGroupConfigEntity.class));

    ThreadExecution execution =
        Mono.fromCallable(
                () ->
                    new ThreadExecution(
                        Thread.currentThread().isVirtual(), Thread.currentThread().getName()))
            .subscribeOn(jdbcScheduler)
            .block(Duration.ofSeconds(5));

    assertNotNull(execution);
    assertTrue(execution.virtual());
    assertTrue(execution.name().startsWith("pharos-jdbc-"));
  }

  @Test
  void keepsHeadlineAndDailyTrendDateBoundariesConsistent() {
    DashboardDetailsResponse response =
        dashboardService
            .getDashboardDetails(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 22))
            .block(Duration.ofSeconds(10));

    assertNotNull(response);
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());
    assertEquals(
        response.batchesRan(),
        response.batchHealthTrend().stream().mapToLong(period -> period.batchesRan()).sum());
  }

  @Test
  void exposesReactiveDashboardApiWithTraceHeaders() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/dashboardDetails")
                    .queryParam("fromDate", "2026-08-16")
                    .queryParam("toDate", "2026-08-22")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectHeader()
        .exists("X-Span-Id")
        .expectBody()
        .jsonPath("$.trendGranularity")
        .isEqualTo("DAILY");
  }

  @Test
  void returnsBackendManagedCountryOptions() {
    BatchFilterOptionsResponse response =
        batchExplorerService.getFilterOptions().block(Duration.ofSeconds(5));

    assertNotNull(response);
    assertEquals(7, response.countries().size());
    assertEquals(
        List.of("DE", "IT", "PL", "PT", "RO", "SG", "US"),
        response.countries().stream().map(country -> country.code()).toList());
    assertTrue(
        response.countries().stream()
            .allMatch(country -> country.name() != null && !country.name().isBlank()));
  }

  @Test
  void mapsOneCountryFilterToAllConfiguredReportGroups() {
    BatchExplorerResponse explorer =
        batchExplorerService
            .getBatches(
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 22),
                BatchStatus.ALL,
                BatchIssueType.ALL,
                "",
                "RO",
                null,
                BatchMetricFocus.DEFAULT,
                0,
                50)
            .block(Duration.ofSeconds(10));
    DashboardDetailsResponse dashboard =
        dashboardService
            .getDashboardDetails(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 22), "", "RO")
            .block(Duration.ofSeconds(10));

    assertNotNull(explorer);
    assertNotNull(dashboard);
    assertEquals(2, explorer.summary().allBatches());
    assertEquals(2, dashboard.batchesRan());
    assertEquals(
        Set.of(1130, 1573742361),
        explorer.batches().stream()
            .map(batch -> batch.reportGroupId())
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(explorer.batches().stream().allMatch(batch -> "RO".equals(batch.countryCode())));
  }

  @Test
  void exposesDatabaseBackedReportConfigWorkspace() {
    webTestClient
        .get()
        .uri("/api/v1/report-configs/filter-options")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.countries.length()")
        .isEqualTo(7)
        .jsonPath("$.reportTypes.length()")
        .isEqualTo(2);

    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/report-configs")
                    .queryParam("country", "SG")
                    .queryParam("status", "ACTIVE")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Span-Id")
        .expectBody()
        .jsonPath("$.summary.totalConfigurations")
        .isEqualTo(2)
        .jsonPath("$.summary.activeConfigurations")
        .isEqualTo(2)
        .jsonPath("$.configurations.length()")
        .isEqualTo(2)
        .jsonPath("$.configurations[0].countryCode")
        .isEqualTo("SG");
  }

  @Test
  void filtersReportConfigurationsByExactReportGroupId() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/report-configs")
                    .queryParam("reportGroupId", 1573742369)
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.summary.totalConfigurations")
        .isEqualTo(1)
        .jsonPath("$.configurations.length()")
        .isEqualTo(1)
        .jsonPath("$.configurations[0].reportGroupId")
        .isEqualTo(1573742369)
        .jsonPath("$.reportGroupId")
        .isEqualTo(1573742369);
  }

  @Test
  void returnsStructuredReportConfigDetails() {
    webTestClient
        .get()
        .uri("/api/v1/report-configs/1573742369/1/1.0")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.identity.reportGroupName")
        .isEqualTo("SINGAPORE MONTHLY OBJECTIVE")
        .jsonPath("$.identity.countryCode")
        .isEqualTo("SG")
        .jsonPath("$.versioning.transformerVersionId")
        .isEqualTo("1.0")
        .jsonPath("$.processingBehavior.partialReport")
        .isEqualTo(true)
        .jsonPath("$.mapping.serviceName")
        .isEqualTo("SingaporeMonthlyObjectiveService")
        .jsonPath("$.strategies.reconciliationStrategyMetadata")
        .isNotEmpty();
  }

  @Test
  void returnsPrioritizedBatchQueueAndCompositeBatchPreview() {
    BatchExplorerResponse explorer =
        batchExplorerService
            .getBatches(
                LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 22),
                BatchStatus.ALL,
                BatchIssueType.ALL,
                "",
                "PT",
                null,
                BatchMetricFocus.DEFAULT,
                0,
                50)
            .block(Duration.ofSeconds(10));

    assertNotNull(explorer);
    assertFalse(explorer.batches().isEmpty());
    assertEquals(
        explorer.summary().allBatches(),
        explorer.summary().successfulBatches() + explorer.summary().attentionBatches());
    assertTrue(explorer.batches().getFirst().totalIssues() > 0);
    assertTrue(
        explorer.batches().stream()
            .allMatch(batch -> "PT".equals(batch.countryCode()) && batch.totalIssues() >= 0));

    var selected = explorer.batches().getFirst();
    BatchDetailsResponse details =
        batchExplorerService
            .getBatchDetails(
                selected.reportGroupId(), selected.batchId(), selected.sequenceNumber())
            .block(Duration.ofSeconds(10));

    assertNotNull(details);
    assertEquals(selected.batchId(), details.batchId());
    assertEquals(selected.totalIssues(), details.totalIssues());
    assertEquals("PT", details.countryCode());
    assertEquals("COMPLETED", details.operationalStatus());
    assertEquals(null, details.finalDownstreamReported());
    assertEquals(
        Math.max(0, details.selectedTransactions() - details.missingAttempts()),
        details.transactionAttemptsFound());
    assertEquals(
        Math.abs(details.expectedReportableTransactions() - details.actualReportableTransactions()),
        details.filtrationErrors());
    assertEquals(
        Math.abs(details.expectedTransformationAttempts() - details.actualTransformationAttempts()),
        details.reconciliationImbalance());
  }

  @Test
  void exposesReactiveBatchExplorerApiWithTraceHeaders() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/batches")
                    .queryParam("fromDate", "2026-08-16")
                    .queryParam("toDate", "2026-08-22")
                    .queryParam("country", "PT")
                    .queryParam("status", "ATTENTION")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectHeader()
        .exists("X-Span-Id")
        .expectBody()
        .jsonPath("$.country")
        .isEqualTo("PT")
        .jsonPath("$.status")
        .isEqualTo("ATTENTION")
        .jsonPath("$.batches[0].totalIssues")
        .isNumber();
  }

  @Test
  void drillsIntoOneReportGroupAndSelectedMetric() {
    BatchExplorerResponse transformationBatches =
        batchExplorerService
            .getBatches(
                LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 22),
                BatchStatus.ATTENTION,
                BatchIssueType.TRANSFORMATION,
                "",
                "PT",
                1000000007,
                BatchMetricFocus.DEFAULT,
                0,
                50)
            .block(Duration.ofSeconds(10));
    BatchExplorerResponse excludedBatches =
        batchExplorerService
            .getBatches(
                LocalDate.of(2026, 8, 16),
                LocalDate.of(2026, 8, 22),
                BatchStatus.ALL,
                BatchIssueType.ALL,
                "",
                "PT",
                1000000007,
                BatchMetricFocus.EXCLUDED,
                0,
                50)
            .block(Duration.ofSeconds(10));

    assertNotNull(transformationBatches);
    assertEquals(4, transformationBatches.matchingBatches());
    assertEquals("PORTUGAL OBJECTIVE", transformationBatches.reportGroupName());
    assertTrue(
        transformationBatches.batches().stream()
            .allMatch(
                batch ->
                    batch.reportGroupId() == 1000000007 && batch.transformationFailures() > 0));

    assertNotNull(excludedBatches);
    assertFalse(excludedBatches.batches().isEmpty());
    assertTrue(
        excludedBatches.batches().stream().allMatch(batch -> batch.excludedTransactions() > 0));
    for (int index = 1; index < excludedBatches.batches().size(); index++) {
      assertTrue(
          excludedBatches.batches().get(index - 1).excludedTransactions()
              >= excludedBatches.batches().get(index).excludedTransactions());
    }
  }

  @Test
  void returnsFullExclusionTransactionEvidenceForOneBatch() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/transactions/report")
                    .queryParam("reportGroupId", 1000000007)
                    .queryParam("batchId", "BIN10000000007260822100000")
                    .queryParam("sequenceNumber", 1)
                    .queryParam("metric", "EXCLUDED")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectHeader()
        .exists("X-Span-Id")
        .expectBody()
        .jsonPath("$.metric")
        .isEqualTo("EXCLUDED")
        .jsonPath("$.aggregateCount")
        .isEqualTo(5)
        .jsonPath("$.availableRecordCount")
        .isEqualTo(5)
        .jsonPath("$.evidenceLevel")
        .isEqualTo("RECORD_LEVEL")
        .jsonPath("$.transactions.length()")
        .isEqualTo(5)
        .jsonPath("$.transactions[0].source")
        .isEqualTo("EXCLUSION_AUDIT")
        .jsonPath("$.transactions[0].outcome")
        .isEqualTo("EXCLUDED");

    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/transactions/report")
                    .queryParam("reportGroupId", 1000000007)
                    .queryParam("batchId", "BIN10000000007260822100000")
                    .queryParam("sequenceNumber", 1)
                    .queryParam("metric", "EXCLUDED")
                    .queryParam("outcome", "ERROR")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.availableRecordCount")
        .isEqualTo(5)
        .jsonPath("$.matchingRecordCount")
        .isEqualTo(0)
        .jsonPath("$.evidenceLevel")
        .isEqualTo("RECORD_LEVEL");
  }

  @Test
  void identifiesAggregateOnlyTransactionEvidenceWithoutInventingRows() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/transactions/report")
                    .queryParam("reportGroupId", 1000000007)
                    .queryParam("batchId", "BIN10000000007260819121604")
                    .queryParam("sequenceNumber", 1)
                    .queryParam("metric", "FAILED")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.aggregateCount")
        .isEqualTo(32)
        .jsonPath("$.availableRecordCount")
        .isEqualTo(0)
        .jsonPath("$.evidenceLevel")
        .isEqualTo("AGGREGATE_ONLY")
        .jsonPath("$.transactions.length()")
        .isEqualTo(0)
        .jsonPath("$.evidenceMessage")
        .isNotEmpty();
  }

  @Test
  void returnsStructuredTraceableErrors() {
    webTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/dashboardDetails")
                    .queryParam("fromDate", "2026-08-31")
                    .queryParam("toDate", "2026-08-01")
                    .build())
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .exists("X-Trace-Id")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_DATE_RANGE")
        .jsonPath("$.traceId")
        .isNotEmpty()
        .jsonPath("$.spanId")
        .isNotEmpty();
  }

  @Test
  void publishesOpenApiDocumentationFromApiInterfaces() {
    webTestClient
        .get()
        .uri("/v3/api-docs")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            document -> {
              assertTrue(document.contains("Pharos Compliance Operations API"));
              assertTrue(document.contains("getDashboardDetails"));
              assertTrue(document.contains("getBatchExplorer"));
              assertTrue(document.contains("getBatchPreview"));
              assertTrue(document.contains("getReportConfigs"));
              assertTrue(document.contains("getReportConfigDetails"));
              assertTrue(document.contains("getTransactionEvidenceReport"));
              assertTrue(document.contains("X-Trace-Id"));
            });
  }

  private record DatabaseMetadata(
      String database,
      String journeyTable,
      String reconciliationTable,
      String exclusionTable,
      String reportGroupConfigTable) {}

  private record ThreadExecution(boolean virtual, String name) {}
}
