package com.pharos.compliance.batch.repository.projection;

import java.time.LocalDateTime;

public record BatchQueueProjection(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber, String reportingPeriodFrom,
    String reportingPeriodTo, LocalDateTime startedAt, LocalDateTime completedAt, long transformationFailures, long missingAttempts,
    long activityMissing, long filtrationErrors, long reconciliationImbalance, long transformerOutput, long excludedTransactions,
    long duplicateTransactions, long simulatedTransactions, long softDedupTransactions, long totalIssues, long discoveredTransactions,
    String statusBucket, long matchingCount) {}
