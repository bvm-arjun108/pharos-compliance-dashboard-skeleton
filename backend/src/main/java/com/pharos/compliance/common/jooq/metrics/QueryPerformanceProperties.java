package com.pharos.compliance.common.jooq.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pharos.observability.query-performance")
public record QueryPerformanceProperties(boolean enabled, Duration slowQueryThreshold) {
  private static final Duration DEFAULT_SLOW_QUERY_THRESHOLD = Duration.ofMillis(250);

  public QueryPerformanceProperties {
    slowQueryThreshold = slowQueryThreshold == null ? DEFAULT_SLOW_QUERY_THRESHOLD : slowQueryThreshold;
    if (slowQueryThreshold.isNegative()) {
      throw new IllegalArgumentException("Query performance slow-query-threshold cannot be negative");
    }
  }
}
