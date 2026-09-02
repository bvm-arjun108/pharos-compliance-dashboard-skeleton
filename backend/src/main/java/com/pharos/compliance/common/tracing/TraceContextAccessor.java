package com.pharos.compliance.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
public class TraceContextAccessor {
  private static final String UNAVAILABLE = "-";
  private final Tracer tracer;

  public TraceContextAccessor(Tracer tracer) {
    this.tracer = tracer;
  }

  public TraceIdentifiers current() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return new TraceIdentifiers(UNAVAILABLE, UNAVAILABLE);
    }
    return new TraceIdentifiers(span.context().traceId(), span.context().spanId());
  }

  public record TraceIdentifiers(String traceId, String spanId) {}
}
