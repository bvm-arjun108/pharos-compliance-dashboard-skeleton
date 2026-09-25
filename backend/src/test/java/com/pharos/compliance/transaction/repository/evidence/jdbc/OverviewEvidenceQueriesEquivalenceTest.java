package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Execution-semantics coverage for {@link OverviewEvidenceQueries}. The seed spans two report
 * groups and two batches within the first group specifically to exercise this class's two defining
 * behaviors: (1) a transaction excluded and later reported within the window must drop out of
 * <em>both</em> buckets (the {@code ever_excluded and ever_reported} precedence), and (2) a
 * transaction reprocessed across two different batches in the same window must surface exactly
 * once, showing its single most-recently-modified journey row -- the bug {@link
 * OverviewEvidenceQueries#latestJourneyForTarget} exists to fix, which the per-batch evidence
 * pipeline (used by every other status) does not have.
 *
 * <p>Originally written in Phase 6f as an old-jOOQ-vs-new-hand-SQL equivalence test; the old jOOQ
 * version was deleted in Phase 6h once every consumer had migrated, so this now asserts directly
 * on the new pipeline's real execution result against seeded Postgres data.
 */
class OverviewEvidenceQueriesEquivalenceTest extends PostgresIntegrationTest {
  private static final int GROUP_1 = 8401;
  private static final int GROUP_2 = 8402;
  private static final String BATCH_OA = "OA";
  private static final String BATCH_OB = "OB";
  private static final String BATCH_OC = "OC";
  private static final String BATCH_OD = "OD";
  private static final LocalDateTime FROM = LocalDateTime.of(2024, 3, 1, 0, 0);
  private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2024, 4, 1, 0, 0);
  private PeriodEvidenceQueries periodQueries;
  private OverviewEvidenceQueries queries;

  @BeforeEach
  void setUp() {
    for (String table : List.of("rule_hit", "rule_hit_exclusion_audit", "record_transformation_journey", "report_batch_info")) {
      jdbcTemplate.getJdbcOperations().update("delete from pharos." + table + " where rpt_grp_id in (" + GROUP_1 + ", " + GROUP_2 + ")");
    }
    jdbcTemplate
      .getJdbcOperations()
      .update("delete from pharos.report_transformation_reconciliation where rpt_grp_id in (" + GROUP_1 + ", " + GROUP_2 + ")");
    jdbcTemplate.getJdbcOperations().update("delete from pharos.reg_reportable_activity where txn_sur_key in (930004, 930008)");

    SqlResourceLoader sqlLoader = new SqlResourceLoader(new DefaultResourceLoader());
    var ruleHitMatcher = new RuleHitMatcher();
    var paginator = new EvidencePaginator(tracingJdbcTemplate, sqlLoader);
    periodQueries = new PeriodEvidenceQueries(tracingJdbcTemplate, sqlLoader, ruleHitMatcher, paginator);
    queries = new OverviewEvidenceQueries(tracingJdbcTemplate, sqlLoader, paginator, periodQueries);

    seedData();
  }

  private void seedData() {
    insertReconciliation(GROUP_1, BATCH_OA, "Group One");
    insertReconciliation(GROUP_1, BATCH_OB, "Group One");
    insertReconciliation(GROUP_1, BATCH_OD, "Group One");
    insertReconciliation(GROUP_2, BATCH_OC, "Group Two");
    // Pure excluded -- never reported.
    insertJourney(GROUP_1, BATCH_OA, "930001", "FILTRATION", "EXCLUDED", "REASON_A", FROM.plusDays(1));
    // Excluded in OA, then reported in a different batch (OD) within the same window -- a journey
    // row is one per (group, batch, identifier), so reprocessing always shows up as a second row in
    // a different batch, never a second row in the same one. Must drop out of BOTH buckets: this is
    // the ever_excluded-and-ever_reported precedence the roll-up exists to get right.
    insertJourney(GROUP_1, BATCH_OA, "930002", "FILTRATION", "EXCLUDED", "REASON_B", FROM.plusDays(1));
    insertJourney(GROUP_1, BATCH_OD, "930002", "TRANSFORMATION", "SUCCESS", null, FROM.plusDays(2));
    // Not reported, single batch.
    insertJourney(GROUP_1, BATCH_OB, "930003", "TRANSACTION_JOIN", "ERROR", "ATTEMPT_NOT_RECEIVED", FROM.plusDays(1));
    // Reprocessed across OA then OB (same group, same window) -- must surface exactly once, with
    // OB's row (the later modified_timestamp) as its content.
    insertJourney(GROUP_1, BATCH_OA, "930004", "TRANSACTION_JOIN", "ERROR", "FIRST_ATTEMPT", FROM.plusDays(1));
    insertJourney(GROUP_1, BATCH_OB, "930004", "SELECTION", "ATTEMPT_MISSING", "SECOND_ATTEMPT", FROM.plusDays(3));
    // More distinct not-reported reasons, to force a real top-3/"Other" split.
    insertJourney(GROUP_1, BATCH_OB, "930005", "TRANSACTION_JOIN", "ERROR", "REASON_X", FROM.plusDays(1));
    insertJourney(GROUP_1, BATCH_OB, "930006", "TRANSACTION_JOIN", "ERROR", "REASON_Y", FROM.plusDays(1));
    insertJourney(GROUP_1, BATCH_OB, "930007", "TRANSACTION_JOIN", "ERROR", "REASON_Z", FROM.plusDays(1));
    // Different report group -- proves the roll-up/enrichment don't leak across groups.
    insertJourney(GROUP_2, BATCH_OC, "930008", "TRANSACTION_JOIN", "ERROR", "OC_REASON", FROM.plusDays(1));
    // OA, OD and OC generated their reports; OB never did (needed so 930002's own batch, OD,
    // actually generated a report -- otherwise its TRANSFORMATION/SUCCESS row would not count as
    // "ever reported" and the test would prove nothing).
    insertReportBatchInfo(GROUP_1, BATCH_OA, "Report Generation Completed", null);
    insertReportBatchInfo(GROUP_1, BATCH_OD, "Report Generation Completed", null);
    insertReportBatchInfo(GROUP_2, BATCH_OC, null, "PARTIAL");

    insertRuleHit(GROUP_1, BATCH_OB, 1, "O-R1", 930004L, true);
    insertRuleHit(GROUP_2, BATCH_OC, 2, "O-R2", 930008L, false);

    insertRra(930004, "Eve Sender", "Frank Receiver");
    insertRra(930008, "Grace Sender", "Heidi Receiver");
  }

  private void insertReconciliation(int groupId, String batchId, String rptGrpName) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, rpt_grp_name, "
        + "created_timestamp) values (:groupId, :batchId, 1, :rptGrpName, :created)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("rptGrpName", rptGrpName)
          .addValue("created", FROM.plusDays(1)));
  }

  private void insertJourney(int groupId, String batchId, String identifier, String stage, String status, String comments,
      LocalDateTime modifiedTimestamp) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, comments, "
        + "modified_timestamp, processing_complete) values (:groupId, :batchId, :identifier, :mtcn, :stage, :status, :comments, :modified, true)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("mtcn", (String) null)
          .addValue("stage", stage)
          .addValue("status", status)
          .addValue("comments", comments)
          .addValue("modified", modifiedTimestamp));
  }

  private void insertReportBatchInfo(int groupId, String batchId, String compilerStatus, String reportStatus) {
    jdbcTemplate.update("insert into pharos.report_batch_info (rpt_grp_id, batch_id, seq_no, compiler_status, report_status) "
        + "values (:groupId, :batchId, 1, :compilerStatus, :reportStatus)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("compilerStatus", compilerStatus)
          .addValue("reportStatus", reportStatus));
  }

  private void insertRuleHit(int groupId, String batchId, long attemptId, String ruleId, long externalTxnKey, boolean isReported) {
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, external_txn_key, "
        + "is_reported, modified_timestamp) values (:groupId, 1, :ruleId, :attemptId, :batchId, :externalTxnKey, :isReported, now())",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("ruleId", ruleId)
          .addValue("attemptId", attemptId)
          .addValue("batchId", batchId)
          .addValue("externalTxnKey", externalTxnKey)
          .addValue("isReported", isReported));
  }

  private void insertRra(long txnSurKey, String senderName, String receiverName) {
    jdbcTemplate.update("insert into pharos.reg_reportable_activity (txn_sur_key, s_party_name, r_party_name, txn_status) "
        + "values (:txnSurKey, :senderName, :receiverName, 'COMPLETE')",
        new MapSqlParameterSource().addValue("txnSurKey", txnSurKey).addValue("senderName", senderName).addValue("receiverName",
            receiverName));
  }

  private SqlFragment scope() {
    return periodQueries.batchScope(FROM, TO_EXCLUSIVE, true, List.of(GROUP_1, GROUP_2), false, 0, "");
  }

  @Test
  void excludedBucketDropsTheAlsoReportedTransaction() {
    EvidencePage page =
        queries.findOverviewEvidenceRecords(scope(), "EXCLUDED", null, "", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(List.of("930001"), identifiers, "930002 was also reported, so only 930001 remains purely excluded");
  }

  @Test
  void excludedBucketWithExactReasonMatchesOnlyThatReason() {
    EvidencePage page =
        queries.findOverviewEvidenceRecords(scope(), "EXCLUDED", "REASON_A", "", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    assertEquals(List.of("930001"), page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList());
  }

  @Test
  void notReportedBucketDedupsTheReprocessedTransactionToItsLatestRow() {
    EvidencePage page =
        queries.findOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(6, identifiers.size(), "identifiers: " + identifiers);
    assertEquals(1, identifiers.stream().filter("930004"::equals).count(), "930004 must surface exactly once despite two batches");
    assertFalse(identifiers.contains("930002"), "930002 was also reported, so it must not appear as not-reported either: " + identifiers);

    var txn930004 = page
      .records()
      .stream()
      .filter(r -> "930004".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals(BATCH_OB, txn930004.batchId(), "930004 must show its most-recently-modified row (from OB), not OA's earlier one");
    assertEquals("SELECTION", txn930004.stage());
  }

  @Test
  void notReportedBucketOtherReasonMatchesEveryReasonOutsideTheTopThree() {
    // Six distinct not-reported reasons, each occurring once: alphabetically, the top 3 (tied on
    // count, broken by reason ascending) are ATTEMPT_NOT_RECEIVED (930003), OC_REASON (930008) and
    // REASON_X (930005) -- "Other" is everything else: REASON_Y (930006), REASON_Z (930007) and
    // SECOND_ATTEMPT (930004).
    EvidencePage page =
        queries.findOverviewEvidenceRecords(scope(), "NOT_REPORTED", "Other", "", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(3, identifiers.size(), "identifiers: " + identifiers);
    assertTrue(identifiers.containsAll(List.of("930004", "930006", "930007")), "identifiers: " + identifiers);
  }

  @Test
  void detailProjectionScopesTheRuleHitRollupPerReportGroup() {
    EvidencePage page =
        queries.findOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "", "ALL", "DESC", 50, 0, null, EvidenceProjection.DETAIL);

    var txn930004 = page
      .records()
      .stream()
      .filter(r -> "930004".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals("Eve Sender", txn930004.senderName());
    assertTrue(txn930004.ruleHitsJson().contains("O-R1"), "group 1's rule hit should appear in group 1's rollup");
    assertFalse(txn930004.ruleHitsJson().contains("O-R2"), "group 2's rule hit must not leak into group 1's rollup");

    var txn930008 = page
      .records()
      .stream()
      .filter(r -> "930008".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals("Grace Sender", txn930008.senderName());
    assertTrue(txn930008.ruleHitsJson().contains("O-R2"), "group 2's rule hit should appear in group 2's rollup");
    assertFalse(txn930008.ruleHitsJson().contains("O-R1"), "group 1's rule hit must not leak into group 2's rollup");
  }

  @Test
  void cursorPaginationNeverRepeatsATransactionAcrossPages() {
    EvidencePage firstPage =
        queries.findOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "", "ALL", "DESC", 1, 0, null, EvidenceProjection.LIST);
    assertEquals(1, firstPage.records().size());
    assertTrue(firstPage.nextCursor() != null, "6 not-reported transactions exist, so a 1-row page must have a next cursor");

    EvidenceCursor cursor = EvidenceCursor.decode(firstPage.nextCursor());
    EvidencePage secondPage =
        queries.findOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "", "ALL", "DESC", 1, 0, cursor, EvidenceProjection.LIST);
    assertEquals(1, secondPage.records().size());
    assertTrue(secondPage
          .records()
          .stream()
          .noneMatch(r -> r.identifier().equals(firstPage.records().get(0).identifier())),
        "the second page must not repeat the first page's transaction");
  }

  @Test
  void countOverviewEvidenceRecordsAgreesBetweenTheShortcutAndTheFullPath() {
    // search empty + outcome ALL takes the count(*)-on-target shortcut; a non-empty search forces
    // the full latestJourneyForTarget + filteredEvidenceForPeriod path. Every seeded not-reported
    // identifier starts with "9300", so both should produce the same count here.
    long shortcutCount = queries.countOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "", "ALL");
    long searchCount = queries.countOverviewEvidenceRecords(scope(), "NOT_REPORTED", null, "9300", "ALL");

    assertEquals(6, shortcutCount);
    assertEquals(shortcutCount, searchCount, "both code paths must agree on the same underlying target set");
  }
}
