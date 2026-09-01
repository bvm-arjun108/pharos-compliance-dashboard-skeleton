package com.pharos.compliance.dashboard.service;

import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import java.time.LocalDate;

public interface DashboardService {

  default DashboardDetailsResponse getDashboardDetails(LocalDate fromDate, LocalDate toDate) {
    return getDashboardDetails(fromDate, toDate, "", "ALL", null);
  }

  DashboardDetailsResponse getDashboardDetails(
      LocalDate fromDate, LocalDate toDate, String batchId, String country, Integer reportGroupId);
}
