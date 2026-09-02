package com.pharos.compliance.transaction.repository.projection;

public record PeriodAggregateProjection(long batchCount, long totalExcluded, String reportGroupName) {}
