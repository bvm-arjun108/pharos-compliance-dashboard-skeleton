package com.wu.compliance.dashboard.transaction.controller;

import com.wu.compliance.dashboard.transaction.dto.TransactionEvidenceDetailResponse;
import com.wu.compliance.dashboard.transaction.model.TransactionDetailScope;
import com.wu.compliance.dashboard.transaction.api.TransactionReportApi;
import com.wu.compliance.dashboard.transaction.dto.PeriodTransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionReportResponse;
import com.wu.compliance.dashboard.transaction.dto.TransactionSearchResponse;
import com.wu.compliance.dashboard.transaction.model.TransactionEvidenceSource;
import com.wu.compliance.dashboard.transaction.model.TransactionMetric;
import com.wu.compliance.dashboard.transaction.model.TransactionOutcome;
import com.wu.compliance.dashboard.transaction.model.TransactionSearchField;
import com.wu.compliance.dashboard.transaction.model.TransactionSortDirection;
import com.wu.compliance.dashboard.transaction.model.TransactionStage;
import com.wu.compliance.dashboard.transaction.model.TransactionStatus;
import com.wu.compliance.dashboard.transaction.service.TransactionReportService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class TransactionReportController implements TransactionReportApi {
  private final TransactionReportService transactionReportService;

  public TransactionReportController(TransactionReportService transactionReportService) {
    this.transactionReportService = transactionReportService;
  }

  @Override
  public TransactionReportResponse getTransactionReport(int reportGroupId, String batchId, int sequenceNumber, TransactionMetric metric,
      String search, TransactionEvidenceSource source, TransactionStage stage, TransactionOutcome outcome, TransactionStatus status,
      TransactionSortDirection sortDirection, int page, int size, String cursor) {
    return transactionReportService.getTransactionReport(reportGroupId, batchId, sequenceNumber, metric, search, source, stage, outcome,
        status, sortDirection, page, size, cursor);
  }

  @Override
  public PeriodTransactionReportResponse getPeriodTransactionReport(LocalDate fromDate, LocalDate toDate, String country,
      Integer reportGroupId, String batchId, String search, TransactionOutcome outcome, TransactionStatus status, String reason,
      boolean batchScopedExcluded, TransactionSortDirection sortDirection, int page, int size, String cursor) {
    return transactionReportService.getPeriodTransactionReport(fromDate, toDate, country, reportGroupId, batchId, search, outcome, status,
        reason, batchScopedExcluded, sortDirection, page, size, cursor);
  }

  @Override
  public List<String> getPeriodReportBatchIds(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId) {
    return transactionReportService.getPeriodReportBatchIds(fromDate, toDate, country, reportGroupId);
  }

  @Override
  public TransactionSearchResponse searchTransactions(TransactionSearchField field, String query, LocalDate fromDate, LocalDate toDate,
      String country, Integer reportGroupId) {
    return transactionReportService.searchTransactions(field, query, fromDate, toDate, country, reportGroupId);
  }

  @Override
  public TransactionEvidenceDetailResponse getTransactionDetail(TransactionDetailScope scope, Integer reportGroupId, String batchId,
      String identifier, LocalDate fromDate, LocalDate toDate, String country, TransactionMetric metric, TransactionEvidenceSource source,
      TransactionStage stage, TransactionOutcome outcome, TransactionStatus status, String reason, boolean batchScopedExcluded,
      String batchIdFilter, String recordKey) {
    return transactionReportService.getTransactionDetail(scope, reportGroupId, batchId, identifier, fromDate, toDate, country, metric,
        source, stage, outcome, status, reason, batchScopedExcluded, batchIdFilter, recordKey);
  }
}
