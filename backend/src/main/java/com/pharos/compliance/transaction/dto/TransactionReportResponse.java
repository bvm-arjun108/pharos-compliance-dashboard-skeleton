package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Transaction evidence report for one reconciliation batch and metric")
public record TransactionReportResponse(TransactionReportContextResponse context, TransactionMetric metric, String metricLabel,
    long aggregateCount, long availableRecordCount, long matchingRecordCount, TransactionEvidenceLevel evidenceLevel, String evidenceMessage,
    List<TransactionEvidenceRecordResponse> transactions, String search, TransactionEvidenceSource source, TransactionStage stage,
    TransactionOutcome outcome, TransactionStatus status, TransactionSortDirection sortDirection, int page, int size,
    @Schema(description = "Opaque cursor for the next page, null once exhausted. Pass it back as the `cursor` request "
    + "parameter for cheap sequential access that skips the OFFSET scan cost `page` incurs at depth -- an alternative "
    + "to `page`, not a required field to track.") String nextCursor) {}
