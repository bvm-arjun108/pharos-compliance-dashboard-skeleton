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
      Integer reportGroupId, String search, TransactionOutcome outcome, TransactionStatus status, String reason,
      TransactionSortDirection sortDirection, int page, int size, String cursor) {
    return transactionReportService.getPeriodTransactionReport(fromDate, toDate, country, reportGroupId, search, outcome, status, reason,
        sortDirection, page, size, cursor);
  }

  @Override
  public TransactionSearchResponse searchTransactions(TransactionSearchField field, String query) {
    return transactionReportService.searchTransactions(field, query);
  }
}
