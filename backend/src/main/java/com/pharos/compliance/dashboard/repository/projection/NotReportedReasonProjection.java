package com.pharos.compliance.dashboard.repository.projection;

/**
 * One bucket of the same journey-derived "not reported" partition {@link
 * TransactionOverviewProjection#notReported()} counts, split out by which of a fixed set of plain
 * facts applies to that identifier (a processing error occurred, its batch's report generation
 * failed, a transformation validation failed, or it hasn't been attempted yet) -- see {@code
 * DashboardRepository#getNotReportedReasons}. Counts across every bucket sum to exactly {@code
 * notReported}.
 */
public record NotReportedReasonProjection(String reason, long count) {}
