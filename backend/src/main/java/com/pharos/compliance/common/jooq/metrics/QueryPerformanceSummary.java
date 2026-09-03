package com.pharos.compliance.common.jooq.metrics;

import java.util.List;

/**
 * Request-level database timing report written as one JSON log event.
 */
public record QueryPerformanceSummary(String event, String view, String endpoint, String httpMethod, int httpStatus, String traceId,
    String spanId, int queryCount, double requestDurationMs, double totalDatabaseTimeMs, double databaseTimePercent,
    double slowQueryThresholdMs, int slowQueryCount, int failedQueryCount, QueryExecutionTiming slowestQuery,
    List<QueryExecutionTiming> queriesByDuration) {}
