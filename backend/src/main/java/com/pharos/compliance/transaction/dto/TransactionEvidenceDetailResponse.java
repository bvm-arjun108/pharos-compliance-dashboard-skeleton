package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Everything the expanded row renders and the list deliberately does not carry. The party fields
 * here are personal data -- names, dates of birth, phone numbers and government ID numbers -- which
 * is the reason this is a separate request at all: the list response is read on every page load,
 * sort and filter change, while this is read only when someone actually opens a transaction. That
 * makes "who looked at whose details" a question this endpoint can answer, and one the list could
 * never answer while it shipped the same fields for every row regardless.
 */
@Schema(description = "One transaction's full evidence detail, fetched when its row is expanded")
public record TransactionEvidenceDetailResponse(String recordKey, String identifier, String mtcn, String batchId, String senderName,
    String senderCity, String senderCountry, String senderPhone, String senderDateOfBirth, String senderIdType, String senderIdNumber,
    String receiverName, String receiverCity, String receiverCountry, String receiverPhone, String receiverDateOfBirth,
    String receiverIdType, String receiverIdNumber, BigDecimal currencyAmount, String currencyCode, String transactionDate, String sendDate,
    String transactionSide, String transactionStatus, String transactionSubStatus, String comments, String skipReason, String ruleId,
    String exclusionReason, String exclusionStrategy, String reportingTimestamp, String txnSource, String activityType, String galacticId,
    Integer bucketId, Long attemptId,
    @Schema(description = "Every pharos.rule_hit record matched to this transaction's identifier within this request's scope, as a JSON "
    + "array. Independent of the row's own evidence source and of whatever status/metric filter the list was under.") String ruleHitsJson) {}
