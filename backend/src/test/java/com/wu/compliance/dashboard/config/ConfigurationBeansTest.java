package com.wu.compliance.dashboard.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceProperties;
import com.wu.compliance.dashboard.common.metrics.QueryPerformanceTracker;
import io.swagger.v3.oas.models.OpenAPI;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds each {@code @Configuration} bean directly so the wiring code runs without a Spring
 * context or a database.
 */
public class ConfigurationBeansTest {
  @Test
  void cacheManagerDefinesEveryNamedCache() {
    CacheManager manager = new CacheConfiguration().cacheManager();
    ((org.springframework.cache.support.SimpleCacheManager) manager).afterPropertiesSet();
    assertThat(manager.getCacheNames())
      .containsExactlyInAnyOrder(CacheConfiguration.TRANSACTION_REPORT_CONTEXT, CacheConfiguration.TRANSACTION_EVIDENCE_COUNT,
          CacheConfiguration.TRANSACTION_EVIDENCE_RECORDS, CacheConfiguration.PERIOD_TRANSACTION_AGGREGATE,
          CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_COUNT, CacheConfiguration.PERIOD_TRANSACTION_EVIDENCE_RECORDS,
          CacheConfiguration.PERIOD_TRANSACTION_BATCH_IDS, CacheConfiguration.TRANSACTION_EVIDENCE_DETAIL,
          CacheConfiguration.COUNTRY_CATALOG);
    manager.getCache(CacheConfiguration.COUNTRY_CATALOG).put("k", "v");
    assertThat(manager.getCache(CacheConfiguration.COUNTRY_CATALOG).get("k").get()).isEqualTo("v");
  }

  @Test
  void dataSourceConfiguresTheHikariPoolFromProperties() {
    PostgresProperties properties =
        new PostgresProperties("jdbc:postgresql://127.0.0.1:1/none", "org.postgresql.Driver", "u", "p", 250, 2, 5);
    // nothing listens on port 1, so the pool is configured from the properties and then fails fast
    assertThatThrownBy(() -> new DatabaseConfiguration().dataSource(properties)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void dataSourceIsReturnedOnceThePoolStarts() throws Exception {
    PostgresProperties properties = new PostgresProperties("jdbc:fake:pool", FakeDriver.class.getName(), "u", "p", 250, 2, 5);
    DataSource dataSource = new DatabaseConfiguration().dataSource(properties);
    try {
      assertThat(dataSource).isInstanceOf(com.zaxxer.hikari.HikariDataSource.class);
      assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getPoolName()).isEqualTo("pharos-postgres-pool");
      // the minimum idle count is capped at the pool maximum
      assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getMinimumIdle()).isEqualTo(2);
    } finally {
      ((com.zaxxer.hikari.HikariDataSource) dataSource).close();
    }
  }

  /**
   * A JDBC driver whose connections always validate, so a real Hikari pool can start without a database.
   */
  public static class FakeDriver implements java.sql.Driver {
    @Override
    public java.sql.Connection connect(String url, java.util.Properties info) throws java.sql.SQLException {
      java.sql.Connection connection = mock(java.sql.Connection.class);
      org.mockito.Mockito.when(connection.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
      return connection;
    }

    @Override
    public boolean acceptsURL(String url) {
      return url.startsWith("jdbc:fake:");
    }

    @Override
    public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) {
      return new java.sql.DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
      return 1;
    }

    @Override
    public int getMinorVersion() {
      return 0;
    }

    @Override
    public boolean jdbcCompliant() {
      return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }
  }

  @Test
  void tracingTemplateWrapsTheDataSource() {
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(false, null));
    assertThat(new JdbcTracingConfiguration().tracingNamedParameterJdbcTemplate(mock(DataSource.class), tracker, new ObjectMapper()))
      .isNotNull();
  }

  @Test
  void openApiDescribesTheService() {
    OpenAPI openApi = new OpenApiConfiguration().pharosOpenApi();
    assertThat(openApi.getInfo().getTitle()).isEqualTo("Pharos Compliance Operations API");
    assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
  }
}
