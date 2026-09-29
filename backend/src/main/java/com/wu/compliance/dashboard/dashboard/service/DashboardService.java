package com.wu.compliance.dashboard.dashboard.service;

import com.wu.compliance.dashboard.dashboard.dto.BatchDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionDashboardResponse;
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
