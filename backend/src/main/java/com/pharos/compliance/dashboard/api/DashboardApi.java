package com.pharos.compliance.dashboard.api;

import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_DESCRIPTION;
import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_HEADER;
import static com.pharos.compliance.common.api.OpenApiHeaders.TRACE_ID_DESCRIPTION;
import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.dashboard.dto.BatchDashboardResponse;
import com.pharos.compliance.dashboard.dto.TransactionDashboardResponse;
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

@Tag(name = "Compliance Dashboard", description = "Operational batch health and issue-driver metrics")
@RequestMapping("/dashboardDetails")
public interface DashboardApi {
  @Operation(operationId = "getBatchDashboard", summary = "Get Batch View dashboard details", description = "Returns aggregate batch"
      + " KPIs, the adaptive batch-health trend, and report groups requiring attention for an inclusive date range. Split from"
      + " Transactions Overview's own endpoint (getTransactionDashboard) so each page only pays for the queries and payload it"
      + " actually reads.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Batch dashboard calculated successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = BatchDashboardResponse.class))),
      @ApiResponse(responseCode = "400", description = "Missing, malformed, or inverted date range", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/batch-view", produces = MediaType.APPLICATION_JSON_VALUE)
  BatchDashboardResponse getBatchDashboard(
      @Parameter(description = "Inclusive reporting-period start date", required = true, example = "2026-08-01") @RequestParam("fromDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @Parameter(description = "Inclusive reporting-period end date", required = true, example = "2026-08-31") @RequestParam("toDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @Parameter(description = "Optional partial batch identifier") @RequestParam(value = "batchId", defaultValue = "") String batchId,
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT") @RequestParam(value = "country", defaultValue = "A"
      + "LL") String country,
      @Parameter(description = "Exact report group ID", example = "1573742369") @RequestParam(value = "reportGroupId", required = false) Integer reportGroupId);

  @Operation(operationId = "getTransactionDashboard", summary = "Get Transactions Overview dashboard details", description = "Returns"
      + " transaction-level Selected/Expected/Excluded/Not Reported totals, their reason breakdowns, and the adaptive health"
      + " trend for an inclusive date range. Split from Batch View's own endpoint (getBatchDashboard) so each page only pays"
      + " for the queries and payload it actually reads.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Transaction dashboard calculated successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = TransactionDashboardResponse.class))),
      @ApiResponse(responseCode = "400", description = "Missing, malformed, or inverted date range", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/transaction-view", produces = MediaType.APPLICATION_JSON_VALUE)
  TransactionDashboardResponse getTransactionDashboard(
      @Parameter(description = "Inclusive reporting-period start date", required = true, example = "2026-08-01") @RequestParam("fromDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @Parameter(description = "Inclusive reporting-period end date", required = true, example = "2026-08-31") @RequestParam("toDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT") @RequestParam(value = "country", defaultValue = "A"
      + "LL") String country,
      @Parameter(description = "Exact report group ID", example = "1573742369") @RequestParam(value = "reportGroupId", required = false) Integer reportGroupId);
}
