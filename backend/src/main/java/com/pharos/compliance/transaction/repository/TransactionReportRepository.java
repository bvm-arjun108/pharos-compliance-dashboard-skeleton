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

  @Query(value = TransactionReportNativeQueries.RECORD_DETAIL, nativeQuery = true)
  Optional<TransactionRecordDetailProjection> findRecordDetail(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("identifier") String identifier,
      @Param("status") String status,
      @Param("metric") String metric,
      @Param("source") String source,
      @Param("search") String search);

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

  @Query(value = PeriodTransactionNativeQueries.AGGREGATE, nativeQuery = true)
  PeriodAggregateProjection findPeriodAggregate(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId);

  @Query(value = PeriodTransactionNativeQueries.EVIDENCE_RECORDS, nativeQuery = true)
  List<TransactionEvidenceProjection> findPeriodEvidenceRecords(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
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
      @Param("batchId") String batchId,
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

  /** The list row. Detail-panel fields live on TransactionRecordDetailProjection, fetched on
   *  demand, so the list query does not join for data the table never renders. */
  interface TransactionEvidenceProjection {
    String getRecordKey();

    int getReportGroupId();

    String getIdentifier();

    String getMtcn();

    String getBatchId();

    String getEvidenceSource();

    String getStatus();

    String getComments();

    String getSkipReason();

    String getExclusionReason();

    String getReportedBatchId();

    String getModifiedAt();

    Boolean getProcessingComplete();
  }

  interface TransactionRecordDetailProjection {
    String getIdentifier();

    String getRuleId();

    String getExclusionStrategy();

    Integer getBucketId();

    Long getAttemptId();

    String getGalacticId();

    String getTransactionSide();

    String getTxnSource();

    String getActivityType();

    BigDecimal getCurrencyAmount();

    String getCurrencyCode();

    String getTransactionDate();

    String getSendDate();

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
}
