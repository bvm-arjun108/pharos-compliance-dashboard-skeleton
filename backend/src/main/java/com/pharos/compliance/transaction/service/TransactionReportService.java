package com.pharos.compliance.transaction.service;

import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionRecordDetailResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import java.time.LocalDate;
import reactor.core.publisher.Mono;

public interface TransactionReportService {

  Mono<TransactionReportResponse> getTransactionReport(
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
      int size);

  Mono<PeriodTransactionReportResponse> getPeriodTransactionReport(
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
      int size);

  Mono<TransactionRecordDetailResponse> getRecordDetail(
      int reportGroupId,
      String batchId,
      String identifier,
      TransactionStatus status,
      TransactionMetric metric);
}
