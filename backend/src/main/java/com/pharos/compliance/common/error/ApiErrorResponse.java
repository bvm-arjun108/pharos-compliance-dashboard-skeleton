package com.pharos.compliance.common.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Standard error returned by every Pharos API endpoint")
public record ApiErrorResponse(
    @Schema(example = "2026-08-22T00:00:00-04:00") OffsetDateTime timestamp,
    @Schema(example = "400") int status,
    @Schema(example = "Bad Request") String error,
    @Schema(example = "INVALID_DATE_RANGE") String code,
    @Schema(example = "fromDate must be on or before toDate") String message,
    @Schema(example = "/dashboardDetails") String path,
    @Schema(description = "Distributed trace identifier") String traceId,
    @Schema(description = "Current server span identifier") String spanId) {}
