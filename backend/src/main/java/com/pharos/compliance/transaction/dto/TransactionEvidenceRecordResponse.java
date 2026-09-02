package com.pharos.compliance.transaction.dto;

import com.pharos.compliance.transaction.model.TransactionEvidenceSource;
import com.pharos.compliance.transaction.model.TransactionOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "One available latest-state journey or exclusion-audit record")
public record TransactionEvidenceRecordResponse(String recordKey, String identifier, String mtcn, String batchId,
    TransactionEvidenceSource source, String stage, String status, TransactionOutcome outcome, String comments, String skipReason,
    String ruleId, String exclusionReason, String exclusionStrategy, String reportedBatchId, String reportingTimestamp, String modifiedAt,
    Boolean processingComplete, BigDecimal currencyAmount, String currencyCode, String transactionDate, String transactionSide,
    String txnSource, String activityType, String sendDate, String galacticId, Integer bucketId, Long attemptId, String senderName,
    String receiverName, String senderCity, String senderCountry, String senderPhone, String senderDateOfBirth, String senderIdType,
    String senderIdNumber, String receiverCity, String receiverCountry, String receiverPhone, String receiverDateOfBirth,
    String receiverIdType, String receiverIdNumber, String transactionStatus, String transactionSubStatus, String ruleHitsJson) {}
