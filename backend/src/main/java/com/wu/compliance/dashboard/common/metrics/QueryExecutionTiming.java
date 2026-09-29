package com.wu.compliance.dashboard.common.metrics;

/**
 * Timing and outcome for one SQL statement executed during an HTTP request.
 */
public record QueryExecutionTiming(int executionOrder, String queryName, String operation, double durationMs, double databaseTimePercent,
    Integer rows, String outcome, boolean slow) {}
