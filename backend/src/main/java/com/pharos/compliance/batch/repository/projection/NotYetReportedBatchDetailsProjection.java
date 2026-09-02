package com.pharos.compliance.batch.repository.projection;

import java.time.LocalDateTime;

public record NotYetReportedBatchDetailsProjection(int reportGroupId, String reportGroupName, String batchId, LocalDateTime startedAt,
    LocalDateTime lastActivityAt, long discoveredTransactions, long stalledTransactions, boolean journeyAvailable,
    boolean exclusionsAvailable) {}
