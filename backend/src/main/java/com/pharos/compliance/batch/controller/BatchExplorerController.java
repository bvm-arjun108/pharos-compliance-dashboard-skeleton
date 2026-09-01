package com.pharos.compliance.batch.controller;

import com.pharos.compliance.batch.api.BatchExplorerApi;
import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import com.pharos.compliance.batch.service.BatchExplorerService;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class BatchExplorerController implements BatchExplorerApi {

  private final BatchExplorerService batchExplorerService;

  public BatchExplorerController(BatchExplorerService batchExplorerService) {
    this.batchExplorerService = batchExplorerService;
  }

  @Override
  public BatchFilterOptionsResponse getFilterOptions() {
    return batchExplorerService.getFilterOptions();
  }

  @Override
  public BatchExplorerResponse getBatches(
      LocalDate fromDate,
      LocalDate toDate,
      BatchStatus status,
      BatchIssueType issueType,
      String batchId,
      String country,
      Integer reportGroupId,
      BatchMetricFocus metricFocus,
      int page,
      int size) {
    return batchExplorerService.getBatches(
        fromDate,
        toDate,
        status,
        issueType,
        batchId,
        country,
        reportGroupId,
        metricFocus,
        page,
        size);
  }

  @Override
  public BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    return batchExplorerService.getBatchDetails(reportGroupId, batchId, sequenceNumber);
  }
}
