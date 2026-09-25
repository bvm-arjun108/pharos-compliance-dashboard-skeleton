package com.pharos.compliance.batch.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.batch.repository.projection.BatchDetailsProjection;
import com.pharos.compliance.batch.repository.projection.BatchQueueProjection;
import com.pharos.compliance.batch.repository.projection.BatchSummaryProjection;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Executes the Phase 3 JDBC migration of {@link BatchExplorerRepository} (and the {@code
 * journeyFailuresByBatch}/{@code journeyStatsLateral} fragments it shares with the not-yet-migrated
 * {@code DashboardRepository}/{@code TransactionReportRepository}) against a real PostgreSQL
 * instance. Three batches in report group 8001 exercise the journey-derived-correction logic that
 * is this repository's whole reason for existing:
 *
 * <ul>
 *   <li>BATCH-A: reconciliation says 2 transformation failures, but 3 distinct identifiers actually
 *       have journey FAILED rows -- the corrected count (3) must win, flagged as a mismatch.
 *   <li>BATCH-B: reconciliation says 0 failures and has zero journey rows at all -- no journey
 *       evidence, so the raw reconciliation value is used and there is no mismatch.
 *   <li>BATCH-C: no transformation failures, but has other issues (missing attempts, activity
 *       missing, excluded/duplicate transactions) to exercise issueType filtering.
 * </ul>
 */
