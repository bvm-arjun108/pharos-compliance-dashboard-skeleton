package com.wu.compliance.dashboard.transaction.repository.projection;

public record PeriodAggregateProjection(long batchCount, long totalExcluded, String reportGroupName) {}
