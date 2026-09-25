package com.pharos.compliance.transaction.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Phase 6g's own verification for {@link TransactionReportRepository} -- the facade itself, now
 * hand-SQL, wired to every migrated evidence pipeline. Originally written with an inline {@code
 * oldFindReportContext} helper reproducing the pre-migration jOOQ query verbatim (since that query
 * was always inline in this facade, never extracted into its own coexisting class the way every
 * other pipeline in this migration was); that comparison was removed in Phase 6h once the old jOOQ
 * query had no reason left to exist, leaving direct assertions on the real hand-SQL result.
 *
 * <p>Replaces the pre-migration version of this test, which used a jOOQ {@code MockConnection} to
 * capture rendered SQL text and bind values -- a mechanism with no hand-SQL equivalent, since there
 * is no jOOQ renderer standing between the repository and the database anymore. Real execution
 * against seeded Testcontainers data proves the same two behaviors more directly: an identifier
 * containing SQL wildcard characters is matched by exact equality, never substring search, and a
 * period-scope batch-ID search filter never gets confused with an exact evidence-batch identity
 * match.
 */
class TransactionDetailLookupTest extends PostgresIntegrationTest {
  private TransactionReportRepository repository;

  @BeforeEach
  void setUp() {
    for (String table : List.of("rule_hit", "rule_hit_exclusion_audit", "record_transformation_journey", "report_batch_info")) {
      jdbcTemplate.getJdbcOperations().update("delete from pharos." + table + " where rpt_grp_id in (8501, 8502, 8503, 8504)");
    }
    jdbcTemplate
      .getJdbcOperations()
      .update("delete from pharos.report_transformation_reconciliation where rpt_grp_id in (8501, 8502, 8503, 8504)");

    SqlResourceLoader sqlLoader = new SqlResourceLoader(new DefaultResourceLoader());
    repository = new TransactionReportRepository(tracingJdbcTemplate, sqlLoader);
  }

