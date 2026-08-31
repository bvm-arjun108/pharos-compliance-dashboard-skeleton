package com.pharos.compliance.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory read caches for the transaction evidence report. A single reconciliation batch is
 * write-once in practice: its aggregate counts and evidence rows are produced by one processing run
 * and essentially never change afterward, but paging through and re-filtering that same batch's
 * evidence is common (Next/Previous, switching metric cards, re-applying the same search). Short
 * TTLs bound staleness without needing cache invalidation wired to batch state.
 *
 * <p>Separate caches (not one shared spec) because the batch-context and count/breakdown entries
 * are tiny and reused often, while evidence-page entries hold up to 200 full records each and have
 * a much larger effective key space (free-text search is part of the key) — that one is kept
 * smaller and shorter-lived to bound memory.
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfiguration {

  public static final String TRANSACTION_REPORT_CONTEXT = "transactionReportContext";
  public static final String TRANSACTION_RECORD_DETAIL = "transactionRecordDetail";
  public static final String TRANSACTION_EVIDENCE_COUNT = "transactionEvidenceCount";
  public static final String TRANSACTION_EVIDENCE_RECORDS = "transactionEvidenceRecords";
  public static final String PERIOD_TRANSACTION_AGGREGATE = "periodTransactionAggregate";
  public static final String PERIOD_TRANSACTION_EVIDENCE_COUNT = "periodTransactionEvidenceCount";
  public static final String PERIOD_TRANSACTION_EVIDENCE_RECORDS =
      "periodTransactionEvidenceRecords";

  @Bean
  CacheManager cacheManager() {
    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(
        List.of(
            buildCache(TRANSACTION_REPORT_CONTEXT, 5_000, Duration.ofMinutes(10)),
            buildCache(TRANSACTION_EVIDENCE_COUNT, 10_000, Duration.ofMinutes(2)),
            buildCache(TRANSACTION_RECORD_DETAIL, 5_000, Duration.ofMinutes(2)),
            buildCache(TRANSACTION_EVIDENCE_RECORDS, 1_000, Duration.ofMinutes(2)),
            buildCache(PERIOD_TRANSACTION_AGGREGATE, 2_000, Duration.ofMinutes(2)),
            buildCache(PERIOD_TRANSACTION_EVIDENCE_COUNT, 2_000, Duration.ofMinutes(2)),
            buildCache(PERIOD_TRANSACTION_EVIDENCE_RECORDS, 500, Duration.ofMinutes(2))));
    return manager;
  }

  private CaffeineCache buildCache(String name, long maximumSize, Duration expireAfterWrite) {
    return new CaffeineCache(
        name,
        Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(expireAfterWrite)
            .recordStats() // exposes hit/miss/eviction counts via /actuator/metrics/cache.*
            .build());
  }
}
