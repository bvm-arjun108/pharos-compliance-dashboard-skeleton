package com.wu.compliance.dashboard.dashboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.dashboard.dto.BatchDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionDashboardResponse;
import com.wu.compliance.dashboard.dashboard.model.TrendGranularity;
import com.wu.compliance.dashboard.dashboard.repository.DashboardRepository;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchHealthTrendProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.DashboardCountsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ExclusionReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.NotReportedReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionOverviewProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionVolumeTrendProjection;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.reportgroup.service.CountryCatalog;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {
  private static final LocalDate FROM_DATE = LocalDate.of(2026, 8, 20);
  private static final LocalDate TO_DATE = LocalDate.of(2026, 8, 21);
  @Mock
  private DashboardRepository dashboardRepository;
  @Mock
  private CountryCatalog countryCatalog;
  private DashboardServiceImpl dashboardService;
  private DashboardFixture fixture;

  @BeforeEach
  void setUp() throws IOException {
    fixture = new ObjectMapper()
      .findAndRegisterModules()
      .readValue(getClass().getResource("/fixtures/dashboard-service.json"), DashboardFixture.class);
    when(countryCatalog.getSnapshot()).thenReturn(new CountryCatalogSnapshot(fixture.countries()));
    dashboardService = new DashboardServiceImpl(dashboardRepository, countryCatalog);
  }

  @Test
  void buildsBatchDashboardFromMockedRepositoryDataWithoutRunningTransactionQueries() {
    when(dashboardRepository.getBatchDashboardSnapshot(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(),
        anyList(), anyBoolean(), anyInt()))
      .thenReturn(new BatchDashboardSnapshotProjection(fixture.counts(), fixture.reportGroups()));
    when(dashboardRepository.getBatchHealthTrend(any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDate.class),
        any(LocalDate.class), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(fixture.trend());
    BatchDashboardResponse response = dashboardService.getBatchDashboard(FROM_DATE, TO_DATE, " BIN ", "pt", null);

    assertEquals(10, response.batchesRan());
    assertEquals(7, response.successfulBatches());
    assertEquals(3, response.batchesNeedingAttention());
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());
    assertEquals(2, response.batchHealthTrend().size());
    assertEquals(5, response.batchHealthTrend().getFirst().batchesRan());
    assertEquals(1, response.batchHealthTrend().getFirst().batchesNeedingAttention());
    assertEquals("PORTUGAL OBJECTIVE", response.reportGroupsRequiringAttention().getFirst().reportGroupName());

    verify(dashboardRepository, never())
      .getTransactionDashboardSnapshot(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
    verify(dashboardRepository, never())
      .getTransactionVolumeTrend(any(), any(), any(), any(), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
  }

  @Test
  void buildsTransactionDashboardFromMockedRepositoryDataWithoutRunningBatchKpiQueries() {
    when(dashboardRepository.getTransactionDashboardSnapshot(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(),
        anyList(), anyBoolean(), anyInt()))
      .thenReturn(
          new TransactionDashboardSnapshotProjection(fixture.transactionOverview(), fixture.exclusionReasons(), fixture.notReportedReasons()));
    when(dashboardRepository.getTransactionVolumeTrend(any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDate.class),
        any(LocalDate.class), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(fixture.transactionVolumeTrend());

    TransactionDashboardResponse response = dashboardService.getTransactionDashboard(FROM_DATE, TO_DATE, "SG", 1573742369);

    assertEquals(583, response.transactionOverview().selected());
    assertEquals(564, response.transactionOverview().expected());
    assertEquals(19, response.transactionOverview().excluded());
    assertEquals(22, response.transactionOverview().notReported());
    assertEquals(2, response.topExclusionReasons().size());
    assertEquals(2, response.notReportedReasons().size());
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());
    assertEquals(2, response.batchHealthTrend().size());
    assertEquals(710, response.batchHealthTrend().getFirst().totalReportedTransactions());

    verify(dashboardRepository, never()).getBatchDashboardSnapshot(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(),
        anyInt());
    verify(dashboardRepository, never())
      .getBatchHealthTrend(any(), any(), any(), any(), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
  }

  @Test
  void validatesFiltersBeforeAnyRepositoryQueryRuns() {
    assertThrows(InvalidDateRangeException.class, () -> dashboardService.getBatchDashboard(TO_DATE, FROM_DATE, "", "PT", null));
    assertThrows(InvalidRequestException.class, () -> dashboardService.getBatchDashboard(FROM_DATE, TO_DATE, "", "XX", null));

    verify(dashboardRepository, never()).getBatchDashboardSnapshot(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(),
        anyInt());
  }

  private record DashboardFixture(List<CountryDefinition> countries, DashboardCountsProjection counts,
      List<BatchHealthTrendProjection> trend, List<TransactionVolumeTrendProjection> transactionVolumeTrend,
      List<ReportGroupMetricsProjection> reportGroups, TransactionOverviewProjection transactionOverview,
      List<ExclusionReasonProjection> exclusionReasons, List<NotReportedReasonProjection> notReportedReasons) {}
}