  private void insertReconciliation(int groupId, String batchId, int txnSelected, int missingAttempt, int activityMissing,
      int expectedEligible, int actualEligible, int transformed, int transformationFailed, int expectedReportable, int actualReportable,
      int excluded, int simulated, int alreadyReported, int softDedup) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, rpt_grp_name, "
        + "rpt_from_date, rpt_to_date, created_timestamp, txn_selected, txn_missing_attempt_count, activity_missing, "
        + "expected_activity_eligible_for_transformation, actual_activity_eligible_for_transformation, activity_transformed, "
        + "activity_transformation_failed, expected_reportable_txn, actual_reportable_txn, excluded_txn, txn_simulated, "
        + "already_reported_count, soft_dedup_dropped_txn_count) "
        + "values (:groupId, :batchId, 1, 'Group RC', '2024-01-01', '2024-01-31', now(), :txnSelected, :missingAttempt, :activityMissing, "
        + ":expectedEligible, :actualEligible, :transformed, :transformationFailed, :expectedReportable, :actualReportable, :excluded, "
        + ":simulated, :alreadyReported, :softDedup)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("txnSelected", txnSelected)
          .addValue("missingAttempt", missingAttempt)
          .addValue("activityMissing", activityMissing)
          .addValue("expectedEligible", expectedEligible)
          .addValue("actualEligible", actualEligible)
          .addValue("transformed", transformed)
          .addValue("transformationFailed", transformationFailed)
          .addValue("expectedReportable", expectedReportable)
          .addValue("actualReportable", actualReportable)
          .addValue("excluded", excluded)
          .addValue("simulated", simulated)
          .addValue("alreadyReported", alreadyReported)
          .addValue("softDedup", softDedup));
  }

  private void insertJourney(int groupId, String batchId, String identifier, String stage, String status, LocalDateTime modified) {
    insertJourney(groupId, batchId, identifier, stage, status, null, modified);
  }

  private void insertJourney(int groupId, String batchId, String identifier, String stage, String status, String comments,
      LocalDateTime modified) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, stage, status, comments, "
        + "modified_timestamp, processing_complete) values (:groupId, :batchId, :identifier, :stage, :status, :comments, :modified, true)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("stage", stage)
          .addValue("status", status)
          .addValue("comments", comments)
          .addValue("modified", modified));
  }

  private void insertReportBatchInfo(int groupId, String batchId) {
    jdbcTemplate.update("insert into pharos.report_batch_info (rpt_grp_id, batch_id, seq_no, compiler_status) "
        + "values (:groupId, :batchId, 1, 'Report Generation Completed')",
        new MapSqlParameterSource().addValue("groupId", groupId).addValue("batchId", batchId));
  }

  @Test
  void findReportContextUsesTheJourneyDerivedFailureCountWhenItDisagreesWithTheRawReconciliationScalar() {
    insertReconciliation(8501, "RC-1", 100, 10, 5, 90, 85, 80, 7, 80, 75, 3, 1, 2, 1);
    // Four distinct identifiers actually failed transformation -- disagreeing with the raw
    // activity_transformation_failed scalar (7) above.
    insertJourney(8501, "RC-1", "F1", "TRANSFORMATION", "FAILED", LocalDateTime.now());
    insertJourney(8501, "RC-1", "F2", "TRANSFORMATION", "ERROR", LocalDateTime.now());
    insertJourney(8501, "RC-1", "F3", "TRANSFORMATION", "FAILURE", LocalDateTime.now());
    insertJourney(8501, "RC-1", "F4", "TRANSFORMATION", "FAILED", LocalDateTime.now());
    insertJourney(8501, "RC-1", "S1", "TRANSFORMATION", "SUCCESS", LocalDateTime.now());

    Optional<TransactionReportContextProjection> context = repository.findReportContext(8501, "RC-1", 1);

    assertTrue(context.isPresent());
    assertEquals(4, context.get().failed(), "the journey-derived count should win over the raw scalar");
    assertEquals(7, context.get().reportedFailed(), "the raw scalar should still be exposed alongside it");
    assertTrue(context.get().failedMismatch());
  }

  @Test
  void findReportContextFallsBackToTheRawScalarWhenJourneyHasNoCoverage() {
    insertReconciliation(8501, "RC-2", 50, 5, 2, 40, 38, 35, 9, 35, 30, 1, 0, 0, 0);
    // Deliberately no journey rows at all for RC-2.
    Optional<TransactionReportContextProjection> context = repository.findReportContext(8501, "RC-2", 1);

    assertTrue(context.isPresent());
    assertEquals(9, context.get().failed(), "with no journey coverage, the raw scalar is used as-is");
    assertFalse(context.get().failedMismatch());
  }

  @Test
  void findReportContextReturnsEmptyWhenNoMatchingBatchExists() {
    assertTrue(repository.findReportContext(8501, "NO-SUCH-BATCH", 1).isEmpty());
  }

  @Test
  void batchDetailUsesExactIdentityNotSubstringSearchForAWildcardLikeIdentifier() {
    // "12%_" contains SQL LIKE metacharacters; if the exact-match path ever degraded to substring
    // search, it would also match "1299X" below ('12' + anything + one more character).
    insertJourney(8502, "BD-BATCH", "12%_", "TRANSFORMATION", "SUCCESS", LocalDateTime.now());
    insertJourney(8502, "BD-BATCH", "1299X", "TRANSFORMATION", "SUCCESS", LocalDateTime.now());

    Optional<TransactionEvidenceProjection> detail =
        repository.findBatchEvidenceDetail(8502, "BD-BATCH", "12%_", "ALL", "ALL", "ALL", "ALL", "ALL", "JOURNEY:12%_");

    assertTrue(detail.isPresent());
    assertEquals("12%_", detail.get().identifier());
  }

  @Test
  void periodDetailKeepsTheBatchIdSearchFilterSeparateFromTheExactEvidenceBatchMatch() {
    // Both batches match the "BATCH-" substring search used to resolve batch_scope, and both carry
    // a journey row for the SAME identifier (reprocessed within the window) -- only PERIOD-B's row
    // is the most-recently-modified one, so the overview rollup's latestJourneyForTarget always
    // returns that one, regardless of which batch the search filter matched.
    insertReconciliation(8503, "PERIOD-A", 10, 0, 0, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0);
    insertReconciliation(8503, "PERIOD-B", 10, 0, 0, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0);
    insertJourney(8503, "PERIOD-A", "777", "FILTRATION", "EXCLUDED", LocalDateTime.now().minusDays(1));
    insertJourney(8503, "PERIOD-B", "777", "FILTRATION", "EXCLUDED", LocalDateTime.now());

    Optional<TransactionEvidenceProjection> latestBatchDetail = repository.findPeriodEvidenceDetail(LocalDateTime.now().minusDays(2),
        LocalDateTime.now().plusDays(1), false, List.of(), true, 8503, "PERIOD-B", "777", "ALL", "EXCLUDED", "", false, "PERIOD-", "");
    assertTrue(latestBatchDetail.isPresent(), "PERIOD-B's row is the most recently modified, so it is the one the rollup returns");
    assertEquals("PERIOD-B", latestBatchDetail.get().batchId());

    Optional<TransactionEvidenceProjection> staleBatchDetail = repository.findPeriodEvidenceDetail(LocalDateTime.now().minusDays(2),
        LocalDateTime.now().plusDays(1), false, List.of(), true, 8503, "PERIOD-A", "777", "ALL", "EXCLUDED", "", false, "PERIOD-", "");
    assertTrue(staleBatchDetail.isEmpty(),
        "the batchIdFilter substring search still matches PERIOD-A, but the exact evidenceBatchId must not -- the rollup never "
        + "surfaces PERIOD-A's superseded row");
  }

  @Test
  void periodEvidenceRoutesBatchScopedExcludedToADifferentDefinitionThanTheOverviewRollup() {
    insertReconciliation(8504, "SCOPED-BATCH", 10, 0, 0, 10, 10, 0, 0, 0, 0, 5, 0, 0, 0);
    insertReconciliation(8504, "SCOPED-BATCH-2", 10, 0, 0, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0);
    insertReportBatchInfo(8504, "SCOPED-BATCH");
    insertReportBatchInfo(8504, "SCOPED-BATCH-2");
    // Genuinely excluded (counts under both definitions).
    insertJourney(8504, "SCOPED-BATCH", "601", "FILTRATION", "EXCLUDED", "EXCLUDED_BECAUSE_EXCLUSION_EXISTS", LocalDateTime.now());
    // Excluded in SCOPED-BATCH, then reported in a different batch within the same scope (a journey
    // row is one per (group, batch, identifier), so reprocessing always lands in a different batch)
    // -- the overview "ever excluded and never reported" definition must drop this one, but the
    // batch-total definition (every in-scope JOURNEY row whose comment says
    // EXCLUDED_BECAUSE_EXCLUSION_EXISTS, full stop) never looks at reporting status at all, so it
    // counts both.
    insertJourney(8504, "SCOPED-BATCH", "602", "FILTRATION", "EXCLUDED", "EXCLUDED_BECAUSE_EXCLUSION_EXISTS",
        LocalDateTime.now().minusMinutes(2));
    insertJourney(8504, "SCOPED-BATCH-2", "602", "TRANSFORMATION", "SUCCESS", null, LocalDateTime.now().minusMinutes(1));

    long batchScopedCount = repository.countPeriodEvidenceRecords(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), false,
        List.of(), true, 8504, "", "", "ALL", "EXCLUDED", null, true);
    long overviewCount = repository.countPeriodEvidenceRecords(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), false,
        List.of(), true, 8504, "", "", "ALL", "EXCLUDED", null, false);

    assertEquals(2, batchScopedCount,
        "both journey rows carry the EXCLUDED_BECAUSE_EXCLUSION_EXISTS comment this batch-total "
        + "definition matches on, regardless of what happened to 602 afterwards");
    assertEquals(1, overviewCount,
        "602 was also reported, so the overview rollup's ever-excluded-and-never-reported definition " + "must drop it, leaving only 601");
  }
}
