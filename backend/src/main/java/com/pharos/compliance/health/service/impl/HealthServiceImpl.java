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
      var metadata = databaseHealthRepository.getDatabaseMetadata();
      HealthResponse response =
          new HealthResponse(
              "UP",
              "pharos-compliance-backend",
              metadata.database(),
              metadata.schema(),
              OffsetDateTime.now());
      LOGGER.info(
          "PostgreSQL health check completed database={} schema={} status={}",
          response.database(),
          response.schema(),
          response.status());
      return response;
    } catch (DatabaseUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new DatabaseUnavailableException(
          "Unable to validate the PostgreSQL connection", exception);
    }
  }
}
