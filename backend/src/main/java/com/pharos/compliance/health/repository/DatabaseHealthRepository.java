package com.pharos.compliance.health.repository;

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
  public DatabaseMetadata getDatabaseMetadata() {
    return dsl.select(
            DSL.field("current_database()", String.class).as("database"),
            DSL.field("current_schema()", String.class).as("schema"))
        .fetchOne(r -> new DatabaseMetadata(r.value1(), r.value2()));
  }

  public record DatabaseMetadata(String database, String schema) {}
}
