package com.wu.compliance.dashboard.common.jdbc.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceProperties;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceTracker;
import com.wu.compliance.dashboard.testsupport.DebugLogging;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit coverage for the tracing wrapper's failure, parameter-rendering and serialisation branches,
 * driven through a mocked delegate so no database is needed.
 */
class TracingNamedParameterJdbcTemplateTest {
  private static final RowMapper<String> MAPPER = (rs, row) -> "x";
  private final NamedParameterJdbcTemplate delegate = mock(NamedParameterJdbcTemplate.class);
  private final QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(true, null));
  private final TracingNamedParameterJdbcTemplate template = new TracingNamedParameterJdbcTemplate(delegate, tracker, new ObjectMapper());

  @Test
  void aFailedQueryIsRecordedAsFailedAndRethrown() {
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), eq(MAPPER))).thenThrow(new DataRetrievalFailureException("down"));
    tracker.beginRequest("GET", "/api/v1/health", "t", "s");
    assertThatThrownBy(() -> template.query("select 1", new MapSqlParameterSource(), MAPPER)).isInstanceOf(
        DataRetrievalFailureException.class);
    assertThat(tracker.completeRequest(500).orElseThrow().failedQueryCount()).isEqualTo(1);
  }

  @Test
  void aFailedQueryUnderDebugLoggingIsStillRethrown() {
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), eq(MAPPER))).thenThrow(new DataRetrievalFailureException("down"));
    try (DebugLogging ignored = DebugLogging.enable()) {
      assertThatThrownBy(() -> template.query("select :a", new MapSqlParameterSource("a", 1), MAPPER))
        .isInstanceOf(DataRetrievalFailureException.class);
    }
  }

  @Test
  void debugRenderingHandlesEveryBindValueShape() {
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), eq(MAPPER))).thenReturn(List.of("a", "b"));
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("n", 5)
      .addValue("flag", true)
      .addValue("text", "O'Brien")
      .addValue("nothing", null)
      .addValue("ids", List.of(1, 2))
      .addValue("none", Set.of());
    try (DebugLogging ignored = DebugLogging.enable()) {
      List<String> rows = template.query("select :n, :flag, :text, :nothing, :ids, :none, :unbound", params, MAPPER);
      assertThat(rows).containsExactly("a", "b");
    }
  }

  @Test
  void anUnserialisableResultNeverBreaksASuccessfulQuery() {
    RowMapper<Object> unserialisable = (rs, row) -> new Object();
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), eq(unserialisable))).thenReturn(List.of(new Object() {
      public Object getSelf() {
        throw new IllegalStateException("cannot serialise");
      }
    }));
    try (DebugLogging ignored = DebugLogging.enable()) {
      assertThat(template.query("select 1", new MapSqlParameterSource(), unserialisable)).hasSize(1);
    }
  }

  @Test
  void scalarQueriesAreTracedUnderDebugAndWithoutIt() {
    when(delegate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(7L);
    assertThat(template.queryForScalar("select 7", new MapSqlParameterSource(), Long.class)).isEqualTo(7L);
    try (DebugLogging ignored = DebugLogging.enable()) {
      assertThat(template.queryForScalar("select 7", new MapSqlParameterSource(), Long.class)).isEqualTo(7L);
    }
  }

  @Test
  void optionalQueriesReturnEmptyOneOrFail() {
    when(delegate.query(anyString(), any(MapSqlParameterSource.class), eq(MAPPER))).thenReturn(List.of(), List.of("a"), List.of("a", "b"));
    assertThat(template.queryForOptional("q", new MapSqlParameterSource(), MAPPER)).isEmpty();
    assertThat(template.queryForOptional("q", new MapSqlParameterSource(), MAPPER)).isEqualTo(Optional.of("a"));
    assertThatThrownBy(() -> template.queryForOptional("q", new MapSqlParameterSource(), MAPPER))
      .isInstanceOf(IncorrectResultSizeDataAccessException.class);
  }

  @Test
  void aMissingCapturedResultIsNotLogged() throws Exception {
    var logResult = TracingNamedParameterJdbcTemplate.class
      .getDeclaredMethod("logResult", String.class, String.class, String.class, int.class, Object.class);
    logResult.setAccessible(true);
    logResult.invoke(template, "section", "purpose", "id", 0, null);
  }
}
