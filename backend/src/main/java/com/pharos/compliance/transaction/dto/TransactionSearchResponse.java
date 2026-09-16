package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Every evidence row across every report group matching a searched identifier/MTCN/external "
    + "transaction key -- for finding a transaction when its country or report group isn't known up front.")
public record TransactionSearchResponse(String query, List<TransactionSearchResultResponse> results) {}
