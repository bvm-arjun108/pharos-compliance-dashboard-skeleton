package com.pharos.compliance.transaction.api;

import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_DESCRIPTION;
import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_HEADER;
import static com.pharos.compliance.common.api.OpenApiHeaders.TRACE_ID_DESCRIPTION;
import com.pharos.compliance.transaction.dto.TransactionEvidenceDetailResponse;
import com.pharos.compliance.transaction.model.TransactionDetailScope;
import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionSearchResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSearchField;
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
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Transaction Evidence", description = "Record-level evidence reports scoped to one reconciliation batch")
@RequestMapping("/api/v1/transactions")
public interface TransactionReportApi {
  @Operation(operationId = "getTransactionEvidenceReport", summary = "Get a transaction evidence report for one batch metric", description = "R"
      + "eturns the aggregate reconciliation count together with every available latest-state journey or exclusion-audit record. "
      + "Aggregate-only metrics are identified explicitly and never expanded into synthetic transaction rows.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Transaction evidence report returned successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = TransactionReportResponse.class))),
      @ApiResponse(responseCode = "404", description = "The reconciliation batch was not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/report", produces = MediaType.APPLICATION_JSON_VALUE)
  TransactionReportResponse getTransactionReport(
      @Parameter(description = "Report-group identifier", example = "1000000007") @RequestParam("reportGroupId") @Min(1) int reportGroupId,
      @Parameter(description = "Exact processing batch identifier") @RequestParam("batchId") @NotBlank String batchId,
      @Parameter(description = "Reconciliation sequence number", example = "1") @RequestParam("sequenceNumber") @Min(1) int sequenceNumber,
      @RequestParam(value = "metric", defaultValue = "ALL") TransactionMetric metric,
      @RequestParam(value = "search", defaultValue = "") String search,
      @RequestParam(value = "source", defaultValue = "ALL") TransactionEvidenceSource source,
      @RequestParam(value = "stage", defaultValue = "ALL") TransactionStage stage,
      @RequestParam(value = "outcome", defaultValue = "ALL") TransactionOutcome outcome,
      @RequestParam(value = "status", defaultValue = "ALL") TransactionStatus status,
      @RequestParam(value = "sortDirection", defaultValue = "DESC") TransactionSortDirection sortDirection,
      @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(value = "size", defaultValue = "100") @Min(1) @Max(200) int size,
      @Parameter(description = "Opaque cursor from a previous response's nextCursor, for cheap sequential paging that skips "
      + "the OFFSET scan cost `page` incurs at depth. Takes precedence over `page` when present; omit for the normal "
      + "page-number paginator.") @RequestParam(value = "cursor", defaultValue = "") String cursor);

  @Operation(operationId = "getPeriodTransactionEvidenceReport", summary = "Get transaction evidence across every batch in a date range", description = "Used when a dashboard KPI spans many batches, so there is no single batch to show evidence for. `status` "
      + "picks which evidence bucket -- for EXCLUDED/NOT_REPORTED specifically, evidence answers 'has this transaction ever "
      + "been excluded/not-reported, anywhere in its journey history' (matching the Transactions Overview page's own tiles), "
      + "unless `batchScopedExcluded` is set for EXCLUDED, which instead matches "
      + "report_transformation_reconciliation.excluded_txn's own simpler, batch-scoped definition. Every other status answers "
      + "from this period's own batch evidence rows directly.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Period transaction evidence report returned successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = PeriodTransactionReportResponse.class))),
      @ApiResponse(responseCode = "400", description = "Missing, malformed, or inverted date range, or unsupported country filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/period-report", produces = MediaType.APPLICATION_JSON_VALUE)
  PeriodTransactionReportResponse getPeriodTransactionReport(
      @Parameter(description = "Inclusive reporting-period start date", required = true, example = "2026-08-01") @RequestParam("fromDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @Parameter(description = "Inclusive reporting-period end date", required = true, example = "2026-08-31") @RequestParam("toDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT") @RequestParam(value = "country", defaultValue = "A"
      + "LL") String country,
      @Parameter(description = "Exact report group ID", example = "1573742369") @RequestParam(value = "reportGroupId", required = false) Integer reportGroupId,
      @Parameter(description = "Case-insensitive substring match against batch ID, narrowing this period's many batches down to "
      + "one (or a few) -- same containsIgnoreCase semantics as Batch Explorer's own batch search.", example = "BIN512608") @RequestParam(value = "b"
      + "atchId", defaultValue = "") String batchId, @RequestParam(value = "search", defaultValue = "") String search,
      @RequestParam(value = "outcome", defaultValue = "ALL") TransactionOutcome outcome,
      @RequestParam(value = "status", defaultValue = "ALL") TransactionStatus status,
      @Parameter(description = "Exact match against the same reason bucket the Transactions Overview dashboard legends "
      + "count: for status=EXCLUDED, the Top Exclusion Reasons bucket (identifier's skip_reason, falling back to "
      + "comments); for status=NOT_REPORTED, the Not Reported Reasons category. Ignored for other status values", example = "Already "
      + "Reported In Prior Batch") @RequestParam(value = "reason", defaultValue = "") String reason,
      @Parameter(description = "Only meaningful for status=EXCLUDED. When true, evidence is scoped to exactly the batches in "
      + "this date/country/report-group window and matches the FILTRATION/EXCLUDED journey rows commented "
      + "EXCLUDED_BECAUSE_EXCLUSION_EXISTS there -- the same simple definition report_transformation_reconciliation.excluded_txn "
      + "itself sums to (simulated and already-reported exclusions are carved out into txn_simulated / already_reported_count "
      + "upstream, so they are out of scope here too), for matching a batch-scoped 'total excluded' KPI (e.g. the Report Groups "
      + "Requiring Attention table). It is also the only combination for which aggregateCount is populated from that scalar. "
      + "When false (default), evidence instead answers 'has this transaction ever been excluded, across every batch in this "
      + "same date/country/report-group window' -- matching the Transactions Overview page's own Excluded tile, which is a "
      + "different, distinct-transaction definition scoped to the window (not the transaction's all-time history). "
      + "NOT_REPORTED always uses that window-scoped, distinct-transaction definition regardless of this flag.") @RequestParam(value = "b"
      + "atchScopedExcluded", defaultValue = "false") boolean batchScopedExcluded,
      @RequestParam(value = "sortDirection", defaultValue = "DESC") TransactionSortDirection sortDirection,
      @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(value = "size", defaultValue = "100") @Min(1) @Max(200) int size,
      @Parameter(description = "Opaque cursor from a previous response's nextCursor, for cheap sequential paging that skips "
      + "the OFFSET scan cost `page` incurs at depth. Takes precedence over `page` when present; omit for the normal "
      + "page-number paginator.") @RequestParam(value = "cursor", defaultValue = "") String cursor);

  @Operation(operationId = "getTransactionDetail", summary = "Get one transaction's full evidence detail", description = "Fetched when a "
      + "row is expanded, not with the list. The list response deliberately omits the party fields returned here -- names, dates of "
      + "birth, phone numbers, government ID numbers -- so that personal data is read only when someone actually opens a transaction, "
      + "and so that access to it is attributable to a specific request rather than to every page load.\n\n"
      + "`scope` is required and is not cosmetic: BATCH resolves rule hits within one efile_batch_id, PERIOD resolves them across every "
      + "batch of the report group in the window. Pass the scope the row was listed under, or the panel will disagree with the list. "
      + "Rule-hit enrichment here is never narrowed by a status or metric filter.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Transaction detail returned successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = TransactionEvidenceDetailResponse.class))),
      @ApiResponse(responseCode = "400", description = "The scope or its required parameters are invalid", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "404", description = "No evidence exists for that identifier within the supplied scope", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/detail", produces = MediaType.APPLICATION_JSON_VALUE)
  TransactionEvidenceDetailResponse getTransactionDetail(
      @Parameter(description = "Which list this row was opened from -- decides the rule-hit scope") @RequestParam("scope") @NotNull TransactionDetailScope scope,
      @Parameter(description = "Report group the row belongs to. Required for scope=BATCH. Optional for scope=PERIOD, which may span "
      + "every report group in the window exactly as the list does.", example = "51") @RequestParam(value = "reportGroupId", required = false) @Min(1) Integer reportGroupId,
      @Parameter(description = "The row's own evidence batch ID", example = "BIN512609021152002340") @RequestParam("batchId") @NotBlank String batchId,
      @Parameter(description = "The row's transaction identifier", example = "8000000000000234001") @RequestParam("identifier") @NotBlank String identifier,
      @Parameter(description = "Required for scope=PERIOD -- the window the row was listed under") @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @Parameter(description = "Required for scope=PERIOD") @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @RequestParam(value = "country", defaultValue = "ALL") String country,
      @Parameter(description = "Pass the same list filters the row was displayed under. The detail panel is an expansion of a specific "
      + "row, so it is built from the same pipeline and the same filters -- omitting them can surface a different evidence source for "
      + "the same transaction. They narrow which row is returned; they never narrow its rule-hit enrichment.") @RequestParam(value = "met"
      + "ric", defaultValue = "ALL") TransactionMetric metric,
      @RequestParam(value = "source", defaultValue = "ALL") TransactionEvidenceSource source,
      @RequestParam(value = "stage", defaultValue = "ALL") TransactionStage stage,
      @RequestParam(value = "outcome", defaultValue = "ALL") TransactionOutcome outcome,
      @RequestParam(value = "status", defaultValue = "ALL") TransactionStatus status,
      @RequestParam(value = "reason", defaultValue = "") String reason,
      @RequestParam(value = "batchScopedExcluded", defaultValue = "false") boolean batchScopedExcluded,
      @Parameter(description = "Original list batch filter, distinct from the selected row batchId") @RequestParam(value = "batchIdFilter", defaultValue = 
      "") String batchIdFilter,
      @Parameter(description = "Selected list row record key") @RequestParam("recordKey") @NotBlank String recordKey);

  @Operation(operationId = "getPeriodReportBatchIds", summary = "List every distinct batch ID in a period-report scope", description = "Same date/country/report-group scope as getPeriodTransactionEvidenceReport, without a status/metric filter -- "
      + "backs a batch picker for that report (e.g. a typeahead) rather than a second full evidence fetch. Ordered by "
      + "batch ID; not paginated, since a reporting period's batch count is small enough to hand back in one response.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Batch IDs returned successfully (possibly empty)", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}),
      @ApiResponse(responseCode = "400", description = "Missing, malformed, or inverted date range, or unsupported country filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/period-report/batches", produces = MediaType.APPLICATION_JSON_VALUE)
  List<String> getPeriodReportBatchIds(
      @Parameter(description = "Inclusive reporting-period start date", required = true, example = "2026-08-01") @RequestParam("fromDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @Parameter(description = "Inclusive reporting-period end date", required = true, example = "2026-08-31") @RequestParam("toDate") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @Parameter(description = "Active report_group_config country code or ALL", example = "PT") @RequestParam(value = "country", defaultValue = "A"
      + "LL") String country,
      @Parameter(description = "Exact report group ID", example = "1573742369") @RequestParam(value = "reportGroupId", required = false) Integer reportGroupId);

  @Operation(operationId = "searchTransactions", summary = "Find every evidence row matching one MTCN or external transaction key, across"
      + " every report group", description = "No date range, report group, or country required -- for when the caller knows a transaction's "
      + "MTCN or external transaction key but not which country or report group it belongs to. Returns every matching row from "
      + "journey, exclusion-audit, and rule_hit unmerged, since the same real transaction can be evaluated more than "
      + "once (once per report group or rule side) with genuinely different outcomes in each. `field` picks the single "
      + "column matched -- an external transaction key that doesn't parse as a number matches nothing rather than "
      + "falling back to a different field.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Search completed successfully (possibly with zero results)", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = TransactionSearchResponse.class))),
      @ApiResponse(responseCode = "400", description = "Blank search query", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "503", description = "Compliance database unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
  TransactionSearchResponse searchTransactions(
      @Parameter(description = "Which field to match query against", required = true, example = "MTCN") @RequestParam("field") TransactionSearchField field,
      @Parameter(description = "MTCN or external transaction key to search for", required = true, example = "9000000000217510") @RequestParam("q"
      + "uery") @NotBlank String query);
}
