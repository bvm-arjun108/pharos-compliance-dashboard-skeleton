package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the transaction table -- the columns it actually renders, and the few it derives the
 * Investigation Detail column from.
 *
 * <p>Everything the expanded panel shows lives in {@link TransactionEvidenceDetailResponse} and is
 * fetched per transaction when a row is opened. That split is deliberate and is mostly about
 * personal data: this response is read on every page load, sort and filter change, and previously
 * carried each party's name, date of birth, phone number and government ID number for all 100 rows
 * whether or not anyone expanded one. Keeping those out of here means they are read only when
 * someone actually opens a transaction, and that access is attributable to that request.
 *
 * <p>It also removed the two most expensive pieces of the list query -- the {@code
 * reg_reportable_activity} join and the rule-hit rollup, along with the bridge that fed it.
 */
@Schema(description = "One row of the transaction evidence table; expanded detail is fetched separately")
public record TransactionEvidenceRecordResponse(String recordKey, String identifier, String mtcn, String batchId,
    TransactionEvidenceSource source, String stage, String status, TransactionOutcome outcome, String comments, String skipReason,
    String exclusionReason, String reportedBatchId, String modifiedAt, Boolean processingComplete) {}
