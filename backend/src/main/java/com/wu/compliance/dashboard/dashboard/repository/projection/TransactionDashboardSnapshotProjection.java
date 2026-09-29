package com.wu.compliance.dashboard.dashboard.repository.projection;

import java.util.List;

public record TransactionDashboardSnapshotProjection(TransactionOverviewProjection overview,
    List<ExclusionReasonProjection> exclusionReasons, List<NotReportedReasonProjection> notReportedReasons) {}
