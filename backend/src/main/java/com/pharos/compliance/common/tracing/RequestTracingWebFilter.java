package com.pharos.compliance.common.tracing;

import com.pharos.compliance.common.tracing.TraceContextAccessor.TraceIdentifiers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestTracingWebFilter extends OncePerRequestFilter {

  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String SPAN_ID_HEADER = "X-Span-Id";

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestTracingWebFilter.class);

  private final TraceContextAccessor traceContextAccessor;

  public RequestTracingWebFilter(TraceContextAccessor traceContextAccessor) {
    this.traceContextAccessor = traceContextAccessor;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Instant startedAt = Instant.now();
    String method = request.getMethod();
    String path = request.getRequestURI();

    LOGGER.info("HTTP request started method={} path={}", method, path);

    // Set before the chain runs, not in a finally block: trace/span identifiers are available
    // synchronously at request start (TraceContextAccessor doesn't depend on downstream
    // processing), and the Servlet spec forbids setting headers once the response is committed —
    // a handler that streams its body early could otherwise commit the response before a
    // finally-block header write ever runs.
    TraceIdentifiers identifiers = traceContextAccessor.current();
    if (!"-".equals(identifiers.traceId())) {
      response.setHeader(TRACE_ID_HEADER, identifiers.traceId());
      response.setHeader(SPAN_ID_HEADER, identifiers.spanId());
    }

    String signal = "completed";
    try {
      filterChain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException exception) {
      signal = "error";
      throw exception;
    } finally {
      long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
      LOGGER.info(
          "HTTP request completed method={} path={} status={} durationMs={} signal={}",
          method,
          path,
          response.getStatus(),
          durationMs,
          signal);
    }
  }
}
