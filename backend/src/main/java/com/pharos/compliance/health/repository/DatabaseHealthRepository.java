package com.pharos.compliance.health.repository;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.health.repository.projection.DatabaseMetadata;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatabaseHealthRepository {
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public DatabaseHealthRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  @Transactional(readOnly = true)
  @SqlQueryPurpose("Validate PostgreSQL connectivity and identify the active database schema")
  public DatabaseMetadata getDatabaseMetadata() {
    return jdbc
      .queryForOptional(sql.load("sql/health/get-database-metadata.sql"), new MapSqlParameterSource(), (rs, rowNum) -> new DatabaseMetadata(rs.getString(
              "database"), rs.getString("schema")))
      .orElseThrow(() -> new IllegalStateException("Database metadata query returned no row"));
  }
}
