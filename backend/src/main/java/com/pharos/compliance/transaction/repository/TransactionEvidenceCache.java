package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.config.CacheConfiguration;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionOutcomeBreakdownProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionReportContextProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionStageBreakdownProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Caches the transaction evidence report's read queries. This is a plain {@code @Component}, not
 * annotations on {@link TransactionReportRepository} directly, because Spring Data repository
 * proxies are built by a {@code FactoryBean} that bypasses the normal AOP auto-proxy pipeline —
 * {@code @Cacheable} on a repository interface method is silently never invoked (only Spring Data's
 * own built-in {@code @Transactional} support gets wired into that proxy). A real bean like this
 * one goes through standard bean post-processing, so {@code @Cacheable} works as expected.
 */
@Component
public class TransactionEvidenceCache {

  private final TransactionReportRepository repository;

  public TransactionEvidenceCache(TransactionReportRepository repository) {
    this.repository = repository;
  }

  @Cacheable(
      cacheNames = CacheConfiguration.TRANSACTION_REPORT_CONTEXT,
      key = "#reportGroupId + ':' + #batchId + ':' + #sequenceNumber")
  public Optional<TransactionReportContextProjection> findReportContext(
      int reportGroupId, String batchId, int sequenceNumber) {
    return repository.findReportContext(reportGroupId, batchId, sequenceNumber);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_RECORDS,
      key =
          "#reportGroupId + ':' + #batchId + ':' + #metric + ':' + #search + ':' + #source"
              + " + ':' + #stage + ':' + #outcome + ':' + #status + ':' + #sortDirection + ':'"
              + " + #size + ':' + #offset")
  public List<TransactionEvidenceProjection> findEvidenceRecords(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String stage,
      String outcome,
      String status,
      String sortDirection,
      int size,
      long offset) {
    return repository.findEvidenceRecords(
        reportGroupId, batchId, metric, search, source, stage, outcome, status, sortDirection,
        size, offset);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_COUNT,
      key =
          "#reportGroupId + ':' + #batchId + ':' + #metric + ':' + #search + ':' + #source"
              + " + ':' + #stage + ':' + #outcome + ':' + #status")
  public long countEvidenceRecords(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String stage,
      String outcome,
      String status) {
    return repository.countEvidenceRecords(
        reportGroupId, batchId, metric, search, source, stage, outcome, status);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.TRANSACTION_OUTCOME_BREAKDOWN,
      key =
          "#reportGroupId + ':' + #batchId + ':' + #metric + ':' + #search + ':' + #source"
              + " + ':' + #stage")
  public TransactionOutcomeBreakdownProjection findOutcomeBreakdown(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String stage) {
    return repository.findOutcomeBreakdown(reportGroupId, batchId, metric, search, source, stage);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.TRANSACTION_STAGE_BREAKDOWN,
      key =
          "#reportGroupId + ':' + #batchId + ':' + #metric + ':' + #search + ':' + #source"
              + " + ':' + #outcome")
  public List<TransactionStageBreakdownProjection> findStageBreakdown(
      int reportGroupId,
      String batchId,
      String metric,
      String search,
      String source,
      String outcome) {
    return repository.findStageBreakdown(reportGroupId, batchId, metric, search, source, outcome);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.PERIOD_TRANSACTION_AGGREGATE,
      key =
          "#fromTimestamp + ':' + #toTimestampExclusive + ':' + #filterByCountry + ':'"
              + " + #reportGroupIds + ':' + #filterByReportGroup + ':' + #reportGroupId")
  public PeriodAggregateProjection findPeriodAggregate(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId) {
    return repository.findPeriodAggregate(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_RECORDS,
      key =
          "#fromTimestamp + ':' + #toTimestampExclusive + ':' + #filterByCountry + ':'"
              + " + #reportGroupIds + ':' + #filterByReportGroup + ':' + #reportGroupId + ':'"
              + " + #search + ':' + #outcome + ':' + #status + ':' + #sortDirection + ':'"
              + " + #size + ':' + #offset")
  public List<TransactionEvidenceProjection> findPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String search,
      String outcome,
      String status,
      String sortDirection,
      int size,
      long offset) {
    return repository.findPeriodEvidenceRecords(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, search, outcome, status, sortDirection, size, offset);
  }

  @Cacheable(
      cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_COUNT,
      key =
          "#fromTimestamp + ':' + #toTimestampExclusive + ':' + #filterByCountry + ':'"
              + " + #reportGroupIds + ':' + #filterByReportGroup + ':' + #reportGroupId + ':'"
              + " + #search + ':' + #outcome + ':' + #status")
  public long countPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String search,
      String outcome,
      String status) {
    return repository.countPeriodEvidenceRecords(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, search, outcome, status);
  }
}
