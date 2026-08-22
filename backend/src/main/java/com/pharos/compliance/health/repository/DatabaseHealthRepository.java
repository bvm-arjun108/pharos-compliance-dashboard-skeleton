package com.pharos.compliance.health.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatabaseHealthRepository {

  private static final String DATABASE_HEALTH_SQL =
      "SELECT current_database() AS database, current_schema() AS schema";

  private final EntityManager entityManager;

  public DatabaseHealthRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public DatabaseMetadata getDatabaseMetadata() {
    Object[] result =
        (Object[]) entityManager.createNativeQuery(DATABASE_HEALTH_SQL).getSingleResult();
    return new DatabaseMetadata((String) result[0], (String) result[1]);
  }

  public record DatabaseMetadata(String database, String schema) {}
}
