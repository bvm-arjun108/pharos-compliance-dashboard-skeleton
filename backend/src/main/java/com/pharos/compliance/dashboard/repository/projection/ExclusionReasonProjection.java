package com.pharos.compliance.dashboard.repository.projection;

/**
 * One bucket of the same journey-derived "excluded" partition {@link
 * TransactionOverviewProjection#excluded()} counts, split out by each excluded identifier's own
 * {@code skip_reason} (falling back to {@code comments} when null) -- see {@code
 * DashboardRepository#getTopExclusionReasons}. Counts across every bucket sum to exactly {@code
 * excluded}.
 */
public record ExclusionReasonProjection(String reason, long count) {}
