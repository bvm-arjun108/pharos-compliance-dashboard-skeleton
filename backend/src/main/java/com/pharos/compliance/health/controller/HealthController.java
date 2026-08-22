package com.pharos.compliance.health.controller;

import com.pharos.compliance.health.api.HealthApi;
import com.pharos.compliance.health.dto.HealthResponse;
import com.pharos.compliance.health.service.HealthService;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class HealthController implements HealthApi {

  private final HealthService healthService;

  public HealthController(HealthService healthService) {
    this.healthService = healthService;
  }

  @Override
  public Mono<HealthResponse> health() {
    return healthService.getHealth();
  }
}
