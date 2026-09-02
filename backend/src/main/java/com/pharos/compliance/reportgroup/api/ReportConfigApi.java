package com.pharos.compliance.reportgroup.api;

import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_DESCRIPTION;
import static com.pharos.compliance.common.api.OpenApiHeaders.SPAN_ID_HEADER;
import static com.pharos.compliance.common.api.OpenApiHeaders.TRACE_ID_DESCRIPTION;
import static com.pharos.compliance.common.api.OpenApiHeaders.TRACE_ID_HEADER;
import com.pharos.compliance.common.error.ApiErrorResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigDetailsResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigExplorerResponse;
import com.pharos.compliance.reportgroup.dto.ReportConfigFilterOptionsResponse;
import com.pharos.compliance.reportgroup.model.ReportConfigStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Report Configuration", description = "Read-only report-group configuration discovery and inspection")
@RequestMapping("/api/v1/report-configs")
public interface ReportConfigApi {
  @Operation(operationId = "getReportConfigFilterOptions", summary = "Get report-configuration filters", description = "Returns distinct "
      + "active countries and report types from report_group_config.")
  @ApiResponse(responseCode = "200", description = "Filter options returned successfully", headers = {@Header(name = TRACE_ID_HEADER, description = TRACE_ID_DESCRIPTION),
      @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)})
  @GetMapping(value = "/filter-options", produces = MediaType.APPLICATION_JSON_VALUE)
  ReportConfigFilterOptionsResponse getFilterOptions();

  @Operation(operationId = "getReportConfigs", summary = "Explore latest report-group configurations", description = "Returns the latest "
      + "configuration version for each report group, filtered by country, status, report type, or exact report group ID.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Report configurations returned successfully", headers = {@Header(name = "X"
      + "-Trace-Id", description = TRACE_ID_DESCRIPTION), @Header(name = SPAN_ID_HEADER, description = SPAN_ID_DESCRIPTION)}, content = @Content(schema = @Schema(implementation = ReportConfigExplorerResponse.class))),
      @ApiResponse(responseCode = "400", description = "A supplied filter is invalid", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  ReportConfigExplorerResponse getReportConfigs(
      @Parameter(description = "Active report_group_config country code or ALL", example = "SG") @RequestParam(value = "country", defaultValue = "A"
      + "LL") String country, @RequestParam(value = "status", defaultValue = "ALL") ReportConfigStatus status,
      @RequestParam(value = "reportType", defaultValue = "ALL") String reportType,
      @Parameter(description = "Exact report group ID", example = "1573742369") @RequestParam(value = "reportGroupId", required = false) Integer reportGroupId);

  @Operation(operationId = "getReportConfigDetails", summary = "Get one complete report-group configuration", description = "Uses the "
      + "full report-group configuration composite identity.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Configuration returned successfully", content = @Content(schema = @Schema(implementation = ReportConfigDetailsResponse.class))),
      @ApiResponse(responseCode = "404", description = "Configuration not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
  @GetMapping(value = "/{reportGroupId}/{reportSelectionVersionId}/{transformerVersionId}", produces = MediaType.APPLICATION_JSON_VALUE)
  ReportConfigDetailsResponse getReportConfigDetails(@PathVariable("reportGroupId") @Min(1) int reportGroupId,
      @PathVariable("reportSelectionVersionId") @Min(1) int reportSelectionVersionId,
      @PathVariable("transformerVersionId") @NotBlank String transformerVersionId);
}
