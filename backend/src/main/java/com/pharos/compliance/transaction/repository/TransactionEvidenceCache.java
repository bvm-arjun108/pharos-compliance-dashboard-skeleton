package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.config.CacheConfiguration;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionRecordDetailProjection;
import com.pharos.compliance.transaction.repository.TransactionReportRepository.TransactionReportContextProjection;
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
 *
 * <p>None of the methods below specify an explicit {@code key} — Spring's default {@code
 * SimpleKeyGenerator} hashes the full parameter list automatically. That's deliberate: these
 * methods have grown to 6-12 parameters each as filters were added over time, and a hand-written
 * SpEL key string has to be manually kept in sync with that list. Miss one when adding a new
 * parameter and two genuinely different queries silently collide on the same cache entry, each
 * serving the other's stale result — a correctness bug with no compiler or test signal. Letting
 * the framework derive the key from every argument removes that failure mode entirely; every
 * parameter here is a primitive, String, LocalDateTime, or List<Integer>, all of which already
 * have correct value-based equals/hashCode, so this is behavior-preserving.
 */
@Component
public class TransactionEvidenceCache {

  private final TransactionReportRepository repository;

  public TransactionEvidenceCache(TransactionReportRepository repository) {
    this.repository = repository;
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_REPORT_CONTEXT)
  public Optional<TransactionReportContextProjection> findReportContext(
      int reportGroupId, String batchId, int sequenceNumber) {
    return repository.findReportContext(reportGroupId, batchId, sequenceNumber);
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_RECORDS)
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

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_RECORD_DETAIL)
  public Optional<TransactionRecordDetailProjection> findRecordDetail(
      int reportGroupId, String batchId, String identifier, String status, String metric) {
    // source/search are fixed: this is a lookup of one known identifier, so neither the evidence
    // source filter nor the free-text search can change which row it resolves to.
    return repository.findRecordDetail(reportGroupId, batchId, identifier, status, metric, "ALL", "");
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_COUNT)
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

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_AGGREGATE)
  public PeriodAggregateProjection findPeriodAggregate(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String batchId) {
    return repository.findPeriodAggregate(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, batchId);
  }

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_RECORDS)
  public List<TransactionEvidenceProjection> findPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String batchId,
      String search,
      String outcome,
      String status,
      String sortDirection,
      int size,
      long offset) {
    return repository.findPeriodEvidenceRecords(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, batchId, search, outcome, status, sortDirection, size, offset);
  }

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_COUNT)
  public long countPeriodEvidenceRecords(
      LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive,
      boolean filterByCountry,
      List<Integer> reportGroupIds,
      boolean filterByReportGroup,
      int reportGroupId,
      String batchId,
      String search,
      String outcome,
      String status) {
    return repository.countPeriodEvidenceRecords(
        fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, batchId, search, outcome, status);
  }
}
