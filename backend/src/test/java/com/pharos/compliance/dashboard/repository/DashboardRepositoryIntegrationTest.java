package com.pharos.compliance.dashboard.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ExclusionReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.NotReportedReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionOverviewProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionVolumeTrendProjection;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Executes the Phase 4 JDBC migration of {@link DashboardRepository} against a real PostgreSQL
 * instance. {@link #getBatchHealthTrendWeeklyBucketsAcrossDistinctWeeks} and {@link
 * #getTransactionVolumeTrendWeeklyBucketsAcrossDistinctWeeks} specifically guard the WEEKLY
 * granularity path, which the port-8086 diff-harness sweep caught failing outright during this
 * migration (a repeated {@code :fromDate} reference in both the SELECT list and a textual GROUP BY
 * copy bound to a different parameter marker each time, so Postgres rejected the query as
 * ungrouped) -- fixed by grouping on ordinal position instead of repeating the expression text.
 */
class DashboardRepositoryIntegrationTest extends PostgresIntegrationTest {
  private static final int GROUP = 9001;
  private static final int CLEAN_GROUP = 9002;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 0, 0);
  private DashboardRepository repository;

  @BeforeEach
  void setUp() {
    for (int group : List.of(GROUP, CLEAN_GROUP)) {
      jdbcTemplate.getJdbcOperations().update("delete from pharos.report_transformation_reconciliation where rpt_grp_id = " + group);
      jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + group);
      jdbcTemplate.getJdbcOperations().update("delete from pharos.report_batch_info where rpt_grp_id = " + group);
    }
    repository = new DashboardRepository(tracingJdbcTemplate, new SqlResourceLoader(new DefaultResourceLoader()));
  }

  private void insertReconciliation(int group, String batchId, LocalDateTime created, int activityTransformationFailed, int missingAttempts,
      int activityMissing, int excludedTxn, int activityTransformed) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, rpt_grp_name, created_timestamp, "
        + "modified_timestamp, activity_transformation_failed, txn_missing_attempt_count, activity_missing, excluded_txn, "
        + "activity_transformed) "
        + "values (:groupId, :batchId, 1, :groupName, :created, :created, :failed, :missing, :activityMissing, :excluded, :transformed)",
        new MapSqlParameterSource()
          .addValue("groupId", group)
          .addValue("batchId", batchId)
          .addValue("groupName", "Group " + group)
          .addValue("created", created)
          .addValue("failed", activityTransformationFailed)
          .addValue("missing", missingAttempts)
          .addValue("activityMissing", activityMissing)
          .addValue("excluded", excludedTxn)
          .addValue("transformed", activityTransformed));
  }

  private void insertJourney(int group, String batchId, String identifier, String stage, String status, String comments) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, stage, status, comments) "
        + "values (:groupId, :batchId, :identifier, :stage, :status, :comments)",
        new MapSqlParameterSource()
          .addValue("groupId", group)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("stage", stage)
          .addValue("status", status)
          .addValue("comments", comments));
  }

  @Test
  void getDashboardCountsUsesJourneyCorrectionAndKeepsIssueBucketsMutuallyExclusive() {
    // BATCH-A: reconciliation says 1 failure, journey says 2 -- corrected count wins.
    insertReconciliation(GROUP, "BATCH-A", FROM.plusDays(1), 1, 0, 0, 0, 10);
    insertJourney(GROUP, "BATCH-A", "id-1", "TRANSFORMATION", "FAILED", null);
    insertJourney(GROUP, "BATCH-A", "id-2", "TRANSFORMATION", "FAILED", null);
    // BATCH-B: no failures, but excluded_txn > 0 -- counts toward exclusion, not attention.
    insertReconciliation(GROUP, "BATCH-B", FROM.plusDays(2), 0, 0, 0, 5, 10);
    // BATCH-C: perfectly clean.
    insertReconciliation(GROUP, "BATCH-C", FROM.plusDays(3), 0, 0, 0, 0, 10);

    DashboardCountsProjection counts = repository.getDashboardCounts(FROM, TO, "", false, List.of(), true, GROUP);

    assertEquals(3, counts.batchesRan());
    assertEquals(1, counts.batchesNeedingAttention(), "only BATCH-A has a real issue");
    assertEquals(1, counts.transformationFailureBatches());
    assertEquals(1, counts.exclusionBatches(), "BATCH-B's exclusion counts since it has no prior issue");
  }

  @Test
  void getReportGroupsRequiringAttentionHidesCleanGroupsUnlessScoped() {
    insertReconciliation(GROUP, "BATCH-A", FROM.plusDays(1), 1, 0, 0, 0, 10);
    insertReconciliation(CLEAN_GROUP, "BATCH-X", FROM.plusDays(1), 0, 0, 0, 0, 10);

    List<ReportGroupMetricsProjection> unscoped = repository.getReportGroupsRequiringAttention(FROM, TO, "", false, List.of(), false, -1);
    assertTrue(unscoped
      .stream()
      .noneMatch(g -> g.reportGroupId() == CLEAN_GROUP), "clean group hidden when unscoped");
    assertTrue(unscoped
      .stream()
      .anyMatch(g -> g.reportGroupId() == GROUP));

    List<ReportGroupMetricsProjection> scoped =
        repository.getReportGroupsRequiringAttention(FROM, TO, "", false, List.of(), true, CLEAN_GROUP);
    assertEquals(1, scoped.size(), "a specific report group is shown even with nothing to flag");
    assertEquals(CLEAN_GROUP, scoped.get(0).reportGroupId());
  }

  @Test
  void getBatchHealthTrendWeeklyBucketsAcrossDistinctWeeks() {
    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 8, 31);
    insertReconciliation(GROUP, "BATCH-WEEK1", LocalDateTime.of(2026, 6, 2, 0, 0), 0, 0, 0, 0, 10);
    insertReconciliation(GROUP, "BATCH-WEEK2", LocalDateTime.of(2026, 6, 10, 0, 0), 1, 0, 0, 0, 10);

    List<BatchHealthTrendProjection> trend = repository.getBatchHealthTrend(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), from, to,
        "WEEKLY", "", false, List.of(), true, GROUP);

    long totalRan = trend.stream().mapToLong(BatchHealthTrendProjection::batchesRan).sum();
    long totalAttention = trend.stream().mapToLong(BatchHealthTrendProjection::batchesNeedingAttention).sum();
    assertEquals(2, totalRan);
    assertEquals(1, totalAttention);
    assertTrue(trend.size() > 1, "a 3-month range must produce more than one weekly bucket");
  }

  @Test
  void getTransactionVolumeTrendWeeklyBucketsAcrossDistinctWeeks() {
    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 8, 31);
    insertReconciliation(GROUP, "BATCH-WEEK1", LocalDateTime.of(2026, 6, 2, 0, 0), 0, 0, 0, 3, 20);
    insertReconciliation(GROUP, "BATCH-WEEK2", LocalDateTime.of(2026, 6, 10, 0, 0), 0, 0, 0, 7, 30);

    List<TransactionVolumeTrendProjection> trend = repository.getTransactionVolumeTrend(from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
        from, to, "WEEKLY", "", false, List.of(), true, GROUP);

    long totalReported = trend.stream().mapToLong(TransactionVolumeTrendProjection::totalReportedTransactions).sum();
    long totalExcluded = trend.stream().mapToLong(TransactionVolumeTrendProjection::totalExcludedTransactions).sum();
    assertEquals(50, totalReported);
    assertEquals(10, totalExcluded);
  }

  @Test
  void getTransactionOverviewPartitionsSelectedIntoExpectedExcludedAndNotReported() {
    insertReconciliation(GROUP, "BATCH-A", FROM.plusDays(1), 0, 0, 0, 0, 10);
    insertJourney(GROUP, "BATCH-A", "reported-1", "REPORT_GENERATION", "GENERATED", null);
    insertJourney(GROUP, "BATCH-A", "excluded-1", "TRANSFORMATION", "EXCLUDED", "Some reason");
    insertJourney(GROUP, "BATCH-A", "not-reported-1", "TRANSFORMATION", "PENDING", null);

    TransactionOverviewProjection overview = repository.getTransactionOverview(FROM, TO, "", false, List.of(), true, GROUP);

    assertEquals(3, overview.selected());
    assertEquals(1, overview.excluded());
    assertEquals(1, overview.notReported());
    assertEquals(2, overview.expected(), "expected = selected - excluded");
  }

  @Test
  void getTopExclusionReasonsCollapsesBeyondTopThreeIntoOther() {
    insertReconciliation(GROUP, "BATCH-A", FROM.plusDays(1), 0, 0, 0, 0, 10);
    insertJourney(GROUP, "BATCH-A", "id-1", "TRANSFORMATION", "EXCLUDED", "Reason A");
    insertJourney(GROUP, "BATCH-A", "id-2", "TRANSFORMATION", "EXCLUDED", "Reason A");
    insertJourney(GROUP, "BATCH-A", "id-3", "TRANSFORMATION", "EXCLUDED", "Reason B");
    insertJourney(GROUP, "BATCH-A", "id-4", "TRANSFORMATION", "EXCLUDED", "Reason C");
    insertJourney(GROUP, "BATCH-A", "id-5", "TRANSFORMATION", "EXCLUDED", "Reason D");

    List<ExclusionReasonProjection> reasons = repository.getTopExclusionReasons(FROM, TO, "", false, List.of(), true, GROUP);

    assertEquals(4, reasons.size(), "top 3 plus one Other bucket");
    assertEquals("Reason A", reasons.get(0).reason(), "highest count sorts first");
    assertEquals(2, reasons.get(0).count());
    assertEquals("Other", reasons.get(reasons.size() - 1).reason(), "Other is always last");
    assertEquals(1, reasons.get(reasons.size() - 1).count(), "Reason D alone collapsed into Other");
  }

  @Test
  void getNotReportedReasonsFallsBackToSkipReasonWhenCommentsIsNull() {
    insertReconciliation(GROUP, "BATCH-A", FROM.plusDays(1), 0, 0, 0, 0, 10);
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, stage, status, comments, skip_reason) "
        + "values (:groupId, 'BATCH-A', 'id-1', 'TRANSFORMATION', 'PENDING', null, 'Skip text')",
        new MapSqlParameterSource("groupId", GROUP));

    List<NotReportedReasonProjection> reasons = repository.getNotReportedReasons(FROM, TO, "", false, List.of(), true, GROUP);

    assertEquals(1, reasons.size());
    assertEquals("Skip text", reasons.get(0).reason());
  }
}
