package com.pharos.compliance.common.jooq.logging;

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

  @Override
  public void executeStart(ExecuteContext context) {
    if (LOGGER.isDebugEnabled()) {
      String purpose = SqlQueryPurposeResolver.resolve();
      context.data(QUERY_PURPOSE, purpose);
      context.data(STARTED_AT_NANOS, System.nanoTime());
      LOGGER.debug("SQL query starting — {} | operation={} | bindValues=inlined\n{}", purpose, context.type(), renderQuery(context));
    }
  }

  @Override
  public void executeEnd(ExecuteContext context) {
    if (!LOGGER.isDebugEnabled()) {
      return;
    }
    Object startedAt = context.data(STARTED_AT_NANOS);
    String purpose = String.valueOf(context.data(QUERY_PURPOSE));
    long durationMs = startedAt instanceof Long startedAtNanos ? (System.nanoTime() - startedAtNanos) / 1_000_000 : -1;
    if (context.rows() >= 0) {
      LOGGER.debug("SQL query completed — {} | operation={} | affectedRows={} | duration={}ms", purpose, context.type(), context.rows(),
          durationMs);
    } else {
      LOGGER.debug("SQL query completed — {} | operation={} | duration={}ms", purpose, context.type(), durationMs);
    }
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
