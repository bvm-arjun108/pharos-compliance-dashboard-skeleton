package com.pharos.compliance.health.service;

import com.pharos.compliance.health.dto.HealthResponse;
import reactor.core.publisher.Mono;

public interface HealthService {

  Mono<HealthResponse> getHealth();
}
