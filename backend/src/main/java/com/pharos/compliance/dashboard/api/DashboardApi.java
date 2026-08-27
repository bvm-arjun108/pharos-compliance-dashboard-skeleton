package com.pharos.compliance.dashboard.api;

import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.dashboard.dto.DashboardDetailsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

@Tag(
    name = "Compliance Dashboard",
    description = "Operational batch health and issue-driver metrics")
@RequestMapping("/dashboardDetails")
public interface DashboardApi {

  @Operation(
      operationId = "getDashboardDetails",
      summary = "Get dashboard details",
      description =
          "Returns aggregate batch KPIs, adaptive health trends, issue-driver trends, and report groups requiring attention for an inclusive date range.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Dashboard details calculated successfully",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content = @Content(schema = @Schema(implementation = DashboardDetailsResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Missing, malformed, or inverted date range",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Compliance database unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<DashboardDetailsResponse> getDashboardDetails(
      @Parameter(
              description = "Inclusive reporting-period start date",
              required = true,
              example = "2026-08-01")
          @RequestParam("fromDate")
          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @Parameter(
              description = "Inclusive reporting-period end date",
              required = true,
              example = "2026-08-31")
          @RequestParam("toDate")
          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @Parameter(description = "Optional partial batch identifier")
          @RequestParam(value = "batchId", defaultValue = "")
          String batchId,
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT")
          @RequestParam(value = "country", defaultValue = "ALL")
          String country,
      @Parameter(description = "Exact report group ID", example = "1573742369")
          @RequestParam(value = "reportGroupId", required = false)
          Integer reportGroupId);
}
