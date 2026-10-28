package com.wu.compliance.dashboard.common.jdbc.logging;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class SqlUiSectionResolverTest {
  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static String section(String path, String metric) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    if (metric != null) {
      request.setParameter("metric", metric);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return SqlUiSectionResolver.resolve();
  }

  @Test
  void outsideARequestItIsABackgroundOperation() {
    assertThat(SqlUiSectionResolver.resolve()).startsWith("Background");
  }

  @Test
  void batchReportMetricsMapToTheirDataSelectionCards() {
    String path = "/api/v1/transactions/report";
    assertThat(section(path, null)).endsWith("Default transaction metric");
    assertThat(section(path, "SELECTED")).endsWith("Data Selection > Selected data");
    assertThat(section(path, "FILTERED")).endsWith("Data Selection > Total exclusions");
    assertThat(section(path, "EXCLUDED")).endsWith("Data Selection > Excluded");
    assertThat(section(path, "SIMULATED")).endsWith("Data Selection > Simulated (SML)");
    assertThat(section(path, "ALREADY_REPORTED")).endsWith("Data Selection > Already reported");
    assertThat(section(path, "SOFT_DEDUP")).endsWith("Data Selection > Soft-dedup dropped");
    assertThat(section(path, "MISSING")).contains("Missing attempts");
    assertThat(section(path, "FAILED")).endsWith("Transformation / reconciliation evidence");
  }

  @Test
  void otherEndpointsMapToTheirScreens() {
    assertThat(section("/api/v1/transactions/period-report", null)).contains("Reporting-period transaction list");
    assertThat(section("/api/v1/transactions/search", null)).contains("Transaction lookup");
    assertThat(section("/dashboardDetails/batch-view", null)).startsWith("Batch View");
    assertThat(section("/dashboardDetails/transaction-view", null)).startsWith("Transactions Overview");
    assertThat(section("/api/v1/batches/filter-options", null)).contains("Search and country filters");
    assertThat(section("/api/v1/batches", null)).contains("Queue and summary");
    assertThat(section("/api/v1/batches/1/B1/1", null)).contains("Selected batch");
    assertThat(section("/api/v1/report-configs", null)).startsWith("Report Config");
    assertThat(section("/api/v1/health", null)).startsWith("System");
  }
}
