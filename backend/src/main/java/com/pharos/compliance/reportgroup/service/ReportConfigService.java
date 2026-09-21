package com.pharos.compliance.reportgroup.service;

import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.dto.ReportGroupOptionResponse;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import java.util.List;

public interface ReportConfigService {
  ReportConfigFilterOptionsResponse getFilterOptions();

  List<ReportGroupOptionResponse> getReportGroupOptions();

  ReportConfigExplorerResponse getReportConfigs(String country, ReportConfigStatus status, String reportType, Integer reportGroupId);

  ReportConfigDetailsResponse getReportConfigDetails(int reportGroupId, int reportSelectionVersionId, String transformerVersionId);
}
