package com.wu.compliance.dashboard.reportgroup.controller;

import com.wu.compliance.dashboard.reportgroup.api.ReportConfigApi;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigDetailsResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigExplorerResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportGroupOptionResponse;
import com.wu.compliance.dashboard.reportgroup.model.ReportConfigStatus;
import com.wu.compliance.dashboard.reportgroup.service.ReportConfigService;
import java.util.List;
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
  public List<ReportGroupOptionResponse> getReportGroupOptions() {
    return reportConfigService.getReportGroupOptions();
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
