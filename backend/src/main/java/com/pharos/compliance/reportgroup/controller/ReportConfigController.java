package com.pharos.compliance.reportgroup.controller;

import com.pharos.compliance.reportgroup.api.ReportConfigApi;
import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import com.pharos.compliance.reportgroup.service.ReportConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class ReportConfigController implements ReportConfigApi {
  private final ReportConfigService reportConfigService;

  public ReportConfigController(ReportConfigService reportConfigService) {
    this.reportConfigService = reportConfigService;
  }

  @Override
  public ReportConfigFilterOptionsResponse getFilterOptions() {
    return reportConfigService.getFilterOptions();
  }

  @Override
  public ReportConfigExplorerResponse getReportConfigs(String country, ReportConfigStatus status, String reportType, Integer reportGroupId) {
    return reportConfigService.getReportConfigs(country, status, reportType, reportGroupId);
  }

  @Override
  public ReportConfigDetailsResponse getReportConfigDetails(int reportGroupId, int reportSelectionVersionId, String transformerVersionId) {
    return reportConfigService.getReportConfigDetails(reportGroupId, reportSelectionVersionId, transformerVersionId);
  }
}
