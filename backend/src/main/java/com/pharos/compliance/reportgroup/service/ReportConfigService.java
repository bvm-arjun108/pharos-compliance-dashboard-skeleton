package com.pharos.compliance.reportgroup.service;

import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;

public interface ReportConfigService {

  ReportConfigFilterOptionsResponse getFilterOptions();

  ReportConfigExplorerResponse getReportConfigs(
      String country, ReportConfigStatus status, String reportType, Integer reportGroupId);

  ReportConfigDetailsResponse getReportConfigDetails(
      int reportGroupId, int reportSelectionVersionId, String transformerVersionId);
}
