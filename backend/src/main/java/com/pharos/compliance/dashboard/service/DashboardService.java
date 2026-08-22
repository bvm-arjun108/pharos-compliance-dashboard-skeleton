package com.pharos.compliance.dashboard.service;

import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import java.time.LocalDate;
import reactor.core.publisher.Mono;

public interface DashboardService {

  default Mono<DashboardDetailsResponse> getDashboardDetails(LocalDate fromDate, LocalDate toDate) {
    return getDashboardDetails(fromDate, toDate, "", "ALL");
  }

  Mono<DashboardDetailsResponse> getDashboardDetails(
      LocalDate fromDate, LocalDate toDate, String batchId, String country);
}
