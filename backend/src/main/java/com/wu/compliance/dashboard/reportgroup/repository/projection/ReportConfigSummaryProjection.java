package com.wu.compliance.dashboard.reportgroup.repository.projection;

public record ReportConfigSummaryProjection(long totalConfigurations, long activeConfigurations, long representedCountries,
    long objectiveConfigurations, long subjectiveConfigurations) {}
