package com.wu.compliance.dashboard.batch.dto;

import com.wu.compliance.dashboard.batch.model.BatchIssueType;
import com.wu.compliance.dashboard.batch.model.BatchMetricFocus;
import com.wu.compliance.dashboard.batch.model.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Batch Explorer queue and its applied filter context")
public record BatchExplorerResponse(BatchExplorerSummaryResponse summary, List<BatchQueueItemResponse> batches, long matchingBatches,
    int page, int size, LocalDate fromDate, LocalDate toDate, BatchStatus status, BatchIssueType issueType, String batchId, String country,
    Integer reportGroupId, String reportGroupName, BatchMetricFocus metricFocus) {}
