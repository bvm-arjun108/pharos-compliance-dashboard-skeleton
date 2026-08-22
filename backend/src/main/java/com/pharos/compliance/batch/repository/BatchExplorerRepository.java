package com.pharos.compliance.batch.repository;

import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationEntity;
import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface BatchExplorerRepository
    extends Repository<
        ReportTransformationReconciliationEntity, ReportTransformationReconciliationId> {

  @Query(value = BatchExplorerNativeQueries.BATCH_SUMMARY, nativeQuery = true)
  BatchSummaryProjection getBatchSummary(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("batchId") String batchId,
      @Param("reportGroupId") Integer reportGroupId,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds);

  @Query(value = BatchExplorerNativeQueries.BATCH_QUEUE, nativeQuery = true)
  List<BatchQueueProjection> getBatchQueue(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("batchId") String batchId,
      @Param("reportGroupId") Integer reportGroupId,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("status") String status,
      @Param("issueType") String issueType,
      @Param("metricFocus") String metricFocus,
      @Param("size") int size,
      @Param("offset") long offset);

  @Query(value = BatchExplorerNativeQueries.BATCH_DETAILS, nativeQuery = true)
  Optional<BatchDetailsProjection> getBatchDetails(
      @Param("reportGroupId") int reportGroupId,
      @Param("batchId") String batchId,
      @Param("sequenceNumber") int sequenceNumber);

  @Query(value = BatchExplorerNativeQueries.BATCH_DETAILS_NOT_YET_REPORTED, nativeQuery = true)
  Optional<NotYetReportedBatchDetailsProjection> getNotYetReportedBatchDetails(
      @Param("reportGroupId") int reportGroupId, @Param("batchId") String batchId);

  interface BatchSummaryProjection {
    long getAllBatches();

    long getSuccessfulBatches();

    long getAttentionBatches();

    long getNotYetReportedBatches();

    String getReportGroupName();
  }

  interface BatchQueueProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    LocalDateTime getStartedAt();

    LocalDateTime getCompletedAt();

    long getTransformationFailures();

    long getMissingAttempts();

    long getFiltrationErrors();

    long getReconciliationImbalance();

    long getReportedTransactions();

    long getExcludedTransactions();

    long getTotalIssues();

    long getDiscoveredTransactions();

    String getStatusBucket();

    long getMatchingCount();
  }

  interface BatchDetailsProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    int getSequenceNumber();

    String getReportingPeriodFrom();

    String getReportingPeriodTo();

    LocalDateTime getStartedAt();

    LocalDateTime getCompletedAt();

    long getTransformationFailures();

    long getMissingAttempts();

    long getFiltrationErrors();

    long getReconciliationImbalance();

    long getSelectedTransactions();

    long getTransactionAttemptsFound();

    long getExpectedReportableTransactions();

    long getActualReportableTransactions();

    long getExpectedTransformationAttempts();

    long getActualTransformationAttempts();

    long getTransformedActivities();

    long getTransformerOutput();

    long getExcludedTransactions();

    boolean getJourneyAvailable();

    boolean getExclusionsAvailable();
  }

  interface NotYetReportedBatchDetailsProjection {
    int getReportGroupId();

    String getReportGroupName();

    String getBatchId();

    LocalDateTime getStartedAt();

    LocalDateTime getLastActivityAt();

    long getDiscoveredTransactions();

    long getStalledTransactions();

    boolean getJourneyAvailable();

    boolean getExclusionsAvailable();
  }
}
