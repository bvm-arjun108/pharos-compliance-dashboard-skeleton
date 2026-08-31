package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the transaction evidence list — only the fields the table itself renders.
 *
 * <p>The Details panel's party, currency and rule-hit data is deliberately absent: it lives on
 * {@link TransactionRecordDetailResponse} and is fetched per row when a user expands one. Carrying
 * it here meant every list request joined reg_reportable_activity and aggregated rule hits for a
 * full page of rows to populate a panel that is usually never opened.
 */
@Schema(description = "A single transaction evidence row as rendered in the list")
public record TransactionEvidenceRecordResponse(
    String recordKey,
    int reportGroupId,
    String identifier,
    String mtcn,
    String batchId,
    String source,
    String status,
    String comments,
    String skipReason,
    String exclusionReason,
    String reportedBatchId,
    String modifiedAt,
    Boolean processingComplete) {}
