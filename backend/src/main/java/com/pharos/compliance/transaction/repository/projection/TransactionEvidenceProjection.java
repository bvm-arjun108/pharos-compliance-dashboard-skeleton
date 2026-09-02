package com.pharos.compliance.transaction.repository.projection;

import java.math.BigDecimal;

public record TransactionEvidenceProjection(String recordKey, String identifier, String mtcn, String batchId, String evidenceSource,
    String stage, String status, String outcome, String comments, String skipReason, String ruleId, String exclusionReason,
    String exclusionStrategy, String reportedBatchId, String reportingTimestamp, String modifiedAt, Boolean processingComplete,
    BigDecimal currencyAmount, String currencyCode, String transactionDate, String transactionSide, String txnSource, String activityType,
    String sendDate, String galacticId, Integer bucketId, Long attemptId, String senderName, String receiverName, String senderCity,
    String senderCountry, String senderPhone, String senderDateOfBirth, String senderIdType, String senderIdNumber, String receiverCity,
    String receiverCountry, String receiverPhone, String receiverDateOfBirth, String receiverIdType, String receiverIdNumber,
    String transactionStatus, String transactionSubStatus, String ruleHitsJson) {}
