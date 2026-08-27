package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
    description =
        "Excluded-transaction evidence across every batch matching a date range / report-group /"
            + " country filter — not scoped to a single batch")
public record PeriodTransactionReportResponse(
    PeriodTransactionContextResponse context,
    String metricLabel,
    long aggregateCount,
    long availableRecordCount,
    long matchingRecordCount,
    TransactionEvidenceLevel evidenceLevel,
    String evidenceMessage,
    List<TransactionEvidenceRecordResponse> transactions,
    String search,
    TransactionOutcome outcome,
    TransactionStatus status,
    TransactionSortDirection sortDirection,
    int page,
    int size) {}
