package com.pharos.compliance.dashboard.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Schema(
    description =
        "Adaptive trend grouping: DAILY for up to 31 inclusive days, WEEKLY for up to 120, otherwise MONTHLY")
public enum TrendGranularity {
  DAILY,
  WEEKLY,
  MONTHLY;

  public static TrendGranularity forPeriod(LocalDate fromDate, LocalDate toDate) {
    long inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    if (inclusiveDays <= 31) {
      return DAILY;
    }
    if (inclusiveDays <= 120) {
      return WEEKLY;
    }
    return MONTHLY;
  }
}
