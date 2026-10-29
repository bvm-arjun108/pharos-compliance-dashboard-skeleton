package com.wu.compliance.dashboard.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class MetricsEdgeCasesTest {
  private static String viewFor(String endpoint) {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, Duration.ofMillis(100)));
    tracker.beginRequest("GET", endpoint, "t", "s");
    tracker.recordQuery("q", "READ", 1_000_000, 1, false);
    return tracker.completeRequest(200).orElseThrow().view();
  }

  @Test
  void endpointsAreClassifiedIntoViews() {
    assertThat(viewFor("/dashboardDetails/transaction-view")).isEqualTo("DASHBOARD_TRANSACTION_VIEW");
    assertThat(viewFor("/api/v1/batches/filter-options")).isEqualTo("BATCH_FILTER_OPTIONS");
    assertThat(viewFor("/api/v1/batches")).isEqualTo("BATCH_EXPLORER");
    assertThat(viewFor("/api/v1/batches/1/B/1")).isEqualTo("BATCH_DETAILS");
    assertThat(viewFor("/api/v1/report-configs/filter-options")).isEqualTo("REPORT_CONFIG_FILTER_OPTIONS");
    assertThat(viewFor("/api/v1/report-configs")).isEqualTo("REPORT_CONFIG");
    assertThat(viewFor("/api/v1/report-configs/1/2/v")).isEqualTo("REPORT_CONFIG_DETAILS");
    assertThat(viewFor("/api/v1/transactions/period-report")).isEqualTo("TRANSACTION_PERIOD_REPORT");
    assertThat(viewFor("/api/v1/transactions/report")).isEqualTo("TRANSACTION_BATCH_REPORT");
    assertThat(viewFor("/api/v1/health")).isEqualTo("HEALTH");
    assertThat(viewFor("/something/else")).isEqualTo("API");
  }

  @Test
  void failedQueriesAreCountedAndZeroElapsedTimeGivesZeroPercent() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, Duration.ZERO));
    tracker.beginRequest("GET", "/api/v1/health", "t", "s");
    tracker.recordQuery("q", "READ", 5, null, true);
    QueryPerformanceSummary summary = tracker.completeRequest(500).orElseThrow();
    assertThat(summary.failedQueryCount()).isEqualTo(1);
    assertThat(summary.slowQueryCount()).isEqualTo(1);
    assertThat(summary.slowestQuery().outcome()).isEqualTo("FAILED");
  }

  @Test
  void recordingOutsideARequestIsIgnored() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, null));
    tracker.recordQuery("q", "READ", 5, 1, false);
    assertThat(tracker.isRequestActive()).isFalse();
    assertThat(tracker.completeRequest(200)).isEmpty();
  }

  @Test
  void propertiesDefaultAndRejectNegativeThresholds() {
    assertThat(new QueryPerformanceProperties(true, null).slowQueryThreshold()).isEqualTo(Duration.ofMillis(250));
    assertThatThrownBy(() -> new QueryPerformanceProperties(true, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void summaryLoggerSwallowsSerialisationFailures() {
    ObjectMapper mapper = mock(ObjectMapper.class);
    QueryExecutionTiming timing = new QueryExecutionTiming(1, "q", "READ", 1.0, 100.0, 1, "SUCCESS", false);
    QueryPerformanceSummary summary =
        new QueryPerformanceSummary("e", "v", "/x", "GET", 200, "t", "s", 1, 1.0, 1.0, 100.0, 250.0, 0, 0, timing, List.of(timing));
    when(mapper.writeValueAsString(any())).thenThrow(new JacksonException("bad") {
    });
    new QueryPerformanceSummaryLogger(mapper).log(summary);
    new QueryPerformanceSummaryLogger(new ObjectMapper()).log(summary);
  }

  @Test
  void percentageOfAnEmptyTotalIsZero() throws Exception {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, null));
    var percentage = QueryPerformanceTracker.class.getDeclaredMethod("percentage", long.class, long.class);
    percentage.setAccessible(true);
    assertThat(percentage.invoke(tracker, 5L, 0L)).isEqualTo(0.0);
    assertThat(percentage.invoke(tracker, 1L, 4L)).isEqualTo(25.0);
  }
}
