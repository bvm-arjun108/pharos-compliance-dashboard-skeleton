package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One report group, latest version only, for populating report-group filter dropdowns -- not a"
    + " configuration directory entry; see ReportConfigListItemResponse for the full per-version listing.")
public record ReportGroupOptionResponse(@Schema(example = "1573742369") int reportGroupId,
    @Schema(example = "SINGAPORE MONTHLY OBJECTIVE") String reportGroupName, @Schema(example = "SG") String countryCode) {}
