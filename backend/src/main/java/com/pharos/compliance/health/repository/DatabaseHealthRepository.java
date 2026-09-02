package com.pharos.compliance.health.repository;

import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.health.repository.projection.DatabaseMetadata;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatabaseHealthRepository {
  private final DSLContext dsl;

  public DatabaseHealthRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Transactional(readOnly = true)
  @SqlQueryPurpose("Validate PostgreSQL connectivity and identify the active database schema")
  public DatabaseMetadata getDatabaseMetadata() {
    return dsl
      .select(DSL.field("current_database()", String.class).as("database"), DSL.field("current_schema()", String.class).as("schema"))
      .fetchOptional(r -> new DatabaseMetadata(r.value1(), r.value2()))
      .orElseThrow(() -> new IllegalStateException("Database metadata query returned no row"));
  }
}
