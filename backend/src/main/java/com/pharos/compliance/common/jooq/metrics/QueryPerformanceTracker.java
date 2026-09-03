package com.pharos.compliance.common.jooq.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Collects jOOQ execution timings on the request thread and creates one request-level summary.
 *
 * <p>Spring MVC handles each request on one platform or virtual thread, so a {@link ThreadLocal}
 * keeps concurrent requests isolated without changing repository APIs or query behavior.
 */
@Component
public class QueryPerformanceTracker {
  private static final String EVENT_NAME = "database_query_performance";
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
  private static final double PERCENT_MULTIPLIER = 100.0;
  private static final double THREE_DECIMAL_PLACES = 1_000.0;
  private static final double TWO_DECIMAL_PLACES = 100.0;
  private final ThreadLocal<RequestState> currentRequest = new ThreadLocal<>();
  private final QueryPerformanceProperties properties;

  public QueryPerformanceTracker(QueryPerformanceProperties properties) {
    this.properties = properties;
  }

  public void beginRequest(String httpMethod, String endpoint, String traceId, String spanId) {
    if (properties.enabled()) {
      currentRequest.set(new RequestState(resolveView(endpoint), endpoint, httpMethod, traceId, spanId, System.nanoTime()));
    }
  }

  public boolean isRequestActive() {
    return currentRequest.get() != null;
  }

  public void recordQuery(String queryName, String operation, long durationNanos, Integer rows, boolean failed) {
    RequestState request = currentRequest.get();
    if (request == null) {
      return;
    }
    request.queries.add(
        new RawQueryTiming(request.queries.size() + 1, queryName, operation, durationNanos, rows, failed ? "FAILED" : "SUCCESS"));
  }

  public Optional<QueryPerformanceSummary> completeRequest(int httpStatus) {
    RequestState request = currentRequest.get();
    currentRequest.remove();
    if (request == null || request.queries.isEmpty()) {
      return Optional.empty();
    }

    long requestDurationNanos = System.nanoTime() - request.startedAtNanos;
    long totalDatabaseNanos = request.queries.stream().mapToLong(RawQueryTiming::durationNanos).sum();
    long slowThresholdNanos = properties.slowQueryThreshold().toNanos();

    List<QueryExecutionTiming> timings = request.queries
      .stream()
      .map(query -> toExecutionTiming(query, totalDatabaseNanos, slowThresholdNanos))
      .sorted(Comparator.comparingDouble(QueryExecutionTiming::durationMs).reversed())
      .toList();
    int slowQueryCount = (int) timings.stream().filter(QueryExecutionTiming::slow).count();
    int failedQueryCount = (int) timings
      .stream()
      .filter(query -> "FAILED".equals(query.outcome()))
      .count();

    return Optional.of(
        new QueryPerformanceSummary(EVENT_NAME, request.view, request.endpoint, request.httpMethod, httpStatus, request.traceId,
            request.spanId, timings.size(), toMilliseconds(requestDurationNanos), toMilliseconds(totalDatabaseNanos),
            percentage(totalDatabaseNanos, requestDurationNanos), toMilliseconds(slowThresholdNanos), slowQueryCount, failedQueryCount,
            timings.getFirst(), timings));
  }

  private QueryExecutionTiming toExecutionTiming(RawQueryTiming query, long totalDatabaseNanos, long slowThresholdNanos) {
    return new QueryExecutionTiming(query.executionOrder(), query.queryName(), query.operation(), toMilliseconds(query.durationNanos()),
        percentage(query.durationNanos(), totalDatabaseNanos), query.rows(), query.outcome(), query.durationNanos() >= slowThresholdNanos);
  }

  private double toMilliseconds(long durationNanos) {
    return Math.round((durationNanos / NANOS_PER_MILLISECOND) * THREE_DECIMAL_PLACES) / THREE_DECIMAL_PLACES;
  }

  private double percentage(long numerator, long denominator) {
    if (denominator <= 0) {
      return 0.0;
    }
    return Math.round(((numerator * PERCENT_MULTIPLIER) / denominator) * TWO_DECIMAL_PLACES) / TWO_DECIMAL_PLACES;
  }

  private String resolveView(String endpoint) {
    if ("/dashboardDetails".equals(endpoint)) {
      return "DASHBOARD";
    }
    if (endpoint.startsWith("/api/v1/batches")) {
      return endpoint.endsWith("/filter-options")
          ? "BATCH_FILTER_OPTIONS"
          : "/api/v1/batches".equals(endpoint) ? "BATCH_EXPLORER" : "BATCH_DETAILS";
    }
    if (endpoint.startsWith("/api/v1/report-configs")) {
      return endpoint.endsWith("/filter-options")
          ? "REPORT_CONFIG_FILTER_OPTIONS"
          : "/api/v1/report-configs".equals(endpoint) ? "REPORT_CONFIG" : "REPORT_CONFIG_DETAILS";
    }
    if (endpoint.startsWith("/api/v1/transactions/period-report")) {
      return "TRANSACTION_PERIOD_REPORT";
    }
    if (endpoint.startsWith("/api/v1/transactions/report")) {
      return "TRANSACTION_BATCH_REPORT";
    }
    if (endpoint.startsWith("/api/v1/health")) {
      return "HEALTH";
    }
    return "API";
  }

  private record RawQueryTiming(int executionOrder, String queryName, String operation, long durationNanos, Integer rows, String outcome) {}

  private static final class RequestState {
    private final String view;
    private final String endpoint;
    private final String httpMethod;
    private final String traceId;
    private final String spanId;
    private final long startedAtNanos;
    private final List<RawQueryTiming> queries = new ArrayList<>();

    private RequestState(String view, String endpoint, String httpMethod, String traceId, String spanId, long startedAtNanos) {
      this.view = view;
      this.endpoint = endpoint;
      this.httpMethod = httpMethod;
      this.traceId = traceId;
      this.spanId = spanId;
      this.startedAtNanos = startedAtNanos;
    }
  }
}
