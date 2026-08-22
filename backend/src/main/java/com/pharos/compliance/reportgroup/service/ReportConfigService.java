package com.pharos.compliance.reportgroup.service;

import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import reactor.core.publisher.Mono;

public interface ReportConfigService {

  Mono<ReportConfigFilterOptionsResponse> getFilterOptions();

  Mono<ReportConfigExplorerResponse> getReportConfigs(
      String country, ReportConfigStatus status, String reportType, Integer reportGroupId);

  Mono<ReportConfigDetailsResponse> getReportConfigDetails(
      int reportGroupId, int reportSelectionVersionId, String transformerVersionId);
}
