package com.pharos.compliance.transaction.service;

import com.pharos.compliance.transaction.dto.PeriodTransactionReportResponse;
import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionSortDirection;
import com.pharos.compliance.transaction.model.TransactionStage;
import com.pharos.compliance.transaction.model.TransactionStatus;
import java.time.LocalDate;

public interface TransactionReportService {

  TransactionReportResponse getTransactionReport(
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

  PeriodTransactionReportResponse getPeriodTransactionReport(
      LocalDate fromDate,
      LocalDate toDate,
      String country,
      Integer reportGroupId,
      String search,
      TransactionOutcome outcome,
      TransactionStatus status,
      TransactionSortDirection sortDirection,
      int page,
      int size);
}
