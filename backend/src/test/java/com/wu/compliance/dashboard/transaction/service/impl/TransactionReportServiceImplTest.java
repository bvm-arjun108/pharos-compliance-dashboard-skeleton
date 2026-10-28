package com.wu.compliance.dashboard.transaction.service.impl;

import com.wu.compliance.dashboard.testsupport.DebugLogging;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.exception.InvalidDateRangeException;
import com.wu.compliance.dashboard.common.exception.InvalidRequestException;
import com.wu.compliance.dashboard.common.exception.ResourceNotFoundException;
import com.wu.compliance.dashboard.reportgroup.model.CountryCatalogSnapshot;
import com.wu.compliance.dashboard.reportgroup.model.CountryDefinition;
import com.wu.compliance.dashboard.reportgroup.service.CountryCatalog;
import com.wu.compliance.dashboard.transaction.dto.PeriodTransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionEvidenceDetailResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionSearchResponse;
import com.wu.compliance.dashboard.transaction.model.EvidenceCursor;
import com.wu.compliance.dashboard.transaction.model.TransactionDetailScope;
import com.wu.compliance.dashboard.transaction.model.TransactionEvidenceLevel;
import com.wu.compliance.dashboard.transaction.model.TransactionEvidenceSource;
import com.wu.compliance.dashboard.transaction.model.TransactionMetric;
import com.wu.compliance.dashboard.transaction.model.TransactionOutcome;
import com.wu.compliance.dashboard.transaction.model.TransactionSearchField;
import com.wu.compliance.dashboard.transaction.model.TransactionSortDirection;
import com.wu.compliance.dashboard.transaction.model.TransactionStage;
import com.wu.compliance.dashboard.transaction.model.TransactionStatus;
import com.wu.compliance.dashboard.transaction.repository.TransactionEvidenceCache;
import com.wu.compliance.dashboard.transaction.repository.TransactionSearchRepository;
import com.wu.compliance.dashboard.transaction.repository.projection.EvidencePage;
import com.wu.compliance.dashboard.transaction.repository.projection.PeriodAggregateProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionEvidenceProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionReportContextProjection;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionSearchResultProjection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransactionReportServiceImplTest {
  private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate TO = LocalDate.of(2026, 9, 30);
  private TransactionEvidenceCache cache;
  private TransactionSearchRepository searchRepository;
  private TransactionReportServiceImpl service;

  @BeforeEach
  void setUp() {
    cache = mock(TransactionEvidenceCache.class);
    searchRepository = mock(TransactionSearchRepository.class);
    CountryCatalog catalog =
        () -> new CountryCatalogSnapshot(List.of(new CountryDefinition("US", "United States", Set.of(1, 2)),
            new CountryDefinition("CA", "Canada", Set.of(3))));
    service = new TransactionReportServiceImpl(cache, catalog, searchRepository);
  }

  private static TransactionReportContextProjection context() {
    return new TransactionReportContextProjection(1, "RG1", "B1", 1, "2026-09-01", "2026-09-30", 10, 9, 8, 7, 6, 5, 4, 3, 2, true, 11, 12,
        13, 14, 15, 16, 17, 18);
  }

  private static TransactionEvidenceProjection evidence(String source, String outcome) {
    return TransactionEvidenceProjection.list("rk", "id", "mtcn", "B1", source, "stage", "status", outcome, "c", "s", "e", "rb", "m", true);
  }

  private void stubBatchReport(long matching, long available, EvidencePage page) {
    when(cache.findReportContext(1, "B1", 1)).thenReturn(Optional.of(context()));
    when(cache.findEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        anyString(), anyInt(), anyLong(), any()))
      .thenReturn(page);
    when(cache.countEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
      .thenReturn(matching, available);
  }

  private TransactionReportResponse batchReport(TransactionMetric metric, String search, TransactionEvidenceSource source,
      TransactionStage stage, TransactionOutcome outcome, TransactionStatus status, String cursor) {
    return service.getTransactionReport(1, "B1", 1, metric, search, source, stage, outcome, status, TransactionSortDirection.ASC, 0, 25,
        cursor);
  }

  private TransactionReportResponse defaultBatchReport(TransactionMetric metric) {
    return batchReport(metric, null, TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL, TransactionStatus.ALL,
        null);
  }

  @ParameterizedTest
  @EnumSource(TransactionMetric.class)
  void everyMetricHasALabelAndAnAggregateCount(TransactionMetric metric) {
    stubBatchReport(5, 5, new EvidencePage(List.of(evidence("JOURNEY", "SUCCESS")), "next"));
    TransactionReportResponse response = defaultBatchReport(metric);
    assertThat(response.metricLabel()).isNotBlank();
    assertThat(response.context().batchId()).isEqualTo("B1");
    assertThat(response.context().countryCode()).isEqualTo("US");
  }

  @Test
  void aggregateCountsFollowTheMetric() {
    stubBatchReport(5, 5, new EvidencePage(List.of(), null));
    assertThat(defaultBatchReport(TransactionMetric.ALL).aggregateCount()).isEqualTo(10);
    assertThat(defaultBatchReport(TransactionMetric.ATTEMPTS_FOUND).aggregateCount()).isEqualTo(9);
    assertThat(defaultBatchReport(TransactionMetric.MISSING).aggregateCount()).isEqualTo(8);
    assertThat(defaultBatchReport(TransactionMetric.ACTIVITY_MISSING).aggregateCount()).isEqualTo(7);
    assertThat(defaultBatchReport(TransactionMetric.EXPECTED_ELIGIBLE).aggregateCount()).isEqualTo(6);
    assertThat(defaultBatchReport(TransactionMetric.ACTUAL_ELIGIBLE).aggregateCount()).isEqualTo(5);
    assertThat(defaultBatchReport(TransactionMetric.TRANSFORMED).aggregateCount()).isEqualTo(4);
    assertThat(defaultBatchReport(TransactionMetric.FAILED).aggregateCount()).isEqualTo(3);
    assertThat(defaultBatchReport(TransactionMetric.EXPECTED_REPORTABLE).aggregateCount()).isEqualTo(11);
    assertThat(defaultBatchReport(TransactionMetric.TRANSFORMER_OUTPUT).aggregateCount()).isEqualTo(12);
    assertThat(defaultBatchReport(TransactionMetric.EXCLUDED).aggregateCount()).isEqualTo(13);
    assertThat(defaultBatchReport(TransactionMetric.SIMULATED).aggregateCount()).isEqualTo(14);
    assertThat(defaultBatchReport(TransactionMetric.ALREADY_REPORTED).aggregateCount()).isEqualTo(15);
    assertThat(defaultBatchReport(TransactionMetric.SOFT_DEDUP).aggregateCount()).isEqualTo(16);
    assertThat(defaultBatchReport(TransactionMetric.FILTERED).aggregateCount()).isEqualTo(8 + 7 + 13 + 14 + 15 + 16);
    assertThat(defaultBatchReport(TransactionMetric.SKIPPED).aggregateCount()).isEqualTo(8 + 7 + 3);
  }

  @Test
  void failedAndSkippedUseTheReportedFailedCountAndFlagAMismatch() {
    stubBatchReport(5, 5, new EvidencePage(List.of(), null));
    TransactionReportResponse failed = defaultBatchReport(TransactionMetric.FAILED);
    assertThat(failed.reportedAggregateCount()).isEqualTo(2);
    assertThat(failed.aggregateCountMismatch()).isTrue();
    TransactionReportResponse skipped = defaultBatchReport(TransactionMetric.SKIPPED);
    assertThat(skipped.reportedAggregateCount()).isEqualTo(8 + 7 + 2);
    assertThat(skipped.aggregateCountMismatch()).isTrue();
    TransactionReportResponse selected = defaultBatchReport(TransactionMetric.SELECTED);
    assertThat(selected.reportedAggregateCount()).isEqualTo(10);
    assertThat(selected.aggregateCountMismatch()).isFalse();
  }

  @Test
  void aggregateOnlyMetricsSkipTheEvidencePipeline() {
    when(cache.findReportContext(1, "B1", 1)).thenReturn(Optional.of(context()));
    TransactionReportResponse variance = defaultBatchReport(TransactionMetric.FILTRATION_VARIANCE);
    assertThat(variance.aggregateCount()).isEqualTo(17);
    assertThat(variance.transactions()).isEmpty();
    assertThat(variance.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.AGGREGATE_ONLY);
    assertThat(variance.evidenceMessage()).contains("17").contains("batch reconciliation evidence");
    TransactionReportResponse reconciliation = defaultBatchReport(TransactionMetric.RECONCILIATION_VARIANCE);
    assertThat(reconciliation.aggregateCount()).isEqualTo(18);
  }

  @Test
  void aMissingBatchIsNotFound() {
    when(cache.findReportContext(1, "B1", 1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> defaultBatchReport(TransactionMetric.ALL)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void aMalformedCursorIsAClientError() {
    assertThatThrownBy(() -> batchReport(TransactionMetric.ALL, null, TransactionEvidenceSource.ALL, TransactionStage.ALL,
        TransactionOutcome.ALL, TransactionStatus.ALL, "***not-base64***"))
      .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void aValidCursorIsDecodedAndForwarded() {
    stubBatchReport(10, 10, new EvidencePage(List.of(evidence("JOURNEY", "SUCCESS")), null));
    String token = Base64.getUrlEncoder().encodeToString("2026-09-01T00:00:00Z|rk-1".getBytes());
    batchReport(TransactionMetric.ALL, "  x ", TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL,
        TransactionStatus.ALL, token);
    verify(cache)
      .findEvidenceRecords(eq(1), eq("B1"), eq("ALL"), eq("x"), eq("ALL"), eq("ALL"), eq("ALL"), eq("ALL"), eq("ASC"), eq(25), eq(0L),
          any(EvidenceCursor.class));
  }

  @Test
  void filteredReportsCountAvailableRecordsSeparately() {
    stubBatchReport(3, 10, new EvidencePage(List.of(evidence("EXCLUSION_AUDIT", "EXCLUDED")), null));
    TransactionReportResponse response = batchReport(TransactionMetric.ALL, "abc", TransactionEvidenceSource.ALL, TransactionStage.ALL,
        TransactionOutcome.ALL, TransactionStatus.ALL, null);
    assertThat(response.matchingRecordCount()).isEqualTo(3);
    assertThat(response.availableRecordCount()).isEqualTo(10);
    assertThat(response.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.RECORD_LEVEL);
    assertThat(response.evidenceMessage()).contains("Record-level");
    assertThat(response.transactions()).hasSize(1);
    assertThat(response.transactions().get(0).source()).isEqualTo(TransactionEvidenceSource.EXCLUSION_AUDIT);
  }

  @Test
  void nonDefaultFiltersEachTriggerTheSeparateAvailableCount() {
    stubBatchReport(3, 10, new EvidencePage(List.of(), null));
    batchReport(TransactionMetric.ALL, null, TransactionEvidenceSource.JOURNEY, TransactionStage.ALL, TransactionOutcome.ALL,
        TransactionStatus.ALL, null);
    batchReport(TransactionMetric.ALL, null, TransactionEvidenceSource.ALL, TransactionStage.valueOf(secondStage()), TransactionOutcome.ALL,
        TransactionStatus.ALL, null);
    batchReport(TransactionMetric.ALL, null, TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.EXCLUDED,
        TransactionStatus.ALL, null);
    batchReport(TransactionMetric.ALL, null, TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL,
        TransactionStatus.FAILED, null);
  }

  private static String secondStage() {
    return TransactionStage.values()[1].name();
  }

  @Test
  void evidenceLevelCoversEveryOutcome() {
    when(cache.findReportContext(1, "B1", 1)).thenReturn(Optional.of(context()));
    when(cache.findEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        anyString(), anyInt(), anyLong(), any()))
      .thenReturn(new EvidencePage(List.of(), null));
    // aggregate 10 (ALL), matching 0 => AGGREGATE_ONLY
    when(cache.countEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
      .thenReturn(0L);
    assertThat(defaultBatchReport(TransactionMetric.ALL).evidenceLevel()).isEqualTo(TransactionEvidenceLevel.AGGREGATE_ONLY);
    // aggregate 10, available 7 => PARTIAL
    when(cache.countEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
      .thenReturn(7L);
    TransactionReportResponse partial = defaultBatchReport(TransactionMetric.ALL);
    assertThat(partial.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.PARTIAL_RECORD_LEVEL);
    assertThat(partial.evidenceMessage()).contains("7 record(s)").contains("10");
    // aggregate 0 (no zero metric in fixture) -> use a zeroed context
    when(cache.findReportContext(1, "B1", 1))
      .thenReturn(Optional.of(
          new TransactionReportContextProjection(1, "RG1", "B1", 1, "a", "b", 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0)));
    when(cache.countEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
      .thenReturn(0L);
    TransactionReportResponse none = defaultBatchReport(TransactionMetric.ALL);
    assertThat(none.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.NO_RECORDS);
    assertThat(none.evidenceMessage()).contains("no transactions");
    // aggregate 0 but records exist => PARTIAL (aggregate not > 0)
    when(cache.countEvidenceRecords(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
      .thenReturn(4L);
    assertThat(defaultBatchReport(TransactionMetric.ALL).evidenceLevel()).isEqualTo(TransactionEvidenceLevel.PARTIAL_RECORD_LEVEL);
  }

  // --- period report ---
  private void stubPeriod(long matching, long totalExcluded, String nextCursor) {
    when(cache.findPeriodAggregate(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt(), anyString()))
      .thenReturn(new PeriodAggregateProjection(4, totalExcluded, "RG1"));
    when(cache.findPeriodEvidenceRecords(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyBoolean(), anyString(), anyInt(), anyLong(), any()))
      .thenReturn(new EvidencePage(List.of(evidence("JOURNEY", "SUCCESS")), nextCursor));
    when(cache.countPeriodEvidenceRecords(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyBoolean()))
      .thenReturn(matching);
  }

  private PeriodTransactionReportResponse period(String country, Integer reportGroupId, TransactionStatus status, boolean batchScoped) {
    return service.getPeriodTransactionReport(FROM, TO, country, reportGroupId, " B1 ", " q ", TransactionOutcome.ALL, status, " r ",
        batchScoped, TransactionSortDirection.DESC, 0, 25, null);
  }

  @Test
  void periodReportForAllCountriesResolvesTheAllDefinition() {
    stubPeriod(5, 9, null);
    PeriodTransactionReportResponse response = period(null, null, TransactionStatus.ALL, false);
    assertThat(response.context().countryCode()).isEqualTo("ALL");
    assertThat(response.context().reportGroupName()).isNull();
    assertThat(response.aggregateCount()).isEqualTo(5);
    assertThat(response.metricLabel()).isEqualTo("All transactions");
    assertThat(response.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.RECORD_LEVEL);
    assertThat(response.nextCursor()).isNull();
  }

  @Test
  void periodReportForACountryUsesItsReportGroups() {
    stubPeriod(5, 9, "n");
    PeriodTransactionReportResponse response = period(" us ", null, TransactionStatus.SUCCESS, false);
    assertThat(response.context().countryCode()).isEqualTo("US");
    assertThat(response.nextCursor()).isEqualTo("n");
    verify(cache)
      .findPeriodAggregate(any(), any(), eq(true), argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(1, 2))), eq(false), eq(-1),
          eq("B1"));
  }

  @Test
  void periodReportForAReportGroupIgnoresTheCountryFilter() {
    stubPeriod(5, 9, null);
    PeriodTransactionReportResponse response = period("CA", 3, TransactionStatus.FAILED, false);
    assertThat(response.context().reportGroupName()).isEqualTo("RG1");
    assertThat(response.context().countryCode()).isEqualTo("CA");
    verify(cache).findPeriodAggregate(any(), any(), eq(false), eq(List.of(-1)), eq(true), eq(3), eq("B1"));
  }

  @Test
  void batchScopedExcludedComparesAgainstTheReconciliationTotal() {
    stubPeriod(5, 9, null);
    PeriodTransactionReportResponse scoped = period("ALL", null, TransactionStatus.EXCLUDED, true);
    assertThat(scoped.aggregateCount()).isEqualTo(9);
    assertThat(scoped.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.PARTIAL_RECORD_LEVEL);
    PeriodTransactionReportResponse unscoped = period("ALL", null, TransactionStatus.EXCLUDED, false);
    assertThat(unscoped.aggregateCount()).isEqualTo(5);
    PeriodTransactionReportResponse reportedScoped = period("ALL", null, TransactionStatus.REPORTED, true);
    assertThat(reportedScoped.aggregateCount()).isEqualTo(5);
  }

  @ParameterizedTest
  @EnumSource(TransactionStatus.class)
  void everyStatusHasAPeriodLabel(TransactionStatus status) {
    stubPeriod(0, 0, null);
    PeriodTransactionReportResponse response = period("ALL", null, status, false);
    assertThat(response.metricLabel()).isNotBlank();
    assertThat(response.evidenceLevel()).isEqualTo(TransactionEvidenceLevel.NO_RECORDS);
  }

  @Test
  void periodReportValidatesItsInputs() {
    assertThatThrownBy(() -> service.getPeriodTransactionReport(TO, FROM, "ALL", null, null, null, TransactionOutcome.ALL,
        TransactionStatus.ALL, null, false, TransactionSortDirection.ASC, 0, 25, null))
      .isInstanceOf(InvalidDateRangeException.class);
    assertThatThrownBy(() -> period("ZZ", null, TransactionStatus.ALL, false))
      .isInstanceOf(InvalidRequestException.class)
      .hasMessageContaining("ZZ");
    assertThatThrownBy(() -> service.getPeriodTransactionReport(FROM, TO, "ALL", null, null, null, TransactionOutcome.ALL,
        TransactionStatus.ALL, null, false, TransactionSortDirection.ASC, 0, 25, "***"))
      .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void periodReportAcceptsNullOptionalTextFilters() {
    stubPeriod(1, 1, null);
    PeriodTransactionReportResponse response = service.getPeriodTransactionReport(FROM, TO, "  ", null, null, null, TransactionOutcome.ALL,
        TransactionStatus.ALL, null, false, TransactionSortDirection.ASC, 0, 25, null);
    assertThat(response.search()).isEmpty();
  }

  // --- batch ids ---
  @Test
  void periodBatchIdsAreResolvedWithTheSameScope() {
    when(cache.findPeriodBatchIds(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of("B1", "B2"));
    assertThat(service.getPeriodReportBatchIds(FROM, TO, "CA", null)).containsExactly("B1", "B2");
    assertThat(service.getPeriodReportBatchIds(FROM, TO, null, 3)).containsExactly("B1", "B2");
    assertThatThrownBy(() -> service.getPeriodReportBatchIds(TO, FROM, "ALL", null)).isInstanceOf(InvalidDateRangeException.class);
  }

  // --- search ---
  private static TransactionSearchResultProjection match() {
    return new TransactionSearchResultProjection(1, "RG1", "US", "United States", "B1", "JOURNEY", "S", "OK", "c", "MTCN", "t", "m", "i");
  }

  @Test
  void unscopedSearchUsesTheOriginalRepositoryPath() {
    when(searchRepository.search(TransactionSearchField.MTCN, "123")).thenReturn(List.of(match()));
    TransactionSearchResponse response = service.searchTransactions(TransactionSearchField.MTCN, " 123 ", null, null, null, null);
    assertThat(response.query()).isEqualTo("123");
    assertThat(response.results()).hasSize(1);
    assertThat(response.results().get(0).identifier()).isEqualTo("i");
  }

  @Test
  void scopedSearchBuildsTheScopeFromTheFilters() {
    when(searchRepository.search(eq(TransactionSearchField.EXTERNAL_TXN_ID), eq("9"), any(TransactionSearchRepository.SearchScope.class)))
      .thenReturn(List.of());
    service.searchTransactions(TransactionSearchField.EXTERNAL_TXN_ID, "9", FROM, TO, "US", null);
    service.searchTransactions(TransactionSearchField.EXTERNAL_TXN_ID, "9", FROM, TO, null, 3);
    var captor = org.mockito.ArgumentCaptor.forClass(TransactionSearchRepository.SearchScope.class);
    verify(searchRepository, org.mockito.Mockito.times(2)).search(eq(TransactionSearchField.EXTERNAL_TXN_ID), eq("9"), captor.capture());
    TransactionSearchRepository.SearchScope byCountry = captor.getAllValues().get(0);
    assertThat(byCountry.filterByCountry()).isTrue();
    assertThat(byCountry.filterByReportGroup()).isFalse();
    assertThat(byCountry.reportGroupId()).isEqualTo(-1);
    assertThat(byCountry.toTimestampExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    TransactionSearchRepository.SearchScope byGroup = captor.getAllValues().get(1);
    assertThat(byGroup.filterByReportGroup()).isTrue();
    assertThat(byGroup.reportGroupId()).isEqualTo(3);
  }

  @Test
  void searchValidatesItsInputs() {
    assertThatThrownBy(() -> service.searchTransactions(TransactionSearchField.MTCN, "1", FROM, null, null, null))
      .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.searchTransactions(TransactionSearchField.MTCN, "1", null, TO, null, null))
      .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.searchTransactions(TransactionSearchField.MTCN, "1", TO, FROM, null, null))
      .isInstanceOf(InvalidDateRangeException.class);
    assertThatThrownBy(() -> service.searchTransactions(TransactionSearchField.MTCN, "   ", null, null, null, null))
      .isInstanceOf(InvalidRequestException.class);
  }

  // --- detail ---
  private TransactionEvidenceProjection detailProjection() {
    return new TransactionEvidenceProjection("rk", "id", "mtcn", "B1", "JOURNEY", "st", "s", "SUCCESS", "c", "sk", "r", "e", "es", "rb",
        "rt", "m", true, BigDecimal.TEN, "USD", "d", "S", "src", "act", "sd", "g", 1, 2L, "sn", "rn", "sc", "scn", "sp", "sdob", "sit",
        "sin", "rc", "rcn", "rp", "rdob", "rit", "rin", "ts", "tss", "[]");
  }

  @Test
  void batchScopedDetailIsResolvedByTheExplicitScope() {
    when(cache.findBatchEvidenceDetail(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        any()))
      .thenReturn(Optional.of(detailProjection()));
    TransactionEvidenceDetailResponse response = service.getTransactionDetail(TransactionDetailScope.BATCH, 1, "B1", " id ", null, null,
        null, TransactionMetric.ALL, TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL, TransactionStatus.ALL,
        null, false, null, "rk");
    assertThat(response.identifier()).isEqualTo("id");
    assertThat(response.ruleHitsJson()).isEqualTo("[]");
  }

  @Test
  void periodScopedDetailIsResolvedWithTheSameCountryRules() {
    when(cache.findPeriodEvidenceDetail(any(), any(), anyBoolean(), anyList(), anyBoolean(), anyInt(), any(), anyString(), anyString(),
        anyString(), anyString(), anyBoolean(), anyString(), any()))
      .thenReturn(Optional.of(detailProjection()));
    assertThat(service.getTransactionDetail(TransactionDetailScope.PERIOD, null, "B1", "id", FROM, TO, "US", TransactionMetric.ALL,
        TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL, TransactionStatus.ALL, " r ", true, " bf ", "rk"))
      .isNotNull();
    assertThat(service.getTransactionDetail(TransactionDetailScope.PERIOD, 3, "B1", "id", FROM, TO, null, TransactionMetric.ALL,
        TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL, TransactionStatus.ALL, null, false, null, "rk"))
      .isNotNull();
  }

  @Test
  void detailValidatesItsInputs() {
    var all = new Object[] {TransactionMetric.ALL, TransactionEvidenceSource.ALL, TransactionStage.ALL,
        TransactionOutcome.ALL, TransactionStatus.ALL};
    assertThatThrownBy(() -> detail(TransactionDetailScope.BATCH, 1, " ", FROM, TO)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> detail(TransactionDetailScope.BATCH, 1, null, FROM, TO)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> detail(TransactionDetailScope.BATCH, null, "id", FROM, TO)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> detail(TransactionDetailScope.PERIOD, 1, "id", null, TO)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> detail(TransactionDetailScope.PERIOD, 1, "id", FROM, null)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> detail(TransactionDetailScope.PERIOD, 1, "id", TO, FROM)).isInstanceOf(InvalidDateRangeException.class);
    assertThat(all).hasSize(5);
  }

  @Test
  void detailNotFoundIs404() {
    when(cache.findBatchEvidenceDetail(anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        any()))
      .thenReturn(Optional.empty());
    assertThatThrownBy(() -> detail(TransactionDetailScope.BATCH, 1, "id", FROM, TO)).isInstanceOf(ResourceNotFoundException.class);
  }

  private TransactionEvidenceDetailResponse detail(TransactionDetailScope scope, Integer reportGroupId, String identifier, LocalDate from,
      LocalDate to) {
    return service.getTransactionDetail(scope, reportGroupId, "B1", identifier, from, to, null, TransactionMetric.ALL,
        TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL, TransactionStatus.ALL, null, false, null, "rk");
  }

  @Test
  void debugLoggingDoesNotChangeResults() {
    try (DebugLogging ignored = DebugLogging.enable()) {
      stubBatchReport(5, 5, new EvidencePage(List.of(evidence("JOURNEY", "SUCCESS")), "next"));
      assertThat(defaultBatchReport(TransactionMetric.ALL).nextCursor()).isEqualTo("next");
      stubPeriod(5, 9, null);
      assertThat(period("ALL", null, TransactionStatus.ALL, false).transactions()).hasSize(1);
      String token = Base64.getUrlEncoder().encodeToString("2026-09-01T00:00:00Z|rk-1".getBytes());
      batchReport(TransactionMetric.ALL, "term", TransactionEvidenceSource.ALL, TransactionStage.ALL, TransactionOutcome.ALL,
          TransactionStatus.ALL, token);
      service.getPeriodTransactionReport(FROM, TO, "ALL", 1, "B", "term", TransactionOutcome.ALL, TransactionStatus.ALL, "reason", false,
          TransactionSortDirection.ASC, 0, 25, token);
      when(searchRepository.search(TransactionSearchField.MTCN, "1")).thenReturn(List.of());
      assertThat(service.searchTransactions(TransactionSearchField.MTCN, "1", null, null, null, null).results()).isEmpty();
      assertThat(service.searchTransactions(TransactionSearchField.MTCN, "1", FROM, TO, "US", 1).results()).isEmpty();
    }
  }
}
