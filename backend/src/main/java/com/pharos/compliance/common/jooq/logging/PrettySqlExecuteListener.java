package com.pharos.compliance.common.jooq.logging;

import com.pharos.compliance.common.jooq.metrics.QueryPerformanceTracker;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONFormat;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Result;
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
  private static final String QUERY_ID = PrettySqlExecuteListener.class.getName() + ".queryId";
  private static final String UI_SECTION = PrettySqlExecuteListener.class.getName() + ".uiSection";
  private static final String FETCHED_RECORDS = PrettySqlExecuteListener.class.getName() + ".fetchedRecords";
  private static final JSONFormat PRETTY_JSON = new JSONFormat().header(false).recordFormat(JSONFormat.RecordFormat.OBJECT).format(true);
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
      context.data(QUERY_ID, UUID.randomUUID().toString());
      context.data(UI_SECTION, SqlUiSectionResolver.resolve());
      String purpose = String.valueOf(context.data(QUERY_PURPOSE));
      LOGGER.debug("SQL query starting | uiSection={} | purpose={} | queryId={} | operation={} | bindValues=inlined\n{}",
          context.data(UI_SECTION), purpose, context.data(QUERY_ID), context.type(), renderQuery(context));
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
  public void fetchStart(ExecuteContext context) {
    if (LOGGER.isDebugEnabled()) {
      context.data(FETCHED_RECORDS, new ArrayList<Record>());
    }
  }

  @Override
  public void recordEnd(ExecuteContext context) {
    if (!LOGGER.isDebugEnabled()) {
      return;
    }
    Record record = context.record();
    if (record != null) {
      fetchedRecords(context).add(record);
    }
  }

  @Override
  public void fetchEnd(ExecuteContext context) {
    if (!LOGGER.isDebugEnabled()) {
      return;
    }
    List<Record> records = fetchedRecords(context);
    try {
      String json = formatAsPrettyJson(context, records);
      LOGGER.debug("SQL result | uiSection={} | purpose={} | queryId={} | returnedRows={} | format=JSON\n{}", context.data(UI_SECTION),
          context.data(QUERY_PURPOSE), context.data(QUERY_ID), records.size(), json);
    } catch (RuntimeException ignored) {
      // Diagnostic serialization must not change a successful database response.
      LOGGER.debug("SQL result could not be formatted | uiSection={} | queryId={}", context.data(UI_SECTION), context.data(QUERY_ID));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Record> fetchedRecords(ExecuteContext context) {
    Object records = context.data(FETCHED_RECORDS);
    if (records instanceof List<?>) {
      return (List<Record>) records;
    }
    List<Record> initialized = new ArrayList<>();
    context.data(FETCHED_RECORDS, initialized);
    return initialized;
  }

  private static String formatAsPrettyJson(ExecuteContext context, List<Record> records) {
    if (records.isEmpty()) {
      return "[]";
    }
    Result<Record> result = DSL.using(context.configuration()).newResult(records.getFirst().fields());
    result.addAll(records);
    return result.formatJSON(PRETTY_JSON);
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
