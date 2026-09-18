package com.pharos.compliance.dashboard.service;

import com.pharos.compliance.dashboard.dto.BatchDashboardResponse;
import com.pharos.compliance.dashboard.dto.TransactionDashboardResponse;
import java.time.LocalDate;

public interface DashboardService {
  default BatchDashboardResponse getBatchDashboard(LocalDate fromDate, LocalDate toDate) {
    return getBatchDashboard(fromDate, toDate, "", "ALL", null);
  }

  BatchDashboardResponse getBatchDashboard(LocalDate fromDate, LocalDate toDate, String batchId, String country, Integer reportGroupId);

  default TransactionDashboardResponse getTransactionDashboard(LocalDate fromDate, LocalDate toDate) {
    return getTransactionDashboard(fromDate, toDate, "ALL", null);
  }

  TransactionDashboardResponse getTransactionDashboard(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId);
}
