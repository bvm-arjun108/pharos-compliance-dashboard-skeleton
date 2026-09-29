package com.wu.compliance.dashboard.dashboard.controller;

import com.wu.compliance.dashboard.dashboard.api.DashboardApi;
import com.wu.compliance.dashboard.dashboard.dto.BatchDashboardResponse;
import com.wu.compliance.dashboard.dashboard.dto.TransactionDashboardResponse;
import com.wu.compliance.dashboard.dashboard.service.DashboardService;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class DashboardController implements DashboardApi {
  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @Override
  public BatchDashboardResponse getBatchDashboard(LocalDate fromDate, LocalDate toDate, String batchId, String country,
      Integer reportGroupId) {
    return dashboardService.getBatchDashboard(fromDate, toDate, batchId, country, reportGroupId);
  }

  @Override
  public TransactionDashboardResponse getTransactionDashboard(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId) {
    return dashboardService.getTransactionDashboard(fromDate, toDate, country, reportGroupId);
  }
}
