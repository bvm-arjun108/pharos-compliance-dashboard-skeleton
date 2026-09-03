package com.pharos.compliance.common.jooq.logging;

import com.pharos.compliance.common.jooq.metrics.QueryPerformanceTracker;
import java.io.Serial;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Query;
import org.jooq.conf.Settings;
import org.jooq.conf.SettingsTools;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the SQL that jOOQ sends to PostgreSQL, with bind values inlined for local debugging.
 */
public final class PrettySqlExecuteListener implements ExecuteListener {
  @Serial
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = LoggerFactory.getLogger(PrettySqlExecuteListener.class);
  private static final String QUERY_PURPOSE = PrettySqlExecuteListener.class.getName() + ".queryPurpose";
  private static final String STARTED_AT_NANOS = PrettySqlExecuteListener.class.getName() + ".startedAtNanos";
  private static final String QUERY_FAILED = PrettySqlExecuteListener.class.getName() + ".queryFailed";
  private final QueryPerformanceTracker queryPerformanceTracker;

  public PrettySqlExecuteListener(QueryPerformanceTracker queryPerformanceTracker) {
    this.queryPerformanceTracker = queryPerformanceTracker;
  }

  @Override
  public void executeStart(ExecuteContext context) {
    if (queryPerformanceTracker.isRequestActive() || LOGGER.isDebugEnabled()) {
      context.data(QUERY_PURPOSE, SqlQueryPurposeResolver.resolve());
    }
    if (LOGGER.isDebugEnabled()) {
      String purpose = String.valueOf(context.data(QUERY_PURPOSE));
      LOGGER.debug("SQL query starting — {} | operation={} | bindValues=inlined\n{}", purpose, context.type(), renderQuery(context));
    }
    if (queryPerformanceTracker.isRequestActive() || LOGGER.isDebugEnabled()) {
      // Start after formatting the DEBUG SQL so rendering a large statement is not mistaken for
      // database execution time.
      context.data(STARTED_AT_NANOS, System.nanoTime());
    }
  }

  @Override
  public void executeEnd(ExecuteContext context) {
    Object startedAt = context.data(STARTED_AT_NANOS);
    if (!(startedAt instanceof Long startedAtNanos)) {
      return;
    }
    String purpose = String.valueOf(context.data(QUERY_PURPOSE));
    long durationNanos = System.nanoTime() - startedAtNanos;
    Integer rows = context.rows() >= 0 ? context.rows() : null;
    queryPerformanceTracker.recordQuery(purpose, context.type().name(), durationNanos, rows, Boolean.TRUE.equals(context.data(QUERY_FAILED)));

    if (!LOGGER.isDebugEnabled()) {
      return;
    }
    long durationMs = durationNanos / 1_000_000;
    if (context.rows() >= 0) {
      LOGGER.debug("SQL query completed — {} | operation={} | affectedRows={} | duration={}ms", purpose, context.type(), context.rows(),
          durationMs);
    } else {
      LOGGER.debug("SQL query completed — {} | operation={} | duration={}ms", purpose, context.type(), durationMs);
    }
  }

  @Override
  public void exception(ExecuteContext context) {
    context.data(QUERY_FAILED, true);
  }

  private static String renderQuery(ExecuteContext context) {
    Query query = context.query();
    if (query == null) {
      return context.sql();
    }

    try {
      Settings prettySettings = SettingsTools.clone(context.settings()).withRenderFormatted(true);
      DSLContext prettyDsl = DSL.using(context.configuration().derive(prettySettings));
      return prettyDsl.renderInlined(query);
    } catch (RuntimeException ignored) {
      // Diagnostics must never prevent the real query from running. The prepared SQL is still
      // useful if a dialect-specific value cannot be rendered inline.
      return context.sql();
    }
  }
}
