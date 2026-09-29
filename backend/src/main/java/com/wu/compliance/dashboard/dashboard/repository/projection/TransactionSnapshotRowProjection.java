package com.wu.compliance.dashboard.dashboard.repository.projection;

public record TransactionSnapshotRowProjection(String rowType, long selected, long expected, long excluded, long notReported, String reason,
    long count) {}
