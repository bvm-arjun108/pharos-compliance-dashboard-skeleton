package com.pharos.compliance.dashboard.repository.projection;

public record ReportGroupMetricsProjection(int reportGroupId, String reportGroupName, long batchesRan, long successfulBatches,
    long batchesNeedingAttention, long transformationFailureBatches, long missingAttemptBatches, long activityMissingBatches,
    long totalReportedTransactions, long totalExcludedTransactions) {}
