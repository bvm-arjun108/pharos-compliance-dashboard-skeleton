package com.pharos.compliance.dashboard.repository;

import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationEntity;
import com.pharos.compliance.dashboard.entity.ReportTransformationReconciliationId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface DashboardRepository
    extends Repository<
        ReportTransformationReconciliationEntity, ReportTransformationReconciliationId> {

  @Query(value = DashboardNativeQueries.DASHBOARD_COUNTS, nativeQuery = true)
  DashboardCountsProjection getDashboardCounts(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("batchId") String batchId,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId);

  @Query(value = DashboardNativeQueries.REPORT_GROUP_ATTENTION, nativeQuery = true)
  List<ReportGroupMetricsProjection> getReportGroupsRequiringAttention(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("batchId") String batchId,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId);

  @Query(value = DashboardNativeQueries.BATCH_HEALTH_TREND, nativeQuery = true)
  List<BatchHealthTrendProjection> getBatchHealthTrend(
      @Param("fromTimestamp") LocalDateTime fromTimestamp,
      @Param("toTimestampExclusive") LocalDateTime toTimestampExclusive,
      @Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate,
      @Param("granularity") String granularity,
      @Param("batchId") String batchId,
      @Param("filterByCountry") boolean filterByCountry,
      @Param("reportGroupIds") List<Integer> reportGroupIds,
      @Param("filterByReportGroup") boolean filterByReportGroup,
      @Param("reportGroupId") int reportGroupId);

  interface DashboardCountsProjection {
    long getBatchesRan();

    long getBatchesNotYetReported();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getDuplicateTransactionBatches();

    long getExclusionBatches();

    long getSimulatedTransactionBatches();

    long getSoftDedupBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }

  interface ReportGroupMetricsProjection {
    int getReportGroupId();

    String getReportGroupName();

    long getBatchesRan();

    long getSuccessfulBatches();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }

  interface BatchHealthTrendProjection {
    LocalDate getPeriodStart();

    long getBatchesRan();

    long getSuccessfulBatches();

    long getBatchesNeedingAttention();

    long getTransformationFailureBatches();

    long getMissingAttemptBatches();

    long getActivityMissingBatches();

    long getTotalReportedTransactions();

    long getTotalExcludedTransactions();
  }
}
