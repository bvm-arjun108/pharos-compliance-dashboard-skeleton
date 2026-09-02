package com.pharos.compliance.health.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Application and PostgreSQL connection health")
public record HealthResponse(@Schema(example = "UP") String status, @Schema(example = "pharos-compliance-backend") String service,
    @Schema(example = "pharosRBT") String database, @Schema(example = "public") String schema, OffsetDateTime timestamp) {}
