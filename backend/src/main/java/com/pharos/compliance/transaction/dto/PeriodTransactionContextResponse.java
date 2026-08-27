package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(
    description =
        "Report-group/country/date-range context for a period-wide transaction evidence report — "
            + "no single batch, since the underlying KPI can span many batches")
public record PeriodTransactionContextResponse(
    Integer reportGroupId,
    String reportGroupName,
    String countryCode,
    String countryName,
    LocalDate fromDate,
    LocalDate toDate,
    long batchCount) {}
