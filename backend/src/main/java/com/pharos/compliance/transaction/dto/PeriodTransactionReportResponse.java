package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceLevel;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Excluded-transaction evidence across every batch matching a date range / report-group /"
    + " country filter — not scoped to a single batch")
public record PeriodTransactionReportResponse(PeriodTransactionContextResponse context, String metricLabel,
    @Schema(description = "The reconciliation-sourced count this evidence list is answering for, and the only number "
    + "matchingRecordCount may legitimately be compared against. Set to SUM(excluded_txn) over the scoped batches for "
    + "status=EXCLUDED with batchScopedExcluded=true -- the one combination whose evidence query is defined to match that "
    + "scalar. For every other status/flag combination there is no reconciliation scalar answering the same question, so "
    + "this mirrors matchingRecordCount instead of borrowing an unrelated one.") long aggregateCount, long availableRecordCount,
    long matchingRecordCount, TransactionEvidenceLevel evidenceLevel, String evidenceMessage,
    List<TransactionEvidenceRecordResponse> transactions, String search, TransactionOutcome outcome, TransactionStatus status,
    TransactionSortDirection sortDirection, int page, int size,
    @Schema(description = "Opaque cursor for the next page, null once exhausted. Pass it back as the `cursor` request "
    + "parameter for cheap sequential access that skips the OFFSET scan cost `page` incurs at depth -- an alternative "
    + "to `page`, not a required field to track.") String nextCursor) {}
