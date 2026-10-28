package com.wu.compliance.dashboard.dashboard.service;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DashboardServiceDefaultsTest {
  private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate TO = LocalDate.of(2026, 9, 30);

  @Test
  void shortOverloadsDefaultToAllCountriesAndNoBatchFilter() {
    DashboardService service = mock(DashboardService.class, CALLS_REAL_METHODS);
    service.getBatchDashboard(FROM, TO);
    verify(service).getBatchDashboard(FROM, TO, "", "ALL", null);
    service.getTransactionDashboard(FROM, TO);
    verify(service).getTransactionDashboard(FROM, TO, "ALL", null);
  }
}
