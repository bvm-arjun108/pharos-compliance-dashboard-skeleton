package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Execution-semantics coverage for {@link BatchEvidenceQueries}: the three evidence sources, every
 * {@code metric} branch, the {@code status=REPORTED} batch-generated condition, the DETAIL
 * projection's RRA join and rule-hit rollup, and keyset cursor pagination across pages. Originally
 * written in Phase 6c as an old-jOOQ-vs-new-hand-SQL equivalence test; the old jOOQ version was
 * deleted in Phase 6h once every consumer had migrated, so this now asserts directly on the new
 * pipeline's real execution result against seeded Postgres data.
 */
class BatchEvidenceQueriesEquivalenceTest extends PostgresIntegrationTest {
  private static final int GROUP = 8101;
  private static final String BATCH = "BATCH-A";
  private BatchEvidenceQueries queries;

  @BeforeEach
  void setUp() {
    for (String table : List.of("rule_hit", "rule_hit_exclusion_audit", "record_transformation_journey", "report_batch_info")) {
      jdbcTemplate.getJdbcOperations().update("delete from pharos." + table + " where rpt_grp_id = " + GROUP);
    }
    // reg_reportable_activity has no rpt_grp_id column; clean it by its own surrogate key instead.
    jdbcTemplate.getJdbcOperations().update("delete from pharos.reg_reportable_activity where txn_sur_key = 910001");

    SqlResourceLoader sqlLoader = new SqlResourceLoader(new DefaultResourceLoader());
    var ruleHitMatcher = new RuleHitMatcher();
    var paginator = new EvidencePaginator(tracingJdbcTemplate, sqlLoader);
    queries = new BatchEvidenceQueries(sqlLoader, ruleHitMatcher, paginator);

    seedData();
  }

  private void seedData() {
    // Reported successfully -- also carries a matching rule_hit row for the rollup.
    insertJourney("910001", "TRANSFORMATION", "SUCCESS", null, null);
    // Genuinely excluded (the VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS convention).
    insertJourney("910002", "FILTRATION", "EXCLUDED", "EXCLUDED_BECAUSE_EXCLUSION_EXISTS", null);
    // Missing attempt (the TRANSACTION_JOIN/ERROR convention).
    insertJourney("910003", "TRANSACTION_JOIN", "ERROR", "ATTEMPT_NOT_RECEIVED", null);
    // Reported via REPORT_GENERATION/GENERATED instead of TRANSFORMATION/SUCCESS.
    insertJourney("910004", "REPORT_GENERATION", "GENERATED", null, null);

    insertExclusionAudit(1, "R-EXCL", "SOME_REASON");
    // Matches 910001 by identifier -- reported.
    insertRuleHit(1, "R-1", 910001L, null, true);
    // Matches nothing (no journey row shares this mtcn/identifier) -- exercises the no-match path.
    insertRuleHit(2, "R-2", 999999L, "NO-MATCH-MTCN", false);

    insertReportBatchInfo();
    insertRra();
  }

