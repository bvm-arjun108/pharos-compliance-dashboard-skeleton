package com.pharos.compliance.common.jooq.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes the complete request-level query timing summary as one JSON log message.
 */
@Component
public class QueryPerformanceSummaryLogger {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryPerformanceSummaryLogger.class);
  private final ObjectMapper objectMapper;

  public QueryPerformanceSummaryLogger(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void log(QueryPerformanceSummary summary) {
    try {
      LOGGER.info("{}", objectMapper.writeValueAsString(summary));
    } catch (JsonProcessingException exception) {
      LOGGER.warn("Database query performance summary could not be serialized | view={} | endpoint={} | queryCount={}", summary.view(),
          summary.endpoint(), summary.queryCount(), exception);
    }
  }
}
