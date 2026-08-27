package com.pharos.compliance.transaction.api;

import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

@Tag(
    name = "Transaction Evidence",
    description = "Record-level evidence reports scoped to one reconciliation batch")
@RequestMapping("/api/v1/transactions")
public interface TransactionReportApi {

  @Operation(
      operationId = "getTransactionEvidenceReport",
      summary = "Get a transaction evidence report for one batch metric",
      description =
          "Returns the aggregate reconciliation count together with every available latest-state journey or exclusion-audit record. Aggregate-only metrics are identified explicitly and never expanded into synthetic transaction rows.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Transaction evidence report returned successfully",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content = @Content(schema = @Schema(implementation = TransactionReportResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "The reconciliation batch was not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Compliance database unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(value = "/report", produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<TransactionReportResponse> getTransactionReport(
      @Parameter(description = "Report-group identifier", example = "1000000007")
          @RequestParam("reportGroupId")
          @Min(1) int reportGroupId,
      @Parameter(description = "Exact processing batch identifier")
          @RequestParam("batchId")
          @NotBlank String batchId,
      @Parameter(description = "Reconciliation sequence number", example = "1")
          @RequestParam("sequenceNumber")
          @Min(1) int sequenceNumber,
      @RequestParam(value = "metric", defaultValue = "ALL") TransactionMetric metric,
      @RequestParam(value = "search", defaultValue = "") String search,
      @RequestParam(value = "source", defaultValue = "ALL") TransactionEvidenceSource source,
      @RequestParam(value = "stage", defaultValue = "ALL") TransactionStage stage,
      @RequestParam(value = "outcome", defaultValue = "ALL") TransactionOutcome outcome,
      @RequestParam(value = "status", defaultValue = "ALL") TransactionStatus status,
      @RequestParam(value = "sortDirection", defaultValue = "DESC")
          TransactionSortDirection sortDirection,
      @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(value = "size", defaultValue = "100") @Min(1) @Max(200) int size);

  @Operation(
      operationId = "getPeriodTransactionEvidenceReport",
      summary = "Get excluded-transaction evidence across every batch in a date range",
      description =
          "Used when a dashboard KPI (e.g. total excluded transactions) spans many batches, so there"
              + " is no single batch to show evidence for. Aggregates report_transformation_reconciliation.excluded_txn"
              + " across every matching batch exactly the way the dashboard sums it, and lists every"
              + " rule_hit_exclusion_audit record for those same batches. Scoped to the EXCLUDED metric"
              + " only.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Period transaction evidence report returned successfully",
        headers = {
          @Header(name = "X-Trace-Id", description = "Distributed trace identifier"),
          @Header(name = "X-Span-Id", description = "Current server span identifier")
        },
        content =
            @Content(schema = @Schema(implementation = PeriodTransactionReportResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Missing, malformed, or inverted date range, or unsupported country filter",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Compliance database unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  @GetMapping(value = "/period-report", produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<PeriodTransactionReportResponse> getPeriodTransactionReport(
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
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT")
          @RequestParam(value = "country", defaultValue = "ALL")
          String country,
      @Parameter(description = "Exact report group ID", example = "1573742369")
          @RequestParam(value = "reportGroupId", required = false)
          Integer reportGroupId,
      @RequestParam(value = "search", defaultValue = "") String search,
      @RequestParam(value = "outcome", defaultValue = "ALL") TransactionOutcome outcome,
      @RequestParam(value = "status", defaultValue = "ALL") TransactionStatus status,
      @RequestParam(value = "sortDirection", defaultValue = "DESC")
          TransactionSortDirection sortDirection,
      @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(value = "size", defaultValue = "100") @Min(1) @Max(200) int size);
}
