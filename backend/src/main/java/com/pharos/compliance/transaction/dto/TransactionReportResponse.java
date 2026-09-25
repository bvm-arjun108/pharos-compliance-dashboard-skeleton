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
    long aggregateCount,
    @Schema(description = "For FAILED/SKIPPED, the raw report_transformation_reconciliation.activity_transformation_failed value before "
    + "correcting it against record_transformation_journey evidence; identical to aggregateCount for every other metric. See "
    + "aggregateCountMismatch.") long reportedAggregateCount,
    @Schema(description = "True when aggregateCount and reportedAggregateCount disagree -- the upstream transformer job's reconciliation "
    + "count doesn't match what record_transformation_journey actually recorded. Always false outside FAILED/SKIPPED.") boolean aggregateCountMismatch,
    long availableRecordCount, long matchingRecordCount, TransactionEvidenceLevel evidenceLevel, String evidenceMessage,
    List<TransactionEvidenceRecordResponse> transactions, String search, TransactionEvidenceSource source, TransactionStage stage,
    TransactionOutcome outcome, TransactionStatus status, TransactionSortDirection sortDirection, int page, int size,
    @Schema(description = "Opaque cursor for the next page, null once exhausted. Pass it back as the `cursor` request "
    + "parameter for cheap sequential access that skips the OFFSET scan cost `page` incurs at depth -- an alternative "
    + "to `page`, not a required field to track.") String nextCursor) {}
