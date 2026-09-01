package com.pharos.compliance.batch.service;

import com.pharos.compliance.batch.dto.BatchDetailsResponse;
import com.pharos.compliance.batch.dto.BatchExplorerResponse;
import com.pharos.compliance.batch.dto.BatchFilterOptionsResponse;
import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import java.time.LocalDate;

public interface BatchExplorerService {

  BatchFilterOptionsResponse getFilterOptions();

  BatchExplorerResponse getBatches(
      LocalDate fromDate,
      LocalDate toDate,
      BatchStatus status,
      BatchIssueType issueType,
      String batchId,
      String country,
      Integer reportGroupId,
      BatchMetricFocus metricFocus,
      int page,
      int size);

  BatchDetailsResponse getBatchDetails(int reportGroupId, String batchId, int sequenceNumber);
}
