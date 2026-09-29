package com.wu.compliance.dashboard.batch.controller;

import com.wu.compliance.dashboard.batch.api.BatchExplorerApi;
import com.wu.compliance.dashboard.batch.dto.BatchDetailsResponse;
import com.wu.compliance.dashboard.batch.dto.BatchExplorerResponse;
import com.wu.compliance.dashboard.batch.dto.BatchFilterOptionsResponse;
import com.wu.compliance.dashboard.batch.model.BatchIssueType;
import com.wu.compliance.dashboard.batch.model.BatchMetricFocus;
import com.wu.compliance.dashboard.batch.model.BatchStatus;
import com.wu.compliance.dashboard.batch.service.BatchExplorerService;
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
  public BatchExplorerResponse getBatches(LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId,
      String country, Integer reportGroupId, BatchMetricFocus metricFocus, int page, int size) {
    return batchExplorerService.getBatches(fromDate, toDate, status, issueType, batchId, country, reportGroupId, metricFocus, page, size);
  }

  @Override
  public BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    return batchExplorerService.getBatchDetails(reportGroupId, batchId, sequenceNumber);
  }
}