  private void insertJourney(String identifier, String stage, String status, String comments, String mtcn) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, comments, "
        + "modified_timestamp, processing_complete) "
        + "values (:groupId, :batchId, :identifier, :mtcn, :stage, :status, :comments, now(), true)",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("batchId", BATCH)
          .addValue("identifier", identifier)
          .addValue("mtcn", mtcn)
          .addValue("stage", stage)
          .addValue("status", status)
          .addValue("comments", comments));
  }

  private void insertExclusionAudit(long attemptId, String ruleId, String reasonId) {
    jdbcTemplate.update("insert into pharos.rule_hit_exclusion_audit (attempt_id, rpt_grp_id, rule_id, bucket_id, processing_batch_id, "
        + "exclusion_reason_id, modified_timestamp) values (:attemptId, :groupId, :ruleId, 1, :batchId, :reasonId, now())",
        new MapSqlParameterSource()
          .addValue("attemptId", attemptId)
          .addValue("groupId", GROUP)
          .addValue("ruleId", ruleId)
          .addValue("batchId", BATCH)
          .addValue("reasonId", reasonId));
  }

  private void insertRuleHit(long attemptId, String ruleId, long externalTxnKey, String mtcn, boolean isReported) {
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, external_txn_key, mtcn, is_reported, "
        + "modified_timestamp) values (:groupId, 1, :ruleId, :attemptId, :batchId, :externalTxnKey, :mtcn, :isReported, now())",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("ruleId", ruleId)
          .addValue("attemptId", attemptId)
          .addValue("batchId", BATCH)
          .addValue("externalTxnKey", externalTxnKey)
          .addValue("mtcn", mtcn)
          .addValue("isReported", isReported));
  }

  private void insertReportBatchInfo() {
    jdbcTemplate.update("insert into pharos.report_batch_info (rpt_grp_id, batch_id, seq_no, compiler_status) "
        + "values (:groupId, :batchId, 1, 'Report Generation Completed')",
        new MapSqlParameterSource().addValue("groupId", GROUP).addValue("batchId", BATCH));
  }

  private void insertRra() {
    jdbcTemplate.update("insert into pharos.reg_reportable_activity (txn_sur_key, s_party_name, r_party_name, txn_status) "
        + "values (910001, 'Alice Sender', 'Bob Receiver', 'COMPLETE')", new MapSqlParameterSource());
  }

  @Test
  void countEvidenceRecordsMatchesEachMetricsOwnDefinition() {
    // Computed directly from the seed above against each metric's own condition (see
    // BatchEvidenceQueries#metricCondition): ALL sees every distinct (batch, identifier) pair
    // across all three sources (5: 910001-910004 plus the exclusion_audit row keyed by attempt_id
    // "1"); SELECTED is JOURNEY-only (4); EXCLUDED is 910002 alone; MISSING/SKIPPED are 910003
    // alone; FILTERED is 910002+910003; TRANSFORMED is 910001+910004 (TRANSFORMATION/SUCCESS and
    // REPORT_GENERATION/GENERATED both count).
    Map<String, Long> expectedByMetric =
        Map.of("ALL", 5L, "SELECTED", 4L, "EXCLUDED", 1L, "MISSING", 1L, "FILTERED", 2L, "SKIPPED", 1L, "TRANSFORMED", 2L);
    for (var entry : expectedByMetric.entrySet()) {
      long count = queries.countEvidenceRecords(GROUP, BATCH, entry.getKey(), "", "ALL", "ALL", "ALL", "ALL");
      assertEquals(entry.getValue(), count, "metric=" + entry.getKey());
    }
  }

  @Test
  void findEvidenceRecordsListProjectionReturnsOneRowPerDistinctTransaction() {
    EvidencePage page =
        queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);
    // 5 distinct (batch, identifier) pairs -- see countEvidenceRecordsMatchesEachMetricsOwnDefinition's
    // ALL case -- merged into exactly one row each, the 910001/rule_hit row folded into 910001's
    // JOURNEY row rather than appearing twice.
    assertEquals(5, page.records().size());
    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertTrue(identifiers.containsAll(List.of("910001", "910002", "910003", "910004", "1")), "identifiers: " + identifiers);
  }

  @Test
  void findEvidenceRecordsDetailProjectionIncludesTheRraJoinAndRuleHitRollup() {
    EvidencePage page =
        queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "ALL", "DESC", 50, 0, null, EvidenceProjection.DETAIL);

    var transaction910001 = page
      .records()
      .stream()
      .filter(r -> "910001".equals(r.identifier()))
      .findFirst()
      .orElseThrow();
    assertEquals("Alice Sender", transaction910001.senderName(), "RRA join should have resolved the sender name");
    assertTrue(transaction910001.ruleHitsJson().contains("R-1"), "the matching rule_hit row should appear in the rollup");
  }

  @Test
  void statusReportedMatchesReportGenerationGeneratedAndBatchGeneratedTransformationSuccess() {
    EvidencePage page =
        queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "REPORTED", "DESC", 50, 0, null, EvidenceProjection.LIST);

    List<String> identifiers = page
      .records()
      .stream()
      .map(r -> r.identifier())
      .toList();
    assertEquals(2, identifiers.size(), "identifiers: " + identifiers);
    assertTrue(identifiers.contains("910001"), "TRANSFORMATION/SUCCESS with a batch-generated report counts as reported: " + identifiers);
    assertTrue(identifiers.contains("910004"), "REPORT_GENERATION/GENERATED counts as reported: " + identifiers);
  }

  @Test
  void cursorPaginationNeverRepeatsATransactionAcrossPages() {
    EvidencePage firstPage =
        queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "ALL", "DESC", 1, 0, null, EvidenceProjection.LIST);
    assertEquals(1, firstPage.records().size());
    assertTrue(firstPage.nextCursor() != null, "5 distinct transactions exist, so a 1-row page must have a next cursor");

    var cursor = com.pharos.compliance.transaction.model.EvidenceCursor.decode(firstPage.nextCursor());
    EvidencePage secondPage =
        queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "ALL", "DESC", 1, 0, cursor, EvidenceProjection.LIST);
    assertEquals(1, secondPage.records().size());
    assertTrue(secondPage
          .records()
          .stream()
          .noneMatch(r -> r.identifier().equals(firstPage.records().get(0).identifier())),
        "the second page must not repeat the first page's transaction");
  }

  @Test
  void exactDetailLookupReturnsOnlyTheRequestedRow() {
    EvidenceProjection exact = EvidenceProjection.detail(BATCH, "910001", null);
    EvidencePage page = queries.findEvidenceRecords(GROUP, BATCH, "ALL", "", "ALL", "ALL", "ALL", "ALL", "DESC", 1, 0, null, exact);

    assertEquals(1, page.records().size());
    assertEquals("910001", page.records().get(0).identifier());
  }
}
