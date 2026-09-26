package com.pharos.compliance.dashboard.repository.projection;

import java.util.List;

public record BatchDashboardSnapshotProjection(DashboardCountsProjection counts, List<ReportGroupMetricsProjection> reportGroups) {}
