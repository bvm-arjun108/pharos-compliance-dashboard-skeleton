package com.wu.compliance.dashboard.health.controller;

import com.wu.compliance.dashboard.health.api.HealthApi;
import com.wu.compliance.dashboard.health.dto.HealthResponse;
import com.wu.compliance.dashboard.health.service.HealthService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController implements HealthApi {
  private final HealthService healthService;

  public HealthController(HealthService healthService) {
    this.healthService = healthService;
  }

  @Override
  public HealthResponse health() {
    return healthService.getHealth();
  }
}
