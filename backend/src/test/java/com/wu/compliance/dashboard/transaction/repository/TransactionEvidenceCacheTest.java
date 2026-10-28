package com.wu.compliance.dashboard.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.transaction.repository.evidence.EvidenceProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.EvidencePage;
import com.wu.compliance.dashboard.transaction.repository.projection.PeriodAggregateProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionEvidenceProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The cache component is a pure pass-through (the caching itself is Spring's proxy); this proves
 * each method forwards every argument, in order, to the repository.
 */
class TransactionEvidenceCacheTest {
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 1, 0, 0);
  private final TransactionReportRepository repository = mock(TransactionReportRepository.class);
  private final TransactionEvidenceCache cache = new TransactionEvidenceCache(repository);

  @Test
  void batchMethodsDelegate() {
    EvidencePage page = new EvidencePage(List.of(), null);
    when(repository.findReportContext(1, "B", 2)).thenReturn(Optional.empty());
    when(repository.findEvidenceRecords(1, "B", "ALL", "s", "ALL", "ALL", "ALL", "ALL", "ASC", 10, 5L, null)).thenReturn(page);
    when(repository.countEvidenceRecords(1, "B", "ALL", "s", "ALL", "ALL", "ALL", "ALL")).thenReturn(7L);
    TransactionEvidenceProjection row =
        TransactionEvidenceProjection.list("rk", "id", "m", "B", "JOURNEY", "s", "st", "SUCCESS", null, null, null, null, null, true);
    when(repository.findBatchEvidenceDetail(1, "B", "id", "ALL", "ALL", "ALL", "ALL", "ALL", "rk")).thenReturn(Optional.of(row));

    assertThat(cache.findReportContext(1, "B", 2)).isEmpty();
    assertThat(cache.findEvidenceRecords(1, "B", "ALL", "s", "ALL", "ALL", "ALL", "ALL", "ASC", 10, 5L, null)).isSameAs(page);
    assertThat(cache.countEvidenceRecords(1, "B", "ALL", "s", "ALL", "ALL", "ALL", "ALL")).isEqualTo(7L);
    assertThat(cache.findBatchEvidenceDetail(1, "B", "id", "ALL", "ALL", "ALL", "ALL", "ALL", "rk")).contains(row);
  }

  @Test
  void periodMethodsDelegate() {
    EvidencePage page = new EvidencePage(List.of(), "n");
    PeriodAggregateProjection aggregate = new PeriodAggregateProjection(1, 2, "RG");
    when(repository.findPeriodAggregate(FROM, TO, true, List.of(1), false, -1, "b")).thenReturn(aggregate);
    when(repository.findPeriodEvidenceRecords(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyBoolean(), anyString(), anyInt(), anyLong(), any(), any(EvidenceProjection.class)))
      .thenReturn(page);
    when(repository.countPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "b", "q", "ALL", "ALL", "r", false)).thenReturn(3L);
    when(repository.findPeriodBatchIds(FROM, TO, true, List.of(1), false, -1)).thenReturn(List.of("B1"));
    when(repository.findPeriodEvidenceDetail(FROM, TO, true, List.of(1), false, -1, "B", "id", "ALL", "ALL", "r", false, "f", "rk"))
      .thenReturn(Optional.empty());

    assertThat(cache.findPeriodAggregate(FROM, TO, true, List.of(1), false, -1, "b")).isSameAs(aggregate);
    assertThat(cache.findPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "b", "q", "ALL", "ALL", "r", false, "ASC", 10, 0L,
        null))
      .isSameAs(page);
    assertThat(cache.countPeriodEvidenceRecords(FROM, TO, true, List.of(1), false, -1, "b", "q", "ALL", "ALL", "r", false)).isEqualTo(3L);
    assertThat(cache.findPeriodBatchIds(FROM, TO, true, List.of(1), false, -1)).containsExactly("B1");
    assertThat(cache.findPeriodEvidenceDetail(FROM, TO, true, List.of(1), false, -1, "B", "id", "ALL", "ALL", "r", false, "f", "rk")).isEmpty();
    verify(repository).findPeriodBatchIds(FROM, TO, true, List.of(1), false, -1);
  }
}
