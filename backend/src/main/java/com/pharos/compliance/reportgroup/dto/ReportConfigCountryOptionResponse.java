package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Country available to the report-configuration workspace")
public record ReportConfigCountryOptionResponse(String code, String name) {}
