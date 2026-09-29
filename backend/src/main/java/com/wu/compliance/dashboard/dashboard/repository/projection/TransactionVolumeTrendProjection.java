package com.wu.compliance.dashboard.dashboard.repository.projection;

import java.time.LocalDate;

public record TransactionVolumeTrendProjection(LocalDate periodStart, long totalReportedTransactions, long totalExcludedTransactions) {}
