package com.pharos.compliance.common.jooq.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueryPerformanceTrackerTest {
  @Test
  void summarizesQueriesSlowestFirstAndPreservesExecutionOrder() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, Duration.ofMillis(100)));
    tracker.beginRequest("GET", "/dashboardDetails", "trace-1", "span-1");

    tracker.recordQuery("Load dashboard totals", "READ", Duration.ofMillis(25).toNanos(), 1, false);
    tracker.recordQuery("Load transaction overview", "READ", Duration.ofMillis(150).toNanos(), 1, false);

    QueryPerformanceSummary summary = tracker.completeRequest(200).orElseThrow();

    assertThat(summary.event()).isEqualTo("database_query_performance");
    assertThat(summary.view()).isEqualTo("DASHBOARD");
    assertThat(summary.queryCount()).isEqualTo(2);
    assertThat(summary.totalDatabaseTimeMs()).isEqualTo(175.0);
    assertThat(summary.slowQueryCount()).isEqualTo(1);
    assertThat(summary.failedQueryCount()).isZero();
    assertThat(summary.slowestQuery().queryName()).isEqualTo("Load transaction overview");
    assertThat(summary.queriesByDuration())
      .extracting(QueryExecutionTiming::queryName)
      .containsExactly("Load transaction overview", "Load dashboard totals");
    assertThat(summary.queriesByDuration()).extracting(QueryExecutionTiming::executionOrder).containsExactly(2, 1);
  }

  @Test
  void returnsNoSummaryWhenRequestExecutesNoQueries() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, Duration.ofMillis(100)));
    tracker.beginRequest("GET", "/swagger-ui.html", "trace-1", "span-1");

    assertThat(tracker.completeRequest(200)).isEmpty();
  }

  @Test
  void ignoresQueriesWhenPerformanceLoggingIsDisabled() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(false, Duration.ofMillis(100)));
    tracker.beginRequest("GET", "/dashboardDetails", "trace-1", "span-1");
    tracker.recordQuery("Load dashboard totals", "READ", Duration.ofMillis(25).toNanos(), 1, false);

    assertThat(tracker.completeRequest(200)).isEmpty();
  }
}
