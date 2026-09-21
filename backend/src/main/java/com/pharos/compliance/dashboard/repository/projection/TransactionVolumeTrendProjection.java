package com.pharos.compliance.dashboard.repository.projection;

import java.time.LocalDate;

public record TransactionVolumeTrendProjection(LocalDate periodStart, long totalReportedTransactions, long totalExcludedTransactions) {}
