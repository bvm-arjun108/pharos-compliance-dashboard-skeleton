package com.pharos.compliance.dashboard.repository.projection;

/**
 * A two-way split of the same journey-derived "not reported" partition {@link
 * TransactionOverviewProjection#notReported()} -- see {@code
 * DashboardRepository#getNotReportedBreakdown}. {@code stalled} is the subset where processing
 * genuinely finished in a terminal non-success state (a real problem worth investigating);
 * {@code stillProcessing} is everything else in the bucket (still in flight, not yet due for
 * concern). {@code stalled + stillProcessing == notReported} exactly.
 */
public record NotReportedBreakdownProjection(long stalled, long stillProcessing) {}
