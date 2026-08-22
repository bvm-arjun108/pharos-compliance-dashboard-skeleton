package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One available latest-state journey or exclusion-audit record")
public record TransactionEvidenceRecordResponse(
    String recordKey,
    String identifier,
    String mtcn,
    TransactionEvidenceSource source,
    String stage,
    String status,
    TransactionOutcome outcome,
    String comments,
    String skipReason,
    String ruleId,
    String exclusionReason,
    String exclusionStrategy,
    String reportedBatchId,
    String reportingTimestamp,
    String modifiedAt,
    Boolean processingComplete) {}
