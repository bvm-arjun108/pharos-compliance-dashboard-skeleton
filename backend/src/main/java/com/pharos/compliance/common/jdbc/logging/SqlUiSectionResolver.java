package com.pharos.compliance.common.jdbc.logging;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Identifies the request's UI context without changing SQL, API parameters, or query results.
 * Entirely independent of jOOQ despite living in this package; public so the JDBC-migration
 * tracing wrapper under {@code com.pharos.compliance.common.jdbc.logging} can call the exact same
 * resolution this class already provides, rather than re-deriving it.
 */
public final class SqlUiSectionResolver {
  private SqlUiSectionResolver() {
  }

  public static String resolve() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
      return "Background / non-HTTP database operation";
    }
    var request = attributes.getRequest();
    String path = request.getRequestURI();
    if (path.endsWith("/transactions/report")) {
      String metric = request.getParameter("metric");
      return "Batch Explorer > Transaction drilldown > " + metricLabel(metric);
    }
    if (path.contains("/transactions/period-report")) {
      return "Transactions > Reporting-period transaction list";
    }
    if (path.contains("/transactions")) {
      return "Transactions > Transaction lookup";
    }
    if (path.endsWith("/dashboardDetails/batch-view")) {
      return "Batch View > Dashboard KPIs, trend, and attention list";
    }
    if (path.endsWith("/dashboardDetails/transaction-view")) {
      return "Transactions Overview > Dashboard KPIs, reason breakdowns, and trend";
    }
    if (path.contains("/batches")) {
      return path.endsWith("/filter-options")
          ? "Batch Explorer > Search and country filters"
          : path.endsWith("/batches")
          ? "Batch Explorer > Queue and summary"
          : "Batch Explorer > Selected batch > Data Selection / Transformation / Reconciliation";
    }
    if (path.contains("/report-configs")) {
      return "Report Config > Configuration catalogue and details";
    }
    return "System > Database health / other API";
  }

  private static String metricLabel(String metric) {
    if (metric == null) {
      return "Default transaction metric";
    }
    return switch (metric) {
      case "SELECTED" -> "Data Selection > Selected data";
      case "FILTERED" -> "Data Selection > Total exclusions";
      case "EXCLUDED" -> "Data Selection > Excluded";
      case "SIMULATED" -> "Data Selection > Simulated (SML)";
      case "ALREADY_REPORTED" -> "Data Selection > Already reported";
      case "SOFT_DEDUP" -> "Data Selection > Soft-dedup dropped";
      case "MISSING" -> "Data Selection > Missing attempts (aggregate context only)";
      default -> "Transformation / reconciliation evidence";
    };
  }
}
