package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.config.CacheConfiguration;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Caches the transaction evidence report's read queries. Keeping caching in this dedicated
 * component separates cache policy from query construction and ensures calls cross a Spring proxy;
 * putting cache annotations on methods invoked internally by the repository would bypass the
 * caching interceptor.
 *
 * <p>None of the methods below specify an explicit {@code key} — Spring's default {@code
 * SimpleKeyGenerator} hashes the full parameter list automatically. That's deliberate: these
 * methods have grown to 6-12 parameters each as filters were added over time, and a hand-written
 * SpEL key string has to be manually kept in sync with that list. Miss one when adding a new
 * parameter and two genuinely different queries silently collide on the same cache entry, each
 * serving the other's stale result — a correctness bug with no compiler or test signal. Letting the
 * framework derive the key from every argument removes that failure mode entirely; every parameter
 * here is a primitive, String, LocalDateTime, or List<Integer>, all of which already have correct
 * value-based equals/hashCode, so this is behavior-preserving.
 */
@Component
public class TransactionEvidenceCache {
  private final TransactionReportRepository repository;

  public TransactionEvidenceCache(TransactionReportRepository repository) {
    this.repository = repository;
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_REPORT_CONTEXT)
  public Optional<TransactionReportContextProjection> findReportContext(int reportGroupId, String batchId, int sequenceNumber) {
    return repository.findReportContext(reportGroupId, batchId, sequenceNumber);
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_RECORDS)
  public List<TransactionEvidenceProjection> findEvidenceRecords(int reportGroupId, String batchId, String metric, String search,
      String source, String stage, String outcome, String status, String sortDirection, int size, long offset) {
    return repository.findEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status, sortDirection, size,
        offset);
  }

  @Cacheable(cacheNames = CacheConfiguration.TRANSACTION_EVIDENCE_COUNT)
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    return repository.countEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status);
  }

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_AGGREGATE)
  public PeriodAggregateProjection findPeriodAggregate(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    return repository.findPeriodAggregate(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId);
  }

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_RECORDS)
  public List<TransactionEvidenceProjection> findPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome,
      String status, String sortDirection, int size, long offset) {
    return repository.findPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, search, outcome, status, sortDirection, size, offset);
  }

  @Cacheable(cacheNames = CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_COUNT)
  public long countPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String search, String outcome, String status) {
    return repository.countPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
        reportGroupId, search, outcome, status);
  }
}
