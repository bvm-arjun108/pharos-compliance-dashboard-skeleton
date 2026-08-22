package com.pharos.compliance.batch.api;

import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import com.pharos.compliance.common.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

@Tag(
    name = "Batch Explorer",
    description = "Prioritized batch work queue and selected-batch diagnostic preview")
@RequestMapping("/api/v1/batches")
public interface BatchExplorerApi {

  @Operation(
      operationId = "getBatchFilterOptions",
      summary = "Get backend-managed Batch Explorer filter values",
      description =
          "Returns distinct active countries from report_group_config. Multiple report groups configured for one country are consolidated into one filter option.")
  @ApiResponse(
      responseCode = "200",
      description = "Filter options returned successfully",
      headers = {
        @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
        @Header(name = "X-Span-Id", description = "Current server span identifier")
      })
  @GetMapping(value = "/filter-options", produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<BatchFilterOptionsResponse> getFilterOptions();

  @Operation(
      operationId = "getBatchExplorer",
      summary = "Get the filtered Batch Explorer work queue",
      description =
          "Returns summary counts and reconciliation batches ordered for operational triage. Duplicate transformation is intentionally excluded from the Phase 1 attention calculation.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Batch queue calculated successfully",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content = @Content(schema = @Schema(implementation = BatchExplorerResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "The filter values or reporting period are invalid",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Compliance database unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<BatchExplorerResponse> getBatches(
      @Parameter(description = "Inclusive reporting-period start date", example = "2026-08-16")
          @RequestParam("fromDate")
          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @Parameter(description = "Inclusive reporting-period end date", example = "2026-08-22")
          @RequestParam("toDate")
          @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "status", defaultValue = "ALL") BatchStatus status,
      @RequestParam(value = "issueType", defaultValue = "ALL") BatchIssueType issueType,
      @RequestParam(value = "batchId", defaultValue = "") String batchId,
      @RequestParam(value = "country", defaultValue = "ALL") String country,
      @Parameter(
              description = "Exact report group ID for dashboard drilldown",
              example = "1000000007")
          @RequestParam(value = "reportGroupId", required = false)
          @Min(1) Integer reportGroupId,
      @Parameter(description = "Transaction metric used to filter and prioritize the queue")
          @RequestParam(value = "metricFocus", defaultValue = "DEFAULT")
          BatchMetricFocus metricFocus,
      @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(value = "size", defaultValue = "50") @Min(1) @Max(200) int size);

  @Operation(
      operationId = "getBatchPreview",
      summary = "Get the diagnostic preview for one batch",
      description =
          "Uses the report-group, batch, and sequence composite identity. Journey and exclusion evidence are reported conditionally; downstream final-reported count remains unavailable until its authoritative source is integrated.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Batch preview returned successfully",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content = @Content(schema = @Schema(implementation = BatchDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No batch exists for the supplied composite identity",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Compliance database unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(
      value = "/{reportGroupId}/{batchId}/{sequenceNumber}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<BatchDetailsResponse> getBatchDetails(
      @PathVariable("reportGroupId") @Min(1) int reportGroupId,
      @PathVariable("batchId") @NotBlank String batchId,
      @PathVariable("sequenceNumber") @Min(1) int sequenceNumber);
}
