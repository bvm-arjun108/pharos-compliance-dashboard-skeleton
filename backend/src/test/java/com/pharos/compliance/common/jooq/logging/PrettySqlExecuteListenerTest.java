package com.pharos.compliance.common.jooq.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharos.compliance.common.jooq.metrics.QueryPerformanceTracker;
import java.util.HashMap;
import java.util.Map;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PrettySqlExecuteListenerTest {
  @Test
  void logsActualRowsAsPrettyJsonWithoutChangingResult() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(PrettySqlExecuteListener.class);
    Level previous = logger.getLevel();
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      var dsl = DSL.using(SQLDialect.POSTGRES);
      var reportGroupId = DSL.field("reportGroupId", Integer.class);
      var batchesNeedingAttention = DSL.field("batchesNeedingAttention", Integer.class);
      var first = dsl.newRecord(reportGroupId, batchesNeedingAttention).values(1573742369, 4);
      var second = dsl.newRecord(reportGroupId, batchesNeedingAttention).values(1573742370, 2);
      ExecuteContext context = mock(ExecuteContext.class);
      Map<Object, Object> contextData = new HashMap<>();
      when(context.data(any())).thenAnswer(invocation -> contextData.get(invocation.getArgument(0)));
      doAnswer(invocation -> contextData.put(invocation.getArgument(0), invocation.getArgument(1))).when(context).data(any(), any());
      when(context.configuration()).thenReturn(dsl.configuration());
      when(context.record()).thenReturn(first, second);
      var listener = new PrettySqlExecuteListener(mock(QueryPerformanceTracker.class));
      listener.fetchStart(context);
      listener.recordEnd(context);
      listener.recordEnd(context);
      listener.fetchEnd(context);
      String message = appender.list.getFirst().getFormattedMessage();
      String json = message.substring(message.indexOf('\n') + 1);
      var parsed = new ObjectMapper().readTree(json);
      assertThat(parsed).hasSize(2);
      assertThat(parsed.get(0).get("reportGroupId").asInt()).isEqualTo(1573742369);
      assertThat(parsed.get(1).get("batchesNeedingAttention").asInt()).isEqualTo(2);
      assertThat(json).contains("\n");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
      appender.stop();
    }
  }

  @Test
  void identifiesDataSelectionMetricAndDoesNotLeakRequestContext() {
    var request = new MockHttpServletRequest("GET", "/api/v1/transactions/report");
    request.setParameter("metric", "EXCLUDED");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      assertThat(SqlUiSectionResolver.resolve()).isEqualTo("Batch Explorer > Transaction drilldown > Data Selection > Excluded");
      request.setParameter("metric", "MISSING");
      assertThat(SqlUiSectionResolver.resolve()).contains("Missing attempts (aggregate context only)");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    assertThat(SqlUiSectionResolver.resolve()).contains("non-HTTP");
  }
}
