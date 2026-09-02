package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Database-backed report-configuration filters")
public record ReportConfigFilterOptionsResponse(List<ReportConfigCountryOptionResponse> countries, List<String> reportTypes) {}
