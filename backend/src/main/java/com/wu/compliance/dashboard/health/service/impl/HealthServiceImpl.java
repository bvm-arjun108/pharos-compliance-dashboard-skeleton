package com.wu.compliance.dashboard.health.service.impl;

import com.wu.compliance.dashboard.common.exception.DatabaseUnavailableException;
import com.wu.compliance.dashboard.health.dto.HealthResponse;
import com.wu.compliance.dashboard.health.repository.DatabaseHealthRepository;
import com.wu.compliance.dashboard.health.service.HealthService;
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
