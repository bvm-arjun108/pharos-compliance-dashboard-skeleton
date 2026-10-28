package com.wu.compliance.dashboard.dashboard.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.dashboard.dto.BatchDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionDashboardResponse;
import com.wu.compliance.dashboard.dashboard.model.TrendGranularity;
import com.wu.compliance.dashboard.dashboard.repository.DashboardRepository;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchHealthTrendProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.DashboardCountsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionOverviewProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionVolumeTrendProjection;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.testsupport.DebugLogging;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the weekly/monthly bucket arithmetic, clipping of buckets that straddle the requested
 * period, and the report-group / blank-country scope paths.
 */
class DashboardServiceTrendTest {
  private DashboardRepository repository;
  private DashboardServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(DashboardRepository.class);
    CountryCatalogSnapshot snapshot = new CountryCatalogSnapshot(List.of(new CountryDefinition("US", "United States", Set.of(1, 2))));
    service = new DashboardServiceImpl(repository, () -> snapshot);
  }

  private void stubBatch(List<BatchHealthTrendProjection> trend) {
    when(repository.getBatchDashboardSnapshot(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(new BatchDashboardSnapshotProjection(new DashboardCountsProjection(10, 4, 1, 1, 1, 1, 1, 1, 1), List.of()));
    when(repository.getBatchHealthTrend(any(), any(), any(), any(), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(),
        anyInt()))
      .thenReturn(trend);
  }

  @Test
  void weeklyBucketsAreClippedToTheRequestedPeriod() {
    LocalDate from = LocalDate.of(2026, 9, 2);
    LocalDate to = LocalDate.of(2026, 10, 15);
    stubBatch(List.of(new BatchHealthTrendProjection(LocalDate.of(2026, 8, 31), 3, 2, 1),
        new BatchHealthTrendProjection(LocalDate.of(2026, 10, 12), 3, 3, 0)));
    BatchDashboardResponse response = service.getBatchDashboard(from, to, null, "  ", null);
    assertThat(response.trendGranularity()).isEqualTo(TrendGranularity.WEEKLY);
    assertThat(response.batchHealthTrend().get(0).periodStart()).isEqualTo(from);
    assertThat(response.batchHealthTrend().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 6));
    assertThat(response.batchHealthTrend().get(1).periodEnd()).isEqualTo(to);
  }

  @Test
  void monthlyBucketsEndOnTheLastDayOfTheMonth() {
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = LocalDate.of(2026, 6, 15);
    stubBatch(List.of(new BatchHealthTrendProjection(LocalDate.of(2026, 1, 1), 1, 1, 0),
        new BatchHealthTrendProjection(LocalDate.of(2026, 6, 1), 1, 1, 0)));
    BatchDashboardResponse response = service.getBatchDashboard(from, to, "B", "us", 7);
    assertThat(response.trendGranularity()).isEqualTo(TrendGranularity.MONTHLY);
    assertThat(response.batchHealthTrend().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
    assertThat(response.batchHealthTrend().get(1).periodEnd()).isEqualTo(to);
    verify(repository)
      .getBatchDashboardSnapshot(any(), any(), eq("B"), eq(true), argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(1, 2))),
          eq(true), eq(7));
  }

  @Test
  void transactionTrendUsesTheSameBucketRules() {
    LocalDate from = LocalDate.of(2026, 9, 2);
    LocalDate to = LocalDate.of(2026, 10, 15);
    when(repository.getTransactionDashboardSnapshot(any(), any(), anyString(), anyBoolean(), anyList(), anyBoolean(), anyInt()))
      .thenReturn(new TransactionDashboardSnapshotProjection(new TransactionOverviewProjection(5, 4, 1, 0), List.of(), List.of()));
    when(repository.getTransactionVolumeTrend(any(), any(), any(), any(), anyString(), anyString(), anyBoolean(), anyList(), anyBoolean(),
        anyInt()))
      .thenReturn(List.of(new TransactionVolumeTrendProjection(LocalDate.of(2026, 8, 31), 10, 2),
          new TransactionVolumeTrendProjection(LocalDate.of(2026, 10, 12), 5, 1)));
    try (DebugLogging ignored = DebugLogging.enable()) {
      TransactionDashboardResponse response = service.getTransactionDashboard(from, to, null, null);
      assertThat(response.batchHealthTrend().get(0).periodStart()).isEqualTo(from);
      assertThat(response.batchHealthTrend().get(1).periodEnd()).isEqualTo(to);
    }
  }
}
