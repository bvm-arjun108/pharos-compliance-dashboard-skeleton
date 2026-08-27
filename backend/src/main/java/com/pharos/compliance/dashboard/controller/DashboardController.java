package com.pharos.compliance.dashboard.controller;

import com.pharos.compliance.dashboard.api.DashboardApi;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import com.pharos.compliance.dashboard.service.DashboardService;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
public class DashboardController implements DashboardApi {

  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @Override
  public Mono<DashboardDetailsResponse> getDashboardDetails(
      LocalDate fromDate, LocalDate toDate, String batchId, String country, Integer reportGroupId) {
    return dashboardService.getDashboardDetails(fromDate, toDate, batchId, country, reportGroupId);
  }
}
