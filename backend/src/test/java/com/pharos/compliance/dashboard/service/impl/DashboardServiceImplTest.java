package com.pharos.compliance.dashboard.service.impl;

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
import com.pharos.compliance.common.exception.InvalidDateRangeException;
import com.pharos.compliance.common.exception.InvalidRequestException;
import com.pharos.compliance.dashboard.dto.BatchDashboardResponse;
import com.pharos.compliance.dashboard.dto.TransactionDashboardResponse;
import com.pharos.compliance.dashboard.model.TrendGranularity;
import com.pharos.compliance.dashboard.repository.DashboardRepository;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ExclusionReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.NotReportedReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionOverviewProjection;
import com.pharos.compliance.reportgroup.model.CountryCatalogSnapshot;
import com.pharos.compliance.reportgroup.model.CountryDefinition;
import com.pharos.compliance.reportgroup.service.CountryCatalog;
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
    when(dashboardRepository.getDashboardCounts(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(), anyList(),
        anyBoolean(), anyInt()))
      .thenReturn(fixture.counts());
    when(dashboardRepository.getBatchHealthTrend(any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDate.class),
        any(LocalDate.class), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(fixture.trend());
    when(dashboardRepository.getReportGroupsRequiringAttention(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(),
        anyList(), anyBoolean(), anyInt()))
      .thenReturn(fixture.reportGroups());

    BatchDashboardResponse response = dashboardService.getBatchDashboard(FROM_DATE, TO_DATE, " BIN ", "pt", null);

    assertEquals(12, response.batchesRan());
    assertEquals(7, response.successfulBatches());
    assertEquals(3, response.batchesNeedingAttention());
    assertEquals(2, response.batchesNotYetReported());
    assertEquals(1640, response.totalReportedTransactions());
    assertEquals(18, response.totalExcludedTransactions());
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());
    assertEquals(2, response.batchHealthTrend().size());
    assertEquals(20.0, response.batchHealthTrend().getFirst().attentionRate());
    assertEquals("PORTUGAL OBJECTIVE", response.reportGroupsRequiringAttention().getFirst().reportGroupName());

    verify(dashboardRepository, never()).getTransactionOverview(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
    verify(dashboardRepository, never()).getTopExclusionReasons(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
    verify(dashboardRepository, never()).getNotReportedReasons(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
  }

  @Test
  void buildsTransactionDashboardFromMockedRepositoryDataWithoutRunningBatchKpiQueries() {
    when(dashboardRepository.getTransactionOverview(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(), anyList(),
        anyBoolean(), anyInt()))
      .thenReturn(fixture.transactionOverview());
    when(dashboardRepository.getTopExclusionReasons(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(), anyList(),
        anyBoolean(), anyInt()))
      .thenReturn(fixture.exclusionReasons());
    when(dashboardRepository.getNotReportedReasons(any(LocalDateTime.class), any(LocalDateTime.class), anyString(), anyBoolean(), anyList(),
        anyBoolean(), anyInt()))
      .thenReturn(fixture.notReportedReasons());
    when(dashboardRepository.getBatchHealthTrend(any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDate.class),
        any(LocalDate.class), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(fixture.trend());

    TransactionDashboardResponse response = dashboardService.getTransactionDashboard(FROM_DATE, TO_DATE, "SG", 1573742369);

    assertEquals(583, response.transactionOverview().selected());
    assertEquals(564, response.transactionOverview().expected());
    assertEquals(19, response.transactionOverview().excluded());
    assertEquals(22, response.transactionOverview().notReported());
    assertEquals(2, response.topExclusionReasons().size());
    assertEquals(2, response.notReportedReasons().size());
    assertEquals(TrendGranularity.DAILY, response.trendGranularity());

    verify(dashboardRepository, never()).getDashboardCounts(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
    verify(dashboardRepository, never())
      .getReportGroupsRequiringAttention(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
  }

  @Test
  void validatesFiltersBeforeAnyRepositoryQueryRuns() {
    assertThrows(InvalidDateRangeException.class, () -> dashboardService.getBatchDashboard(TO_DATE, FROM_DATE, "", "PT", null));
    assertThrows(InvalidRequestException.class, () -> dashboardService.getBatchDashboard(FROM_DATE, TO_DATE, "", "XX", null));

    verify(dashboardRepository, never()).getDashboardCounts(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt());
  }

  private record DashboardFixture(List<CountryDefinition> countries, DashboardCountsProjection counts,
      List<BatchHealthTrendProjection> trend, List<ReportGroupMetricsProjection> reportGroups,
      TransactionOverviewProjection transactionOverview, List<ExclusionReasonProjection> exclusionReasons,
      List<NotReportedReasonProjection> notReportedReasons) {}
}
