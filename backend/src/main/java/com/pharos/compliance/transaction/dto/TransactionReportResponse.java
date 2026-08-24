package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionStage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Transaction evidence report for one reconciliation batch and metric")
public record TransactionReportResponse(
    TransactionReportContextResponse context,
    TransactionMetric metric,
    String metricLabel,
    long aggregateCount,
    long availableRecordCount,
    long matchingRecordCount,
    TransactionEvidenceLevel evidenceLevel,
    String evidenceMessage,
    TransactionOutcomeBreakdownResponse outcomeBreakdown,
    List<TransactionStageBreakdownResponse> stageBreakdown,
    List<TransactionEvidenceRecordResponse> transactions,
    String search,
    TransactionEvidenceSource source,
    TransactionStage stage,
    TransactionOutcome outcome,
    int page,
    int size) {}
