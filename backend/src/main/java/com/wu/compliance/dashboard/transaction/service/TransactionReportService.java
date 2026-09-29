package com.wu.compliance.dashboard.transaction.service;

import com.wu.compliance.dashboard.transaction.dto.TransactionEvidenceDetailResponse;
import com.wu.compliance.dashboard.transaction.model.TransactionDetailScope;
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

  TransactionEvidenceDetailResponse getTransactionDetail(TransactionDetailScope scope, Integer reportGroupId, String batchId,
      String identifier, LocalDate fromDate, LocalDate toDate, String country, TransactionMetric metric, TransactionEvidenceSource source,
      TransactionStage stage, TransactionOutcome outcome, TransactionStatus status, String reason, boolean batchScopedExcluded,
      String batchIdFilter, String recordKey);

  TransactionSearchResponse searchTransactions(TransactionSearchField field, String query);
}
