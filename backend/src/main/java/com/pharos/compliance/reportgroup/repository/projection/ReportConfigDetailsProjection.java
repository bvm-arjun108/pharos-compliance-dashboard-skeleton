package com.pharos.compliance.reportgroup.repository.projection;

import java.time.Instant;

public record ReportConfigDetailsProjection(int reportGroupId, String reportGroupName, String businessGroupName, String countryCode,
    String countryName, String threeLetterCountryCode, String regionCode, String regionName, String reportCurrency, String reportType,
    boolean active, int reportSelectionVersionId, String transformerVersionId, Instant createdAt, Instant modifiedAt,
    boolean databaseLookupEnabled, boolean blankReport, boolean nonTransactionalReport, boolean partialReport, Integer reportPeriod,
    String additionalData, String mappingProjectKey, String mappingServiceName, String acknowledgementDocumentSubtype,
    String outputFileDocumentSubtype, String submissionDocumentSubtype, String transformerConfig, String inboundRuleId,
    String outboundRuleId, String reportSelection, String reportableActivityColumns, String ruleHitColumns, String exclusionStrategy,
    String exclusionReason, String columnToCompare, String manipulationStrategyMetadata, String reconciliationStrategyMetadata) {}
