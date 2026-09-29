package com.wu.compliance.dashboard.batch.service;

import com.wu.compliance.dashboard.batch.dto.BatchDetailsResponse;
import com.wu.compliance.dashboard.batch.dto.BatchExplorerResponse;
import com.wu.compliance.dashboard.batch.dto.BatchFilterOptionsResponse;
import com.wu.compliance.dashboard.batch.model.BatchIssueType;
import com.wu.compliance.dashboard.batch.model.BatchMetricFocus;
import com.wu.compliance.dashboard.batch.model.BatchStatus;
import java.time.LocalDate;

public interface BatchExplorerService {
  BatchFilterOptionsResponse getFilterOptions();

  BatchExplorerResponse getBatches(LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId,
      String country, Integer reportGroupId, BatchMetricFocus metricFocus, int page, int size);

  BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber);
}
