package com.pharos.compliance.transaction.controller;

import com.pharos.compliance.transaction.api.TransactionReportApi;
import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionRecordDetailResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import com.pharos.compliance.transaction.service.TransactionReportService;
import java.time.LocalDate;
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
      TransactionStatus status,
      TransactionSortDirection sortDirection,
      int page,
      int size) {
    return transactionReportService.getTransactionReport(
        reportGroupId, batchId, sequenceNumber, metric, search, source, stage, outcome, status,
        sortDirection, page, size);
  }

  @Override
  public Mono<PeriodTransactionReportResponse> getPeriodTransactionReport(
      LocalDate fromDate,
      LocalDate toDate,
      String country,
      Integer reportGroupId,
      String batchId,
      String search,
      TransactionOutcome outcome,
      TransactionStatus status,
      TransactionSortDirection sortDirection,
      int page,
      int size) {
    return transactionReportService.getPeriodTransactionReport(
        fromDate, toDate, country, reportGroupId, batchId, search, outcome, status, sortDirection,
        page, size);
  }

  @Override
  public Mono<TransactionRecordDetailResponse> getRecordDetail(
      int reportGroupId,
      String batchId,
      String identifier,
      TransactionStatus status,
      TransactionMetric metric) {
    return transactionReportService.getRecordDetail(
        reportGroupId, batchId, identifier, status, metric);
  }
}
