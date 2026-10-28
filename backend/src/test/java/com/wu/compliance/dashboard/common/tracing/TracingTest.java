package com.wu.compliance.dashboard.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.metrics.QueryExecutionTiming;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceSummary;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceSummaryLogger;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceTracker;
import com.wu.compliance.dashboard.common.tracing.TraceContextAccessor.TraceIdentifiers;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TracingTest {
  private final TraceContextAccessor accessor = mock(TraceContextAccessor.class);
  private final QueryPerformanceTracker tracker = mock(QueryPerformanceTracker.class);
  private final QueryPerformanceSummaryLogger summaryLogger = mock(QueryPerformanceSummaryLogger.class);
  private final RequestTracingWebFilter filter = new RequestTracingWebFilter(accessor, tracker, summaryLogger);

  @Test
  void accessorReturnsPlaceholdersWithoutASpan() {
    TraceIdentifiers identifiers = new TraceContextAccessor(mock(Tracer.class)).current();
    assertThat(identifiers.traceId()).isEqualTo("-");
    assertThat(identifiers.spanId()).isEqualTo("-");
  }

  @Test
  void accessorReadsTheCurrentSpan() {
    Tracer tracer = mock(Tracer.class);
    Span span = mock(Span.class);
    TraceContext context = mock(TraceContext.class);
    when(tracer.currentSpan()).thenReturn(span);
    when(span.context()).thenReturn(context);
    when(context.traceId()).thenReturn("t1");
    when(context.spanId()).thenReturn("s1");
    assertThat(new TraceContextAccessor(tracer).current()).isEqualTo(new TraceIdentifiers("t1", "s1"));
  }

  private MockHttpServletResponse run(int status, TraceIdentifiers identifiers, FilterChain chain) throws Exception {
    when(accessor.current()).thenReturn(identifiers);
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(status);
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/health"), response, chain);
    return response;
  }

  @Test
  void successfulRequestEchoesTraceHeadersAndLogsTheSummary() throws Exception {
    QueryExecutionTiming timing = new QueryExecutionTiming(1, "q", "READ", 1.0, 100.0, 1, "SUCCESS", false);
    QueryPerformanceSummary summary =
        new QueryPerformanceSummary("e", "v", "/x", "GET", 200, "t", "s", 1, 1.0, 1.0, 100.0, 250.0, 0, 0, timing, java.util.List.of(timing));
    when(tracker.completeRequest(200)).thenReturn(Optional.of(summary));
    MockHttpServletResponse response = run(200, new TraceIdentifiers("t", "s"), (req, res) -> {});
    assertThat(response.getHeader(RequestTracingWebFilter.TRACE_ID_HEADER)).isEqualTo("t");
    assertThat(response.getHeader(RequestTracingWebFilter.SPAN_ID_HEADER)).isEqualTo("s");
    verify(tracker).beginRequest("GET", "/api/v1/health", "t", "s");
    verify(summaryLogger).log(summary);
  }

  @Test
  void headersAreOmittedWhenThereIsNoTrace() throws Exception {
    when(tracker.completeRequest(404)).thenReturn(Optional.empty());
    MockHttpServletResponse response = run(404, new TraceIdentifiers("-", "-"), (req, res) -> {});
    assertThat(response.getHeader(RequestTracingWebFilter.TRACE_ID_HEADER)).isNull();
    verify(summaryLogger, never()).log(any());
  }

  @Test
  void serverErrorStatusAndThrownExceptionsStillCompleteTheRequest() throws Exception {
    when(tracker.completeRequest(anyInt())).thenReturn(Optional.empty());
    TraceIdentifiers ids = new TraceIdentifiers("t", "s");
    run(503, ids, (req, res) -> {});
    verify(tracker).completeRequest(503);
    assertThatThrownBy(() -> run(200, ids, (req, res) -> {
      throw new IllegalStateException("boom");
    })).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> run(200, ids, (req, res) -> {
      throw new ServletException("servlet");
    })).isInstanceOf(ServletException.class);
    assertThatThrownBy(() -> run(200, ids, (req, res) -> {
      throw new IOException("io");
    })).isInstanceOf(IOException.class);
    verify(tracker, times(4)).completeRequest(anyInt());
  }
}
