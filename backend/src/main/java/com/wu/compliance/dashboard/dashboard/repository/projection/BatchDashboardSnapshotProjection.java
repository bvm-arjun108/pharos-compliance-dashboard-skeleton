package com.wu.compliance.dashboard.dashboard.repository.projection;

import java.util.List;

public record BatchDashboardSnapshotProjection(DashboardCountsProjection counts, List<ReportGroupMetricsProjection> reportGroups) {}
