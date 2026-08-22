package com.pharos.compliance.health.service.impl;

import com.pharos.compliance.common.exception.DatabaseUnavailableException;
import com.pharos.compliance.health.dto.HealthResponse;
import com.pharos.compliance.health.repository.DatabaseHealthRepository;
import com.pharos.compliance.health.service.HealthService;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Service
public class HealthServiceImpl implements HealthService {

  private static final Logger LOGGER = LoggerFactory.getLogger(HealthServiceImpl.class);

  private final DatabaseHealthRepository databaseHealthRepository;
  private final Scheduler jdbcScheduler;

  public HealthServiceImpl(
      DatabaseHealthRepository databaseHealthRepository,
      @Qualifier("jdbcScheduler") Scheduler jdbcScheduler) {
    this.databaseHealthRepository = databaseHealthRepository;
    this.jdbcScheduler = jdbcScheduler;
  }

  @Override
  public Mono<HealthResponse> getHealth() {
    return Mono.fromCallable(databaseHealthRepository::getDatabaseMetadata)
        .subscribeOn(jdbcScheduler)
        .map(
            metadata ->
                new HealthResponse(
                    "UP",
                    "pharos-compliance-backend",
                    metadata.database(),
                    metadata.schema(),
                    OffsetDateTime.now()))
        .onErrorMap(
            exception -> !(exception instanceof DatabaseUnavailableException),
            exception ->
                new DatabaseUnavailableException(
                    "Unable to validate the PostgreSQL connection", exception))
        .doOnSuccess(
            response ->
                LOGGER.info(
                    "PostgreSQL health check completed database={} schema={} status={}",
                    response.database(),
                    response.schema(),
                    response.status()));
  }
}
