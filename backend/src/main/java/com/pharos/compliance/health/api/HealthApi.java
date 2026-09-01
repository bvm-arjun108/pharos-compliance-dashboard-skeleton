package com.pharos.compliance.health.api;

import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.health.dto.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Health", description = "Application and database connectivity checks")
@RequestMapping("/api/v1")
public interface HealthApi {

  @Operation(
      operationId = "getApplicationHealth",
      summary = "Check application health",
      description = "Validates a live connection to the configured PostgreSQL database.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Application and PostgreSQL are available",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content = @Content(schema = @Schema(implementation = HealthResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "PostgreSQL is unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  HealthResponse health();
}
