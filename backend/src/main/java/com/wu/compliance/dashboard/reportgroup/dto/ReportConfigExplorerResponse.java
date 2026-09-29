package com.wu.compliance.dashboard.reportgroup.dto;

import com.wu.compliance.dashboard.reportgroup.model.ReportConfigStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Filtered report-group configuration workspace")
public record ReportConfigExplorerResponse(ReportConfigSummaryResponse summary, List<ReportConfigListItemResponse> configurations,
    String country, ReportConfigStatus status, String reportType, Integer reportGroupId) {}
