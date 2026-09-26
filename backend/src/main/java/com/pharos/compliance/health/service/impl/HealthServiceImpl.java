package com.pharos.compliance.health.service.impl;

import com.pharos.compliance.common.exception.DatabaseUnavailableException;
import com.pharos.compliance.health.dto.HealthResponse;
import com.pharos.compliance.health.repository.DatabaseHealthRepository;
import com.pharos.compliance.health.service.HealthService;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {
  private static final Logger LOGGER = LoggerFactory.getLogger(HealthServiceImpl.class);
  private final DatabaseHealthRepository databaseHealthRepository;

  public HealthServiceImpl(DatabaseHealthRepository databaseHealthRepository) {
    this.databaseHealthRepository = databaseHealthRepository;
  }

  @Override
  public HealthResponse getHealth() {
    try {
      var databaseMetadata = databaseHealthRepository.getDatabaseMetadata();
      HealthResponse healthResponse =
          new HealthResponse("UP", "pharos-compliance-backend", databaseMetadata.database(), databaseMetadata.schema(), OffsetDateTime.now());
      LOGGER.debug("Database health check passed | database={} | schema={} | status={}", healthResponse.database(), healthResponse.schema(),
          healthResponse.status());
      return healthResponse;
    } catch (DatabaseUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new DatabaseUnavailableException("Unable to validate the PostgreSQL connection", exception);
    }
  }
}
