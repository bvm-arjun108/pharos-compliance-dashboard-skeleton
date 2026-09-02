package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Summary for the current report-configuration selection")
public record ReportConfigSummaryResponse(long totalConfigurations, long activeConfigurations, long representedCountries,
    long objectiveConfigurations) {}
