package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Batch and report-group context for a transaction evidence report")
public record TransactionReportContextResponse(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber,
    String countryCode, String countryName, String reportingPeriodFrom, String reportingPeriodTo) {}
