package com.pharos.compliance.transaction.controller;

import com.pharos.compliance.transaction.api.TransactionReportApi;
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
import com.pharos.compliance.transaction.service.TransactionReportService;
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
  public TransactionSearchResponse searchTransactions(TransactionSearchField field, String query) {
    return transactionReportService.searchTransactions(field, query);
  }
}
