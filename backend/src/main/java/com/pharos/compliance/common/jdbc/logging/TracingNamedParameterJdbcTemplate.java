package com.pharos.compliance.common.jdbc.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pharos.compliance.common.metrics.QueryPerformanceTracker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Wraps {@link NamedParameterJdbcTemplate} so every repository query is timed and traced through
 * the same {@link QueryPerformanceTracker#recordQuery} call (gated only by {@link
 * QueryPerformanceTracker#isRequestActive()}, independent of log level), the same {@link
 * SqlQueryPurposeResolver}/{@link SqlUiSectionResolver} resolution, and the same DEBUG-gated
 * inlined-SQL-plus-row-JSON-dump behavior, carrying the same PII caveat: that dump is opt-in-only
 * (see {@code application.yml}'s {@code SQL_LOG_LEVEL}) because it is a second copy of customer
 * data outside the database's own access controls.
 *
 * <p>A composition wrapper, not a subclass, and not Spring AOP on repository methods: the two-pass
 * evidence pagination issues more than one JDBC call per repository method, and per-method AOP
 * would collapse that into one aggregate timing, changing what {@link QueryPerformanceTracker}
 * observes. Wrapping at the same layer every repository already calls into keeps one recorded
 * query per actual statement, exactly as today.
 */
public class TracingNamedParameterJdbcTemplate {
  private static final Logger LOGGER = LoggerFactory.getLogger(TracingNamedParameterJdbcTemplate.class);
  private static final String OPERATION = "READ";
  private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");
  private final NamedParameterJdbcTemplate delegate;
  private final QueryPerformanceTracker queryPerformanceTracker;
  private final ObjectMapper objectMapper;

  public TracingNamedParameterJdbcTemplate(NamedParameterJdbcTemplate delegate, QueryPerformanceTracker queryPerformanceTracker,
      ObjectMapper objectMapper) {
    this.delegate = delegate;
    this.queryPerformanceTracker = queryPerformanceTracker;
    this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * Maps every row with {@code rowMapper}.
   */
  public <T> List<T> query(String sql, MapSqlParameterSource params, RowMapper<T> rowMapper) {
    return traced(sql, params, () -> {
      List<T> results = delegate.query(sql, params, rowMapper);
      Object captured = LOGGER.isDebugEnabled() ? results : null;
      return new Executed<>(results, results.size(), captured);
    });
  }

  /**
   * At most one row expected -- the replacement for jOOQ's {@code fetchOptional(mapper)}.
   *
   * @throws IncorrectResultSizeDataAccessException if more than one row matches.
   */
  public <T> Optional<T> queryForOptional(String sql, MapSqlParameterSource params, RowMapper<T> rowMapper) {
    List<T> results = query(sql, params, rowMapper);
    if (results.size() > 1) {
      throw new IncorrectResultSizeDataAccessException(1, results.size());
    }
    return results.stream().findFirst();
  }

  /**
   * A single scalar column, e.g. {@code SELECT count(*)}.
   */
  public <T> T queryForScalar(String sql, MapSqlParameterSource params, Class<T> requiredType) {
    return traced(sql, params, () -> {
      T result = delegate.queryForObject(sql, params, requiredType);
      Object captured = LOGGER.isDebugEnabled() ? List.of(java.util.Map.of("value", String.valueOf(result))) : null;
      return new Executed<>(result, 1, captured);
    });
  }

  private record Executed<T>(T value, int rows, Object capturedResult) {}

  private <T> T traced(String sql, MapSqlParameterSource params, SqlSupplier<Executed<T>> execution) {
    boolean tracking = queryPerformanceTracker.isRequestActive();
    boolean debug = LOGGER.isDebugEnabled();
    if (!tracking && !debug) {
      return unwrap(execution);
    }

    String purpose = SqlQueryPurposeResolver.resolve();
    String queryId = debug ? UUID.randomUUID().toString() : null;
    String uiSection = debug ? SqlUiSectionResolver.resolve() : null;
    if (debug) {
      LOGGER.debug("SQL query starting | uiSection={} | purpose={} | queryId={} | operation={} | bindValues=inlined\n{}", uiSection, purpose,
          queryId, OPERATION, inline(sql, params));
    }

    long startedAtNanos = System.nanoTime();
    boolean failed = false;
    Executed<T> executed = null;
    try {
      executed = execution.get();
      return executed.value();
    } catch (RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      long durationNanos = System.nanoTime() - startedAtNanos;
      Integer rows = executed == null ? null : executed.rows();
      queryPerformanceTracker.recordQuery(purpose, OPERATION, durationNanos, rows, failed);
      if (debug) {
        if (failed) {
          LOGGER.debug("SQL query completed — {} | operation={} | duration={}ms | outcome=FAILED", purpose, OPERATION,
              durationNanos / 1_000_000);
        } else {
          LOGGER.debug("SQL query completed — {} | operation={} | affectedRows={} | duration={}ms", purpose, OPERATION, executed.rows(),
              durationNanos / 1_000_000);
          logResult(uiSection, purpose, queryId, executed.rows(), executed.capturedResult());
        }
      }
    }
  }

  private <T> T unwrap(SqlSupplier<Executed<T>> execution) {
    return execution.get().value();
  }

  private void logResult(String uiSection, String purpose, String queryId, int returnedRows, Object result) {
    if (result == null) {
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(result);
      LOGGER.debug("SQL result | uiSection={} | purpose={} | queryId={} | returnedRows={} | format=JSON\n{}", uiSection, purpose, queryId,
          returnedRows, json);
    } catch (RuntimeException | JsonProcessingException e) {
      // Diagnostic serialization must not change a successful database response.
      LOGGER.debug("SQL result could not be formatted | uiSection={} | queryId={}", uiSection, queryId);
    }
  }

  /**
   * Best-effort, DEBUG-only display rendering of {@code sql} with each {@code :name} placeholder
   * substituted by its bound value -- never used for actual execution (the real call always goes
   * through {@code NamedParameterJdbcTemplate}'s own parameterized binding), so this has no
   * injection surface of its own.
   */
  private static String inline(String sql, MapSqlParameterSource params) {
    Matcher matcher = NAMED_PARAM.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String replacement = params.hasValue(name) ? literal(params.getValue(name)) : matcher.group();
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String literal(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    if (value instanceof java.util.Collection<?> collection) {
      return collection
        .stream()
        .map(TracingNamedParameterJdbcTemplate::literal)
        .reduce((a, b) -> a + ", " + b)
        .map(s -> "(" + s + ")")
        .orElse("()");
    }
    return "'" + value.toString().replace("'", "''") + "'";
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get();
  }
}
