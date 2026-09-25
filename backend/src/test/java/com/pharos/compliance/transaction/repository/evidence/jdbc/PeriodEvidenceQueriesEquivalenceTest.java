package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Execution-semantics coverage for {@link PeriodEvidenceQueries}. The seed spans <em>two report
 * groups and three batches</em> -- the case this class's own {@code batch_scope} CTE convention
 * and {@code carriesReportGroupId=true} enrichment scoping exist for -- with a deliberate per-batch
 * split on whether a report was actually generated (PA and PC generated, PB not), so the {@code
 * status=REPORTED} TRANSFORMATION/SUCCESS+batch_generated branch is proven both ways in the same
 * run, and a same-shaped {@code TRANSFORMATION/SUCCESS} row in the non-generated batch proves it
 * is correctly excluded rather than merely never tested.
 *
 * <p>Originally written in Phase 6e as an old-jOOQ-vs-new-hand-SQL equivalence test; the old jOOQ
 * version was deleted in Phase 6h once every consumer had migrated, so this now asserts directly
 * on the new pipeline's real execution result against seeded Postgres data.
 */
class PeriodEvidenceQueriesEquivalenceTest extends PostgresIntegrationTest {
  private static final int GROUP_1 = 8201;
  private static final int GROUP_2 = 8202;
  private static final String BATCH_PA = "PA";
  private static final String BATCH_PB = "PB";
  private static final String BATCH_PC = "PC";
  private static final LocalDateTime FROM = LocalDateTime.of(2024, 1, 1, 0, 0);
  private static final LocalDateTime TO_EXCLUSIVE = LocalDateTime.of(2024, 2, 1, 0, 0);
  private PeriodEvidenceQueries queries;

  @BeforeEach
  void setUp() {
    for (String table : List.of("rule_hit", "rule_hit_exclusion_audit", "record_transformation_journey", "report_batch_info")) {
      jdbcTemplate.getJdbcOperations().update("delete from pharos." + table + " where rpt_grp_id in (" + GROUP_1 + ", " + GROUP_2 + ")");
    }
    jdbcTemplate
      .getJdbcOperations()
      .update("delete from pharos.report_transformation_reconciliation where rpt_grp_id in (" + GROUP_1 + ", " + GROUP_2 + ")");
    jdbcTemplate.getJdbcOperations().update("delete from pharos.reg_reportable_activity where txn_sur_key in (920001, 920006)");

    SqlResourceLoader sqlLoader = new SqlResourceLoader(new DefaultResourceLoader());
    var ruleHitMatcher = new RuleHitMatcher();
    var paginator = new EvidencePaginator(tracingJdbcTemplate, sqlLoader);
    queries = new PeriodEvidenceQueries(tracingJdbcTemplate, sqlLoader, ruleHitMatcher, paginator);

    seedData();
  }

  private void seedData() {
    insertReconciliation(GROUP_1, BATCH_PA, "Group One", 3);
    insertReconciliation(GROUP_1, BATCH_PB, "Group One", 1);
    insertReconciliation(GROUP_2, BATCH_PC, "Group Two", 2);
    // PA (group 1) -- reported via TRANSFORMATION/SUCCESS, and only counts because PA's own batch
    // actually generated its report (see report_batch_info below).
    insertJourney(GROUP_1, BATCH_PA, "920001", "TRANSFORMATION", "SUCCESS", null, null);
    insertJourney(GROUP_1, BATCH_PA, "920002", "FILTRATION", "EXCLUDED", "EXCLUDED_BECAUSE_EXCLUSION_EXISTS", null);
    // PB (group 1) -- reported via the literal REPORT_GENERATION/GENERATED convention (counts
    // regardless of batch_generated), a missing attempt, and a TRANSFORMATION/SUCCESS row that must
    // NOT count as reported since PB's own batch never generated a report.
    insertJourney(GROUP_1, BATCH_PB, "920003", "REPORT_GENERATION", "GENERATED", null, null);
    insertJourney(GROUP_1, BATCH_PB, "920004", "TRANSFORMATION", "SUCCESS", null, null);
    insertJourney(GROUP_1, BATCH_PB, "920005", "TRANSACTION_JOIN", "ERROR", "ATTEMPT_NOT_RECEIVED", null);
    // PC (group 2) -- reported via TRANSFORMATION/SUCCESS with batch_generated resolved through
    // report_status (PARTIAL) rather than compiler_status this time, and its own exclusion.
    insertJourney(GROUP_2, BATCH_PC, "920006", "TRANSFORMATION", "SUCCESS", null, null);
    insertJourney(GROUP_2, BATCH_PC, "920007", "FILTRATION", "EXCLUDED", "EXCLUDED_BECAUSE_EXCLUSION_EXISTS", null);

    insertExclusionAudit(GROUP_1, BATCH_PA, 1, "P-EXCL-A");
    insertExclusionAudit(GROUP_2, BATCH_PC, 1, "P-EXCL-C");
    // Matches 920001 in group 1/batch PA.
    insertRuleHit(GROUP_1, BATCH_PA, 1, "P-R1", 920001L, null, true);
    // No match anywhere (exercises the no-match path).
    insertRuleHit(GROUP_1, BATCH_PB, 2, "P-R2", 999999L, "NO-MATCH-MTCN", false);
    // Matches 920006 in group 2/batch PC -- proves the enrichment's report-group scoping (a
    // different group than P-R1 above) is preserved end to end through the DETAIL rollup.
    insertRuleHit(GROUP_2, BATCH_PC, 3, "P-R3", 920006L, null, true);
    // PA generated its report via compiler_status; PC via report_status; PB never generated one.
    insertReportBatchInfo(GROUP_1, BATCH_PA, "Report Generation Completed", null);
    insertReportBatchInfo(GROUP_2, BATCH_PC, null, "PARTIAL");

    insertRra(920001, "Alice Sender", "Bob Receiver");
    insertRra(920006, "Carol Sender", "Dave Receiver");
  }

