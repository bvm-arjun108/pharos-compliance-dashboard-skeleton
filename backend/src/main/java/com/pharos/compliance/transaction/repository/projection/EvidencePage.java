package com.pharos.compliance.transaction.repository.projection;

import java.util.List;

/**
 * One page of merged transaction evidence, plus the cursor to fetch the next page cheaply
 * ({@code null} once exhausted). See {@code com.pharos.compliance.transaction.model.EvidenceCursor}
 * for the encoding and the rationale for offering both offset and cursor pagination.
 */
public record EvidencePage(List<TransactionEvidenceProjection> records, String nextCursor) {}
