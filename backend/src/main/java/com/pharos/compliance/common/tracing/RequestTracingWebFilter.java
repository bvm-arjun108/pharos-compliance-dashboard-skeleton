package com.pharos.compliance.common.tracing;

import com.pharos.compliance.common.tracing.TraceContextAccessor.TraceIdentifiers;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestTracingWebFilter implements WebFilter {

  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String SPAN_ID_HEADER = "X-Span-Id";

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestTracingWebFilter.class);

  private final TraceContextAccessor traceContextAccessor;

  public RequestTracingWebFilter(TraceContextAccessor traceContextAccessor) {
    this.traceContextAccessor = traceContextAccessor;
  }

  @Override
  @NonNull public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
    Instant startedAt = Instant.now();
    String method = exchange.getRequest().getMethod().name();
    String path = exchange.getRequest().getPath().value();

    LOGGER.info("HTTP request started method={} path={}", method, path);
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              TraceIdentifiers identifiers = traceContextAccessor.current();
              if (!"-".equals(identifiers.traceId())) {
                exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, identifiers.traceId());
                exchange.getResponse().getHeaders().set(SPAN_ID_HEADER, identifiers.spanId());
              }
              return Mono.empty();
            });

    return chain
        .filter(exchange)
        .doFinally(
            signalType -> {
              HttpStatusCode status = exchange.getResponse().getStatusCode();
              long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
              LOGGER.info(
                  "HTTP request completed method={} path={} status={} durationMs={} signal={}",
                  method,
                  path,
                  status == null ? 200 : status.value(),
                  durationMs,
                  signalType);
            });
  }
}
