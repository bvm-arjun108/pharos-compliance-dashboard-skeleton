package com.pharos.compliance.dashboard.controller;

import com.pharos.compliance.dashboard.api.DashboardApi;
import com.pharos.compliance.dashboard.dto.BatchDashboardResponse;
import com.pharos.compliance.dashboard.dto.TransactionDashboardResponse;
import com.pharos.compliance.dashboard.service.DashboardService;
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
