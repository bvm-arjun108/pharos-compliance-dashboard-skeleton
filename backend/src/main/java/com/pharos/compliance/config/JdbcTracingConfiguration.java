package com.pharos.compliance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.metrics.QueryPerformanceProperties;
import com.pharos.compliance.common.metrics.QueryPerformanceTracker;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Wires the tracing wrapper (see {@link TracingNamedParameterJdbcTemplate}) as the single {@link
 * NamedParameterJdbcTemplate}-shaped bean every repository is constructed with, and enables {@link
 * QueryPerformanceProperties} -- previously enabled by the jOOQ-era {@code
 * JooqLoggingConfiguration}, deleted once the jOOQ-to-JDBC migration finished and every repository
 * had moved to this wrapper.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryPerformanceProperties.class)
public class JdbcTracingConfiguration {
  @Bean
  TracingNamedParameterJdbcTemplate tracingNamedParameterJdbcTemplate(DataSource dataSource, QueryPerformanceTracker queryPerformanceTracker,
      ObjectMapper objectMapper) {
    return new TracingNamedParameterJdbcTemplate(new NamedParameterJdbcTemplate(dataSource), queryPerformanceTracker, objectMapper);
  }
}
