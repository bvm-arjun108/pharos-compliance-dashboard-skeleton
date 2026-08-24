package com.pharos.compliance.transaction.service;

import com.pharos.compliance.transaction.dto.TransactionReportResponse;
import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionMetric;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import com.pharos.compliance.transaction.model.TransactionStage;
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
      int page,
      int size);
}
