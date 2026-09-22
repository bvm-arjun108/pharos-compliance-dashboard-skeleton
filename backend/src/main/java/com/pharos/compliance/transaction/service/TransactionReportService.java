package com.pharos.compliance.transaction.service;

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
import java.time.LocalDate;
import java.util.List;

public interface TransactionReportService {
  TransactionReportResponse getTransactionReport(int reportGroupId, String batchId, int sequenceNumber, TransactionMetric metric,
      String search, TransactionEvidenceSource source, TransactionStage stage, TransactionOutcome outcome, TransactionStatus status,
      TransactionSortDirection sortDirection, int page, int size, String cursor);

  PeriodTransactionReportResponse getPeriodTransactionReport(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId,
      String batchId, String search, TransactionOutcome outcome, TransactionStatus status, String reason, boolean batchScopedExcluded,
      TransactionSortDirection sortDirection, int page, int size, String cursor);

  List<String> getPeriodReportBatchIds(LocalDate fromDate, LocalDate toDate, String country, Integer reportGroupId);

  TransactionSearchResponse searchTransactions(TransactionSearchField field, String query);
}
