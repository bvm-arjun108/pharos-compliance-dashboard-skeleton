package com.pharos.compliance.health.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.health.repository.projection.DatabaseMetadata;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 of the jOOQ-to-JDBC migration: {@link DatabaseHealthRepository} is a single
 * parameterless query, so this is a smoke test confirming it runs against a real PostgreSQL
 * instance and reports that instance's own identity, not a hardcoded/stale value.
 */
class DatabaseHealthRepositoryIntegrationTest extends PostgresIntegrationTest {
  @Test
  void reportsTheRealConnectedDatabaseAndSchema() {
    var repository = new DatabaseHealthRepository(tracingJdbcTemplate, new SqlResourceLoader(new DefaultResourceLoader()));

    DatabaseMetadata metadata = repository.getDatabaseMetadata();

    assertEquals(POSTGRES.getDatabaseName(), metadata.database());
    assertEquals("public", metadata.schema());
  }
}
