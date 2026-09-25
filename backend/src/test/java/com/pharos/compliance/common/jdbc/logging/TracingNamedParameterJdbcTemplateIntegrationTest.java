package com.pharos.compliance.common.jdbc.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.metrics.QueryPerformanceProperties;
import com.pharos.compliance.common.metrics.QueryPerformanceTracker;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Proves the Phase 0 JDBC-migration infrastructure end to end against a real PostgreSQL instance
 * (see {@link PostgresIntegrationTest}): a {@code .sql} resource file loaded once and executed
 * through {@link TracingNamedParameterJdbcTemplate}, with the same performance-tracking and
 * DEBUG-gated diagnostic behavior {@code PrettySqlExecuteListener} provides for jOOQ today.
 */
class TracingNamedParameterJdbcTemplateIntegrationTest extends PostgresIntegrationTest {
  private static final SqlResourceLoader SQL_RESOURCES = new SqlResourceLoader(new DefaultResourceLoader());
  private Logger tracingLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachLogAppender() {
    tracingLogger = (Logger) LoggerFactory.getLogger(TracingNamedParameterJdbcTemplate.class);
    originalLevel = tracingLogger.getLevel();
    appender = new ListAppender<>();
    appender.start();
    tracingLogger.addAppender(appender);
  }

  @AfterEach
  void restoreLogging() {
    tracingLogger.detachAppender(appender);
    tracingLogger.setLevel(originalLevel);
  }

  @Test
  void selectOneSmokeQueryRunsAgainstRealPostgres() {
    String sql = SQL_RESOURCES.load("sql/health/select-one.sql");

    List<Integer> results = tracingJdbcTemplate.query(sql, new MapSqlParameterSource(), (rs, rowNum) -> rs.getInt("one"));

    assertEquals(List.of(1), results);
  }

  @Test
  void recordsQueryPerformanceOnlyWhileARequestIsActive() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, null));
    var tracing = new TracingNamedParameterJdbcTemplate(jdbcTemplate, tracker, new ObjectMapper());
    tracker.beginRequest("GET", "/api/v1/health", "trace-1", "span-1");

    tracing.query(SQL_RESOURCES.load("sql/health/select-one.sql"), new MapSqlParameterSource(), (rs, rowNum) -> rs.getInt("one"));

    var summary = tracker.completeRequest(200);
    assertTrue(summary.isPresent(), "a query ran while the request was active, so a summary must be recorded");
    assertEquals(1, summary.get().queryCount());
  }

  @Test
  void doesNotRecordAnythingWhenNoRequestIsActiveAndDebugIsOff() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, null));
    var tracing = new TracingNamedParameterJdbcTemplate(jdbcTemplate, tracker, new ObjectMapper());

    tracing.query(SQL_RESOURCES.load("sql/health/select-one.sql"), new MapSqlParameterSource(), (rs, rowNum) -> rs.getInt("one"));

    assertTrue(tracker.completeRequest(200).isEmpty(), "no beginRequest was called, so nothing should be tracked");
  }

  @Test
  void debugLoggingInlinesBindValuesAndDumpsRowsAsJson() {
    tracingLogger.setLevel(Level.DEBUG);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_group_config where rpt_grp_id = 999");
    jdbcTemplate.update("insert into pharos.report_group_config (rpt_grp_id, rpt_selection_version_id, transformer_version_id, rpt_grp_name) "
        + "values (:id, 1, 'v1', :name)", new MapSqlParameterSource().addValue("id", 999).addValue("name", "Debug Test Group"));

    List<String> names = tracingJdbcTemplate.query("select rpt_grp_name from pharos.report_group_config where rpt_grp_id = :id", new MapSqlParameterSource()
      .addValue("id", 999), (rs, rowNum) -> rs.getString("rpt_grp_name"));

    assertEquals(List.of("Debug Test Group"), names);
    String logged = appender.list
      .stream()
      .map(ILoggingEvent::getFormattedMessage)
      .reduce("", (a, b) -> a + "\n" + b);
    assertTrue(logged.contains("SQL query starting"), "should log the starting event: " + logged);
    assertTrue(logged.contains("999"), "bind value should be inlined into the logged SQL: " + logged);
    assertTrue(logged.contains("SQL result"), "should log the row dump: " + logged);
    assertTrue(logged.contains("Debug Test Group"), "row JSON dump should contain the actual row data: " + logged);
  }

  @Test
  void queryForOptionalThrowsWhenMoreThanOneRowMatches() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_group_config where rpt_grp_id = 998");
    jdbcTemplate.update("insert into pharos.report_group_config (rpt_grp_id, rpt_selection_version_id, transformer_version_id) values (:"
        + "id, 1, 'v1')", new MapSqlParameterSource("id", 998));
    jdbcTemplate.update("insert into pharos.report_group_config (rpt_grp_id, rpt_selection_version_id, transformer_version_id) values (:"
        + "id, 2, 'v1')", new MapSqlParameterSource("id", 998));

    assertThrows(IncorrectResultSizeDataAccessException.class, () -> tracingJdbcTemplate.queryForOptional("select rpt_grp_id from pharos."
        + "report_group_config where rpt_grp_id = :id", new MapSqlParameterSource("id", 998), (rs, rowNum) -> rs.getInt("rpt_grp_id")));
  }

  @Test
  void queryForScalarReturnsASingleValue() {
    Long count = tracingJdbcTemplate.queryForScalar("select count(*) from pharos.report_group_config where rpt_grp_id = :id",
        new MapSqlParameterSource("id", -1), Long.class);

    assertEquals(0L, count);
  }
}
