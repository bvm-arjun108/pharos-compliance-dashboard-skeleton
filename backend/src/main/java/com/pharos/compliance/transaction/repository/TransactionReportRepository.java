package com.pharos.compliance.transaction.repository;

import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationEntity;
import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationId;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface TransactionReportRepository
    extends Repository<
        ReportTransformationReconciliationEntity, ReportTransformationReconciliationId> {

  @Query(value = TransactionReportNativeQueries.REPORT_CONTEXT, nativeQuery = true)
  Optional<TransactionReportContextProjection> findReportContext(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("sequenceNumber") int sequenceNumber);

  @Query(value = TransactionReportNativeQueries.EVIDENCE_RECORDS, nativeQuery = true)
  List<TransactionEvidenceProjection> findEvidenceRecords(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("metric") String metric,
      @Param("search") String search,
      @Param("source") String source,
      @Param("stage") String stage,
      @Param("outcome") String outcome,
      @Param("status") String status,
      @Param("sortDirection") String sortDirection,
      @Param("size") int size,
      @Param("offset") long offset);

  @Query(value = TransactionReportNativeQueries.EVIDENCE_COUNT, nativeQuery = true)
  long countEvidenceRecords(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("metric") String metric,
      @Param("search") String search,
      @Param("source") String source,
      @Param("stage") String stage,
      @Param("outcome") String outcome,
      @Param("status") String status);

  @Query(value = TransactionReportNativeQueries.OUTCOME_BREAKDOWN, nativeQuery = true)
  TransactionOutcomeBreakdownProjection findOutcomeBreakdown(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("metric") String metric,
      @Param("search") String search,
      @Param("source") String source,
      @Param("stage") String stage);

  @Query(value = TransactionReportNativeQueries.STAGE_BREAKDOWN, nativeQuery = true)
  List<TransactionStageBreakdownProjection> findStageBreakdown(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("metric") String metric,
      @Param("search") String search,
      @Param("source") String source,
      @Param("outcome") String outcome);

  @Query(value = PeriodTransactionNativeQueries.AGGREGATE, nativeQuery = true)
  PeriodAggregateProjection findPeriodAggregate(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId);

  @Query(value = PeriodTransactionNativeQueries.EVIDENCE_RECORDS, nativeQuery = true)
  List<TransactionEvidenceProjection> findPeriodEvidenceRecords(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId,
      @Param("search") String search,
      @Param("outcome") String outcome,
      @Param("status") String status,
      @Param("sortDirection") String sortDirection,
      @Param("size") int size,
      @Param("offset") long offset);

  @Query(value = PeriodTransactionNativeQueries.EVIDENCE_COUNT, nativeQuery = true)
  long countPeriodEvidenceRecords(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId,
      @Param("search") String search,
      @Param("outcome") String outcome,
      @Param("status") String status);

  interface PeriodAggregateProjection {
    long getBatchCount();

    long getTotalExcluded();

    String getReportGroupName();
  }

  interface TransactionReportContextProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    long getSelectedTransactions();

    long getAttemptsFound();

    long getMissingAttempts();

    long getExpectedEligible();

    long getActualEligible();

    long getTransformed();

    long getFailed();

    long getExpectedReportable();

    long getActualReportable();

    long getExcluded();

    long getSimulated();

    long getAlreadyReported();

    long getSoftDedup();

    long getFiltrationVariance();

    long getReconciliationVariance();
  }

  interface TransactionEvidenceProjection {
    String getRecordKey();

    String getIdentifier();

    String getMtcn();

    String getBatchId();

    String getEvidenceSource();

    String getStage();

    String getStatus();

    String getOutcome();

    String getComments();

    String getSkipReason();

    String getRuleId();

    String getExclusionReason();

    String getExclusionStrategy();

    String getReportedBatchId();

    String getReportingTimestamp();

    String getModifiedAt();

    Boolean getProcessingComplete();

    BigDecimal getCurrencyAmount();

    String getCurrencyCode();

    String getTransactionDate();

    String getTransactionSide();

    String getTxnSource();

    String getActivityType();

    String getSendDate();

    String getGalacticId();

    Integer getBucketId();

    Long getAttemptId();

    String getSenderName();

    String getReceiverName();

    String getSenderCity();

    String getSenderCountry();

    String getSenderPhone();

    String getSenderDateOfBirth();

    String getSenderIdType();

    String getSenderIdNumber();

    String getReceiverCity();

    String getReceiverCountry();

    String getReceiverPhone();

    String getReceiverDateOfBirth();

    String getReceiverIdType();

    String getReceiverIdNumber();

    String getTransactionStatus();

    String getTransactionSubStatus();

    String getRuleHitsJson();
  }

  interface TransactionOutcomeBreakdownProjection {
    long getSuccessCount();

    long getErrorCount();

    long getPendingCount();

    long getExcludedCount();

    long getTotalCount();
  }

  interface TransactionStageBreakdownProjection {
    String getStage();

    long getSuccessCount();

    long getErrorCount();

    long getPendingCount();

    long getExcludedCount();

    long getTotalCount();
  }
}
