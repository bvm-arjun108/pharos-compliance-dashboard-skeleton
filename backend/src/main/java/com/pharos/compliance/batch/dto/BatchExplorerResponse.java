package com.pharos.compliance.batch.dto;

import com.pharos.compliance.batch.model.BatchIssueType;
import com.pharos.compliance.batch.model.BatchMetricFocus;
import com.pharos.compliance.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Batch Explorer queue and its applied filter context")
public record BatchExplorerResponse(BatchExplorerSummaryResponse summary, List<BatchQueueItemResponse> batches, long matchingBatches,
    int page, int size, LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId, String country,
    Integer reportGroupId, String reportGroupName, BatchMetricFocus metricFocus) {}
