package com.pharos.compliance.reportgroup.repository.projection;

public record ReportConfigSummaryProjection(long totalConfigurations, long activeConfigurations, long representedCountries,
    long objectiveConfigurations) {}
