package com.pharos.compliance.reportgroup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Complete read-only report-group configuration")
public record ReportConfigDetailsResponse(Identity identity, Versioning versioning, ProcessingBehavior processingBehavior, Mapping mapping,
    Rules rules, Strategies strategies) {
  public record Identity(int reportGroupId, String reportGroupName, String businessGroupName, String countryCode, String countryName,
      String threeLetterCountryCode, String regionCode, String regionName, String reportCurrency, String reportType, boolean active) {}

  public record Versioning(int reportSelectionVersionId, String transformerVersionId, Instant createdAt, Instant modifiedAt) {}

  public record ProcessingBehavior(boolean databaseLookupEnabled, boolean blankReport, boolean nonTransactionalReport, boolean partialReport,
      Integer reportPeriod, String additionalData) {}

  public record Mapping(String projectKey, String serviceName, String acknowledgementDocumentSubtype, String outputFileDocumentSubtype,
      String submissionDocumentSubtype, String transformerConfig) {}

  public record Rules(String inboundRuleId, String outboundRuleId, String reportSelection, String reportableActivityColumns,
      String ruleHitColumns) {}

  public record Strategies(String exclusionStrategy, String exclusionReason, String columnToCompare, String manipulationStrategyMetadata,
      String reconciliationStrategyMetadata) {}
}
