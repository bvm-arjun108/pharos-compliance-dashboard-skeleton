package com.pharos.compliance.dashboard.repository.projection;

/**
 * A transaction-level (not batch-level) partition of every selected identifier in scope, derived
 * from its full journey event history rather than its latest-state row -- see {@code
 * DashboardRepository#getTransactionOverview}. {@code excluded} and an implicit {@code reported}
 * bucket partition {@code selected} exactly ({@code selected == excluded + reported}), so {@code
 * expected == selected - excluded}; {@code notReported} is the subset of {@code expected} that
 * isn't {@code reported}. {@code reported} itself isn't exposed here since it isn't one of the
 * four values the Transactions Overview card displays -- it's recoverable as {@code expected -
 * notReported} if ever needed.
 */
public record TransactionOverviewProjection(long selected, long expected, long excluded, long notReported) {}
