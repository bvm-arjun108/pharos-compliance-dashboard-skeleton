package com.pharos.compliance.common.api;

/**
 * Shared tracing-header documentation for OpenAPI responses.
 */
public final class OpenApiHeaders {
  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String TRACE_ID_DESCRIPTION = "Distributed trace identifier";
  public static final String SPAN_ID_HEADER = "X-Span-Id";
  public static final String SPAN_ID_DESCRIPTION = "Current server span identifier";

  private OpenApiHeaders() {
  }
}
