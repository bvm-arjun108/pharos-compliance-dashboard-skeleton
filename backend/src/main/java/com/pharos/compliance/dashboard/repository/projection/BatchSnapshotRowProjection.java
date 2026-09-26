package com.pharos.compliance.dashboard.repository.projection;

public record BatchSnapshotRowProjection(DashboardCountsProjection counts, ReportGroupMetricsProjection reportGroup) {}