class BatchExplorerRepositoryIntegrationTest extends PostgresIntegrationTest {
  private static final int GROUP = 8001;
  private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
  private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 0, 0);
  private BatchExplorerRepository repository;

  @BeforeEach
  void setUp() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_transformation_reconciliation where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_batch_info where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit_exclusion_audit where rpt_grp_id = " + GROUP);
    repository = new BatchExplorerRepository(tracingJdbcTemplate, new SqlResourceLoader(new DefaultResourceLoader()));

    insertReconciliation("BATCH-A", 2, 0, 0, 0, 0, 0, 5);
    insertJourneyFailure("BATCH-A", "ident-1");
    insertJourneyFailure("BATCH-A", "ident-2");
    insertJourneyFailure("BATCH-A", "ident-3");

    insertReconciliation("BATCH-B", 0, 0, 0, 0, 0, 0, 0);

    insertReconciliation("BATCH-C", 0, 1, 1, 5, 2, 0, 10);

    insertBatchInfo("BATCH-A", 3, "3.0");
    insertExclusionAudit("BATCH-A");
  }

  private void insertReconciliation(String batchId, int activityTransformationFailed, int missingAttempts, int activityMissing,
      int excludedTxn, int duplicateTransformation, int softDedup, int actualReportableTxn) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, rpt_grp_name, created_timestamp, "
        + "modified_timestamp, activity_transformation_failed, txn_missing_attempt_count, activity_missing, excluded_txn, "
        + "duplicate_transformation, soft_dedup_dropped_txn_count, actual_reportable_txn, expected_reportable_txn, "
        + "expected_activity_eligible_for_transformation, actual_activity_eligible_for_transformation) "
        + "values (:groupId, :batchId, 1, 'Test Group 8001', :created, :created, :failed, :missing, :activityMissing, :excluded, "
        + "        :duplicate, :softDedup, :output, :output, 0, 0)",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("batchId", batchId)
          .addValue("created", FROM.plusDays(1))
          .addValue("failed", activityTransformationFailed)
          .addValue("missing", missingAttempts)
          .addValue("activityMissing", activityMissing)
          .addValue("excluded", excludedTxn)
          .addValue("duplicate", duplicateTransformation)
          .addValue("softDedup", softDedup)
          .addValue("output", actualReportableTxn));
  }

  private void insertJourneyFailure(String batchId, String identifier) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, stage, status) "
        + "values (:groupId, :batchId, :identifier, 'TRANSFORMATION', 'FAILED')",
        new MapSqlParameterSource().addValue("groupId", GROUP).addValue("batchId", batchId).addValue("identifier", identifier));
  }

  private void insertBatchInfo(String batchId, int selectionVersion, String transformerVersion) {
    jdbcTemplate.update("insert into pharos.report_batch_info (rpt_grp_id, batch_id, seq_no, selection_version, transformer_mapping_version) "
        + "values (:groupId, :batchId, 1, :selectionVersion, :transformerVersion)",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("batchId", batchId)
          .addValue("selectionVersion", selectionVersion)
          .addValue("transformerVersion", transformerVersion));
  }

  private void insertExclusionAudit(String batchId) {
    jdbcTemplate.update("insert into pharos.rule_hit_exclusion_audit (attempt_id, rpt_grp_id, rule_id, bucket_id, processing_batch_id) "
        + "values (:attemptId, :groupId, 'RULE-1', 1, :batchId)",
        new MapSqlParameterSource().addValue("attemptId", (long) batchId.hashCode()).addValue("groupId", GROUP).addValue("batchId", batchId));
  }

  @Test
  void getBatchSummaryCountsSuccessfulVsAttentionUsingTheJourneyCorrectedFailureCount() {
    BatchSummaryProjection summary = repository.getBatchSummary(FROM, TO, "", GROUP, false, List.of());

    assertEquals(3, summary.allBatches());
    assertEquals(1, summary.successfulBatches(), "only BATCH-B has zero total issues");
    assertEquals(2, summary.attentionBatches(), "BATCH-A (corrected failures) and BATCH-C (other issues)");
    assertEquals("Test Group 8001", summary.reportGroupName());
  }

  @Test
  void getBatchQueueFiltersByStatus() {
    List<BatchQueueProjection> attention =
        repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ATTENTION", "ALL", "DEFAULT", 50, 0);
    assertEquals(2, attention.size());
    assertTrue(attention.stream().map(BatchQueueProjection::batchId).toList().containsAll(List.of("BATCH-A", "BATCH-C")));

    List<BatchQueueProjection> successful =
        repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "SUCCESSFUL", "ALL", "DEFAULT", 50, 0);
    assertEquals(1, successful.size());
    assertEquals("BATCH-B", successful.get(0).batchId());
  }

  @Test
  void getBatchQueueUsesTheJourneyCorrectedCountForTheTransformationIssueType() {
    // BATCH-A's reconciliation column says 2 failures, but the journey-derived count is 3 -- the
    // TRANSFORMATION issue filter and the returned transformationFailures value must both reflect
    // the corrected count, not the raw reconciliation column.
    List<BatchQueueProjection> transformationIssues =
        repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "TRANSFORMATION", "DEFAULT", 50, 0);

    assertEquals(1, transformationIssues.size());
    BatchQueueProjection batchA = transformationIssues.get(0);
    assertEquals("BATCH-A", batchA.batchId());
    assertEquals(3, batchA.transformationFailures(), "corrected (journey-derived) count");
    assertEquals(2, batchA.reportedTransformationFailures(), "raw reconciliation column, kept visible");
    assertTrue(batchA.transformationFailureMismatch());
  }

  @Test
  void getBatchQueueFiltersByOtherIssueTypes() {
    assertEquals(1, repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "EXCLUSION", "DEFAULT", 50, 0).size());
    assertEquals("BATCH-C",
        repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "EXCLUSION", "DEFAULT", 50, 0).get(0).batchId());
    assertEquals(1,
        repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "DUPLICATE_TRANSFORMATION", "DEFAULT", 50, 0).size());
    assertEquals(1, repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "MISSING_ATTEMPTS", "DEFAULT", 50, 0).size());
    assertEquals(1, repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "ACTIVITY_MISSING", "DEFAULT", 50, 0).size());
  }

  @Test
  void getBatchQueueMetricFocusReportedOrdersByTransformerOutputDescending() {
    List<BatchQueueProjection> reported = repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "ALL", "REPORTED", 50, 0);

    assertEquals(2, reported.size(), "BATCH-A (output=5) and BATCH-C (output=10); BATCH-B has output=0");
    assertEquals("BATCH-C", reported.get(0).batchId(), "higher transformerOutput sorts first");
    assertEquals("BATCH-A", reported.get(1).batchId());
  }

  @Test
  void getBatchQueueMatchingCountReflectsTheFullFilteredSetNotJustThePage() {
    List<BatchQueueProjection> firstPage = repository.getBatchQueue(FROM, TO, "", GROUP, false, List.of(), "ALL", "ALL", "DEFAULT", 2, 0);
    assertEquals(2, firstPage.size());
    assertEquals(3, firstPage.get(0).matchingCount(), "matchingCount is the total across all 3 batches, not just this page");
  }

  @Test
  void getBatchQueueFiltersByBatchIdSearchCaseInsensitively() {
    List<BatchQueueProjection> matches =
        repository.getBatchQueue(FROM, TO, "batch-a", GROUP, false, List.of(), "ALL", "ALL", "DEFAULT", 50, 0);
    assertEquals(1, matches.size());
    assertEquals("BATCH-A", matches.get(0).batchId());
  }

  @Test
  void getBatchDetailsAppliesTheJourneyCorrectionAndFlagsTheMismatch() {
    BatchDetailsProjection details = repository.getBatchDetails(GROUP, "BATCH-A", 1).orElseThrow();

    assertTrue(details.journeyAvailable());
    assertEquals(3, details.transformationFailures(), "corrected by the journey-derived count");
    assertEquals(2, details.reportedTransformationFailures());
    assertTrue(details.transformationFailureMismatch());
    assertTrue(details.exclusionsAvailable(), "an exclusion-audit row exists for this batch");
    assertEquals(3, details.reportSelectionVersionId());
    assertEquals("3.0", details.transformerVersionId());
  }

  @Test
  void getBatchDetailsFallsBackToTheRawReconciliationValueWhenNoJourneyRowsExist() {
    BatchDetailsProjection details = repository.getBatchDetails(GROUP, "BATCH-B", 1).orElseThrow();

    assertFalse(details.journeyAvailable(), "no journey rows at all for this batch");
    assertEquals(0, details.transformationFailures());
    assertFalse(details.transformationFailureMismatch());
    assertFalse(details.exclusionsAvailable(), "no exclusion-audit row for this batch");
    assertEquals(null, details.reportSelectionVersionId(), "no report_batch_info row for this batch");
  }
}
