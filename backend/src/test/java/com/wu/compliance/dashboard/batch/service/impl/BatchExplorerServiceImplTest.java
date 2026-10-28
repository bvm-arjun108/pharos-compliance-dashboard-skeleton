package com.wu.compliance.dashboard.batch.service.impl;

import com.wu.compliance.dashboard.testsupport.DebugLogging;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.batch.dto.BatchDetailsResponse;
import com.wu.compliance.dashboard.batch.dto.BatchExplorerResponse;
import com.wu.compliance.dashboard.batch.model.BatchIssueType;
import com.wu.compliance.dashboard.batch.model.BatchMetricFocus;
import com.wu.compliance.dashboard.batch.model.BatchStatus;
import com.wu.compliance.dashboard.batch.repository.BatchExplorerRepository;
import com.wu.compliance.dashboard.batch.repository.projection.BatchDetailsProjection;
import com.wu.compliance.dashboard.batch.repository.projection.BatchQueueProjection;
import com.wu.compliance.dashboard.batch.repository.projection.BatchSummaryProjection;
import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.common.exception.ResourceNotFoundException;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchExplorerServiceImplTest {
  private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate TO = LocalDate.of(2026, 9, 30);
  private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 10, 0);
  private BatchExplorerRepository repository;
  private BatchExplorerServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(BatchExplorerRepository.class);
    CountryCatalogSnapshot snapshot = new CountryCatalogSnapshot(List.of(new CountryDefinition("US", "United States", Set.of(1))));
    service = new BatchExplorerServiceImpl(repository, () -> snapshot);
  }

  private static BatchSummaryProjection summary() {
    return new BatchSummaryProjection(10, 6, 4, 1, 1, 1, 1, 1, 1, 1, "RG1");
  }

  private static BatchQueueProjection queueItem(int reportGroupId, long totalIssues, long matching) {
    return new BatchQueueProjection(reportGroupId, "RG", "B1", 1, "f", "t", START, START.plusMinutes(5), 1, 1, false, 1, 1, 1, 1, 1, 1, 1, 1,
        1, totalIssues, matching);
  }

  private static BatchDetailsProjection details(LocalDateTime completedAt, long failures, long missing, long activityMissing, long attempts,
      long transformed) {
    return new BatchDetailsProjection(1, "RG1", "B1", 1, "f", "t", START, completedAt, failures, failures, false, missing, activityMissing,
        1, 1, 1, 1, 1, 1, 1, 1, attempts, transformed, 1, 1, 1, 1, 1, true, true, 7, "tv");
  }

  @Test
  void filterOptionsListTheCatalogCountries() {
    assertThat(service.getFilterOptions().countries()).extracting("code").containsExactly("US");
  }

  @Test
  void batchesForAllCountries() {
    when(repository.getBatchSummary(any(), any(), anyString(), isNull(), anyBoolean(), anyList())).thenReturn(summary());
    when(repository.getBatchQueue(any(), any(), anyString(), isNull(), anyBoolean(), anyList(), anyString(), anyString(), anyString(),
        anyInt(), anyLong()))
      .thenReturn(List.of(queueItem(1, 0, 3), queueItem(99, 2, 3)));
    BatchExplorerResponse response =
        service.getBatches(FROM, TO, BatchStatus.ALL, BatchIssueType.ALL, null, null, null, BatchMetricFocus.DEFAULT, 1, 10);
    assertThat(response.matchingBatches()).isEqualTo(3);
    assertThat(response.country()).isEqualTo("ALL");
    assertThat(response.reportGroupName()).isNull();
    assertThat(response.batches()).hasSize(2);
    assertThat(response.batches().get(0).status()).isEqualTo(BatchStatus.SUCCESSFUL);
    assertThat(response.batches().get(0).countryCode()).isEqualTo("US");
    assertThat(response.batches().get(1).status()).isEqualTo(BatchStatus.ATTENTION);
    assertThat(response.batches().get(1).countryCode()).isEqualTo("UNCONFIGURED");
    verify(repository)
      .getBatchQueue(any(), any(), eq(""), isNull(), eq(false), eq(List.of(-1)), eq("ALL"), eq("ALL"), eq("DEFAULT"), eq(10), eq(10L));
  }

  @Test
  void batchesForACountryAndReportGroup() {
    when(repository.getBatchSummary(any(), any(), anyString(), eq(1), anyBoolean(), anyList())).thenReturn(summary());
    when(repository.getBatchQueue(any(), any(), anyString(), eq(1), anyBoolean(), anyList(), anyString(), anyString(), anyString(), anyInt(),
        anyLong()))
      .thenReturn(List.of());
    BatchExplorerResponse response =
        service.getBatches(FROM, TO, BatchStatus.ATTENTION, BatchIssueType.EXCLUSION, " b1 ", " us ", 1, BatchMetricFocus.EXCLUDED, 0, 5);
    assertThat(response.matchingBatches()).isZero();
    assertThat(response.batchId()).isEqualTo("b1");
    assertThat(response.country()).isEqualTo("US");
    assertThat(response.reportGroupName()).isEqualTo("RG1");
    verify(repository).getBatchSummary(any(), any(), eq("b1"), eq(1), eq(true), eq(List.of(1)));
  }

  @Test
  void batchesRejectBadInput() {
    assertThatThrownBy(() -> service.getBatches(TO, FROM, BatchStatus.ALL, BatchIssueType.ALL, null, null, null, BatchMetricFocus.DEFAULT, 0,
        10))
      .isInstanceOf(InvalidDateRangeException.class);
    assertThatThrownBy(() -> service.getBatches(FROM, TO, BatchStatus.ALL, BatchIssueType.ALL, null, "ZZ", null, BatchMetricFocus.DEFAULT, 0,
        10))
      .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.getBatches(FROM, TO, BatchStatus.ALL, BatchIssueType.ALL, null, "  ", null, BatchMetricFocus.DEFAULT, 0,
        10))
      .isNotInstanceOf(InvalidRequestException.class);
  }

  @Test
  void batchDetailsForACompletedHealthyBatch() {
    when(repository.getBatchDetails(1, "B1", 1)).thenReturn(Optional.of(details(START.plusSeconds(90), 0, 0, 0, 5, 5)));
    BatchDetailsResponse response = service.getBatchDetails(1, "B1", 1);
    assertThat(response.status()).isEqualTo(BatchStatus.SUCCESSFUL);
    assertThat(response.operationalStatus()).isEqualTo("COMPLETED");
    assertThat(response.durationSeconds()).isEqualTo(90);
    assertThat(response.transformationBalanced()).isTrue();
    assertThat(response.totalIssues()).isZero();
  }

  @Test
  void batchDetailsForARunningBatchWithIssues() {
    when(repository.getBatchDetails(1, "B1", 1)).thenReturn(Optional.of(details(null, 1, 1, 1, 5, 5)));
    BatchDetailsResponse response = service.getBatchDetails(1, "B1", 1);
    assertThat(response.status()).isEqualTo(BatchStatus.ATTENTION);
    assertThat(response.operationalStatus()).isEqualTo("RUNNING");
    assertThat(response.durationSeconds()).isZero();
    assertThat(response.transformationBalanced()).isFalse();
    assertThat(response.totalIssues()).isEqualTo(3);
  }

  @Test
  void batchDetailsNeverReportANegativeDuration() {
    when(repository.getBatchDetails(1, "B1", 1)).thenReturn(Optional.of(details(START.minusMinutes(1), 0, 0, 0, 1, 1)));
    assertThat(service.getBatchDetails(1, "B1", 1).durationSeconds()).isZero();
  }

  @Test
  void unknownBatchIsNotFound() {
    when(repository.getBatchDetails(1, "B1", 1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getBatchDetails(1, "B1", 1)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void debugLoggingDoesNotChangeResults() {
    try (DebugLogging ignored = DebugLogging.enable()) {
      when(repository.getBatchSummary(any(), any(), anyString(), isNull(), anyBoolean(), anyList())).thenReturn(summary());
      when(repository.getBatchQueue(any(), any(), anyString(), isNull(), anyBoolean(), anyList(), anyString(), anyString(), anyString(),
          anyInt(), anyLong()))
        .thenReturn(List.of(queueItem(1, 0, 3)));
      assertThat(service
        .getBatches(FROM, TO, BatchStatus.ALL, BatchIssueType.ALL, "B", null, null, BatchMetricFocus.DEFAULT, 0, 10)
        .batches())
        .hasSize(1);
      when(repository.getBatchDetails(1, "B1", 1)).thenReturn(Optional.of(details(START.plusSeconds(1), 0, 0, 0, 1, 1)));
      assertThat(service.getBatchDetails(1, "B1", 1).batchId()).isEqualTo("B1");
    }
  }
}
