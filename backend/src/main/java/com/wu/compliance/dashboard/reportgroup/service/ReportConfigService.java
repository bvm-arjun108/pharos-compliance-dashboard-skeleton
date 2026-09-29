package com.wu.compliance.dashboard.reportgroup.service;

import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigDetailsResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigExplorerResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.wu.compliance.dashboard.reportgroup.dto.ReportGroupOptionResponse;
import com.wu.compliance.dashboard.reportgroup.model.ReportConfigStatus;
import java.util.List;

public interface ReportConfigService {
  ReportConfigFilterOptionsResponse getFilterOptions();

  List<ReportGroupOptionResponse> getReportGroupOptions();

  ReportConfigExplorerResponse getReportConfigs(String country, ReportConfigStatus status, String reportType, Integer reportGroupId);

  ReportConfigDetailsResponse getReportConfigDetails(int reportGroupId, int reportSelectionVersionId, String transformerVersionId);
}