  private void insertReconciliation(int groupId, String batchId, String rptGrpName, int excludedTxn) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, rpt_grp_name, excluded_txn, "
        + "created_timestamp) values (:groupId, :batchId, 1, :rptGrpName, :excludedTxn, :created)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("rptGrpName", rptGrpName)
          .addValue("excludedTxn", excludedTxn)
          .addValue("created", FROM.plusDays(3)));
  }

  private void insertJourney(int groupId, String batchId, String identifier, String stage, String status, String comments, String mtcn) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, comments, "
        + "modified_timestamp, processing_complete) "
        + "values (:groupId, :batchId, :identifier, :mtcn, :stage, :status, :comments, now(), true)",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("mtcn", mtcn)
          .addValue("stage", stage)
          .addValue("status", status)
          .addValue("comments", comments));
  }

  private void insertExclusionAudit(int groupId, String batchId, long attemptId, String ruleId) {
    jdbcTemplate.update("insert into pharos.rule_hit_exclusion_audit (attempt_id, rpt_grp_id, rule_id, bucket_id, processing_batch_id, "
        + "exclusion_reason_id, modified_timestamp) values (:attemptId, :groupId, :ruleId, 1, :batchId, 'SOME_REASON', now())",
        new MapSqlParameterSource()
          .addValue("attemptId", attemptId)
          .addValue("groupId", groupId)
          .addValue("ruleId", ruleId)
          .addValue("batchId", batchId));
  }

  private void insertRuleHit(int groupId, String batchId, long attemptId, String ruleId, long externalTxnKey, String mtcn,
      boolean isReported) {
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, external_txn_key, mtcn, "
        + "is_reported, modified_timestamp) values (:groupId, 1, :ruleId, :attemptId, :batchId, :externalTxnKey, :mtcn, :isReported, now())",
        new MapSqlParameterSource()
          .addValue("groupId", groupId)
          .addValue("ruleId", ruleId)
          .addValue("attemptId", attemptId)
          .addValue("batchId", batchId)
          .addValue("externalTxnKey", externalTxnKey)
          .addValue("mtcn", mtcn)
          .addValue("isReported", isReported));
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

  private void insertRra(long txnSurKey, String senderName, String receiverName) {
    jdbcTemplate.update("insert into pharos.reg_reportable_activity (txn_sur_key, s_party_name, r_party_name, txn_status) "
        + "values (:txnSurKey, :senderName, :receiverName, 'COMPLETE')",
        new MapSqlParameterSource().addValue("txnSurKey", txnSurKey).addValue("senderName", senderName).addValue("receiverName",
            receiverName));
  }

  private SqlFragment scopeAcrossBothGroups() {
    return queries.batchScope(FROM, TO_EXCLUSIVE, true, List.of(GROUP_1, GROUP_2), false, 0, "");
  }

  @Test
  void findPeriodAggregateSumsExcludedTxnAndCountsDistinctBatchesAcrossBothGroups() {
    PeriodAggregateProjection aggregate = queries.findPeriodAggregate(FROM, TO_EXCLUSIVE, true, List.of(GROUP_1, GROUP_2), false, 0, "");

    assertEquals(3, aggregate.batchCount());
    assertEquals(6, aggregate.totalExcluded());
  }

  @Test
  void findPeriodAggregateScopesToASingleReportGroupWhenFiltered() {
    PeriodAggregateProjection aggregate = queries.findPeriodAggregate(FROM, TO_EXCLUSIVE, false, List.of(), true, GROUP_1, "");

    assertEquals(2, aggregate.batchCount());
  }

  @Test
  void findPeriodAggregateReturnsZeroBatchesForAnEmptyReportGroupIdsFilter() {
    PeriodAggregateProjection aggregate = queries.findPeriodAggregate(FROM, TO_EXCLUSIVE, true, List.of(), false, 0, "");

    assertEquals(0, aggregate.batchCount());
  }

  @Test
  void distinctBatchIdsReturnsEveryBatchInScopeOrdered() {
    List<String> ids = queries.distinctBatchIds(scopeAcrossBothGroups());

    assertEquals(List.of(BATCH_PA, BATCH_PB, BATCH_PC), ids);
  }

  @Test
  void countEvidenceRecordsMatchesTheExpectedDistinctTransactionCountPerStatus() {
    // ALL: 7 distinct journey-sourced (batch, identifier) pairs plus 2 exclusion_audit rows (PA and
    // PC, each keyed by attempt_id since no external_txn_key was set) that don't share a pair with
    // any journey row = 9. REPORTED: 920001 (PA, batch-generated), 920003 (PB, literal
    // REPORT_GENERATION/GENERATED), 920006 (PC, batch-generated) = 3; 920004 (PB, NOT generated)
    // must not count.
    assertEquals(9, queries.countEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "ALL"));
    assertEquals(3, queries.countEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "REPORTED"));
  }

  @Test
  void findEvidenceRecordsListProjectionReturnsEveryDistinctTransactionForAllStatus() {
    EvidencePage page = queries.findEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    assertEquals(9, page.records().size());
  }

  @Test
  void findEvidenceRecordsDetailProjectionScopesTheRuleHitRollupPerReportGroup() {
    EvidencePage page =
        queries.findEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "ALL", "DESC", 50, 0, null, EvidenceProjection.DETAIL);

    var txn920001 = page
      .records()
      .stream()
      .filter(r -> "920001".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals("Alice Sender", txn920001.senderName());
    assertTrue(txn920001.ruleHitsJson().contains("P-R1"), "group 1's rule hit should appear in group 1's transaction rollup");
    assertTrue(!txn920001.ruleHitsJson().contains("P-R3"), "group 2's rule hit must not leak into group 1's rollup");

    var txn920006 = page
      .records()
      .stream()
      .filter(r -> "920006".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals("Carol Sender", txn920006.senderName());
    assertTrue(txn920006.ruleHitsJson().contains("P-R3"), "group 2's rule hit should appear in group 2's transaction rollup");
    assertTrue(!txn920006.ruleHitsJson().contains("P-R1"), "group 1's rule hit must not leak into group 2's rollup");
  }

  @Test
  void statusReportedIncludesEveryGeneratedPathAndExcludesTheNonGeneratedBatch() {
    EvidencePage page =
        queries.findEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "REPORTED", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(3, identifiers.size(), "identifiers: " + identifiers);
    assertTrue(identifiers.contains("920001"), "PA's TRANSFORMATION/SUCCESS counts: its batch generated a report: " + identifiers);
    assertTrue(identifiers.contains("920003"), "PB's REPORT_GENERATION/GENERATED always counts: " + identifiers);
    assertTrue(identifiers.contains("920006"), "PC's TRANSFORMATION/SUCCESS counts via report_status=PARTIAL: " + identifiers);
    assertTrue(!identifiers.contains("920004"), "PB's TRANSFORMATION/SUCCESS must not count: PB never generated a report: " + identifiers);
  }

  @Test
  void cursorPaginationNeverRepeatsATransactionAcrossPages() {
    EvidencePage firstPage =
        queries.findEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "ALL", "DESC", 1, 0, null, EvidenceProjection.LIST);
    assertEquals(1, firstPage.records().size());
    assertTrue(firstPage.nextCursor() != null, "9 distinct transactions exist, so a 1-row page must have a next cursor");

    EvidenceCursor cursor = EvidenceCursor.decode(firstPage.nextCursor());
    EvidencePage secondPage =
        queries.findEvidenceRecords(scopeAcrossBothGroups(), "", "ALL", "ALL", "DESC", 1, 0, cursor, EvidenceProjection.LIST);
    assertEquals(1, secondPage.records().size());
    assertTrue(secondPage
          .records()
          .stream()
          .noneMatch(r -> r.identifier().equals(firstPage.records().get(0).identifier())),
        "the second page must not repeat the first page's transaction");
  }

  @Test
  void findExcludedEvidenceRecordsForBatchTotalMatchesBothExclusionCommentedRows() {
    EvidencePage page =
        queries.findExcludedEvidenceRecordsForBatchTotal(scopeAcrossBothGroups(), "", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(2, identifiers.size(), "identifiers: " + identifiers);
    assertTrue(identifiers.contains("920002"), "PA's EXCLUDED_BECAUSE_EXCLUSION_EXISTS row should count: " + identifiers);
    assertTrue(identifiers.contains("920007"), "PC's EXCLUDED_BECAUSE_EXCLUSION_EXISTS row should count: " + identifiers);
  }

  @Test
  void countExcludedEvidenceRecordsForBatchTotalCountsBothExclusionCommentedRows() {
    assertEquals(2, queries.countExcludedEvidenceRecordsForBatchTotal(scopeAcrossBothGroups(), ""));
  }
}
