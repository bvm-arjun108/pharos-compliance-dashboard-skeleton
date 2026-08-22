package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Concise report-group configuration result")
public record ReportConfigListItemResponse(
    int reportGroupId,
    String reportGroupName,
    int reportSelectionVersionId,
    String transformerVersionId,
    String countryCode,
    String countryName,
    String regionName,
    String reportType,
    boolean active,
    boolean partialReport,
    boolean databaseLookupEnabled,
    String mappingServiceName,
    Instant modifiedAt) {}
