package com.pharos.compliance.transaction.controller;

import com.pharos.compliance.transaction.api.TransactionReportApi;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.service.TransactionReportService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
public class TransactionReportController implements TransactionReportApi {

  private final TransactionReportService transactionReportService;

  public TransactionReportController(TransactionReportService transactionReportService) {
    this.transactionReportService = transactionReportService;
  }

  @Override
  public Mono<TransactionReportResponse> getTransactionReport(
      int reportGroupId,
      String batchId,
      int sequenceNumber,
      TransactionMetric metric,
      String search,
      TransactionEvidenceSource source,
      TransactionStage stage,
      TransactionOutcome outcome,
      int page,
      int size) {
    return transactionReportService.getTransactionReport(
        reportGroupId, batchId, sequenceNumber, metric, search, source, stage, outcome, page, size);
  }
}
