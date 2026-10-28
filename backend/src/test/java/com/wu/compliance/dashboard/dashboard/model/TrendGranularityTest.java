package com.wu.compliance.dashboard.dashboard.model;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TrendGranularityTest {
  private static final LocalDate START = LocalDate.of(2026, 1, 1);

  @Test
  void choosesTheGranularityFromTheInclusiveDayCount() {
    assertThat(TrendGranularity.forPeriod(START, START)).isEqualTo(TrendGranularity.DAILY);
    assertThat(TrendGranularity.forPeriod(START, START.plusDays(30))).isEqualTo(TrendGranularity.DAILY);
    assertThat(TrendGranularity.forPeriod(START, START.plusDays(31))).isEqualTo(TrendGranularity.WEEKLY);
    assertThat(TrendGranularity.forPeriod(START, START.plusDays(119))).isEqualTo(TrendGranularity.WEEKLY);
    assertThat(TrendGranularity.forPeriod(START, START.plusDays(120))).isEqualTo(TrendGranularity.MONTHLY);
    assertThat(TrendGranularity.valueOf("MONTHLY")).isEqualTo(TrendGranularity.MONTHLY);
  }
}
