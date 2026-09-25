package com.pharos.compliance.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.metrics.QueryPerformanceProperties;
import com.pharos.compliance.common.metrics.QueryPerformanceTracker;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Base class for tests that need to run hand-written SQL against a real PostgreSQL instance,
 * rather than only inspecting query text and bind values.
 *
 * <p>One container per test class (Testcontainers starts it once in {@code @BeforeAll} via the
 * {@code @Container} field and reuses it for every {@code @Test} in the class). Schema is seeded
 * from {@code src/test/resources/testcontainers/pharos-schema.sql} -- a schema-only {@code
 * pg_dump} of the {@code pharos} schema from the project's own mock database (there is no Flyway
 * or other migration tooling in this repository to reuse) -- copied into {@code
 * /docker-entrypoint-initdb.d/} so the official postgres image's own startup script applies it via
 * {@code psql} (not Testcontainers' JDBC-based script runner, which cannot execute the dump's
 * own psql-only "restrict"/"unrestrict" meta-commands).
 *
 * <p>No Spring {@code ApplicationContext} is started here; the fixture uses a plain JDBC
 * connection so repository SQL tests remain focused and fast.
 */
@Testcontainers
@Tag("integration")
public abstract class PostgresIntegrationTest {
  @Container
  protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
    .withCopyFileToContainer(MountableFile.forClasspathResource("testcontainers/pharos-schema.sql"),
        "/docker-entrypoint-initdb.d/01-schema.sql");
  protected static DataSource dataSource;
  protected static NamedParameterJdbcTemplate jdbcTemplate;
  protected static TracingNamedParameterJdbcTemplate tracingJdbcTemplate;

  @BeforeAll
  static void setUpJdbcTemplates() {
    DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
    driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
    driverManagerDataSource.setUsername(POSTGRES.getUsername());
    driverManagerDataSource.setPassword(POSTGRES.getPassword());
    dataSource = driverManagerDataSource;
    jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    QueryPerformanceTracker tracker = new QueryPerformanceTracker(new QueryPerformanceProperties(false, null));
    tracingJdbcTemplate = new TracingNamedParameterJdbcTemplate(jdbcTemplate, tracker, new ObjectMapper());
  }
}
