package com.pharos.compliance.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * The expanded-row payload for a single transaction, fetched on demand rather than shipped with
 * every list row. Every field here requires a join the transaction list itself does not need.
 */
@Schema(description = "On-demand detail for one transaction in the evidence list")
public record TransactionRecordDetailResponse(
    String identifier,
    String ruleId,
    String exclusionStrategy,
    Integer bucketId,
    Long attemptId,
    String galacticId,
    String transactionSide,
    String txnSource,
    String activityType,
    BigDecimal currencyAmount,
    String currencyCode,
    String transactionDate,
    String sendDate,
    String senderName,
    String receiverName,
    String senderCity,
    String senderCountry,
    String senderPhone,
    String senderDateOfBirth,
    String senderIdType,
    String senderIdNumber,
    String receiverCity,
    String receiverCountry,
    String receiverPhone,
    String receiverDateOfBirth,
    String receiverIdType,
    String receiverIdNumber,
    String transactionStatus,
    String transactionSubStatus,
    String ruleHitsJson) {}
