package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One raw evidence row matching a searched MTCN or external transaction key -- unmerged, "
    + "so the same real transaction can legitimately appear more than once here (once per report group or rule side it "
    + "was evaluated under, possibly with different outcomes in each).")
public record TransactionSearchResultResponse(@Schema(example = "1000000007") int reportGroupId,
    @Schema(example = "PORTUGAL OBJECTIVE") String reportGroupName, @Schema(example = "PT") String countryCode,
    @Schema(example = "Portugal") String countryName, String batchId,
    @Schema(description = "Which table this row came from", example = "JOURNEY") String evidenceSource, String stage, String status,
    String comments,
    @Schema(description = "Which field the search was scoped to -- the same for every row in the response, "
    + "since the caller now picks it up front", example = "mtcn") String matchedOn, String occurredAt,
    @Schema(description = "This row's actual MTCN, regardless of which field the search query matched on -- "
    + "used to jump straight to the transaction report's own identifier/MTCN search, which doesn't understand external "
    + "transaction keys", example = "9000000000217510") String mtcn) {}
