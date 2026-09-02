package com.pharos.compliance.reportgroup.repository.projection;

import java.time.Instant;

public record ReportConfigListProjection(int reportGroupId, String reportGroupName, int reportSelectionVersionId,
    String transformerVersionId, String countryCode, String countryName, String regionName, String reportType, boolean active,
    boolean partialReport, boolean databaseLookupEnabled, String mappingServiceName, Instant modifiedAt) {}
