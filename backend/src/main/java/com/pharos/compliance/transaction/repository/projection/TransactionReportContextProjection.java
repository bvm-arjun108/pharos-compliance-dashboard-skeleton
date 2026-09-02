package com.pharos.compliance.transaction.repository.projection;

public record TransactionReportContextProjection(int reportGroupId, String reportGroupName, String batchId, int sequenceNumber,
    String reportingPeriodFrom, String reportingPeriodTo, long selectedTransactions, long attemptsFound, long missingAttempts,
    long expectedEligible, long actualEligible, long transformed, long failed, long expectedReportable, long actualReportable, long excluded,
    long simulated, long alreadyReported, long softDedup, long filtrationVariance, long reconciliationVariance) {}
