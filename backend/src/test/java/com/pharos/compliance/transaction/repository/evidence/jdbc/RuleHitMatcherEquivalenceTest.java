package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Execution-semantics coverage for {@link RuleHitMatcher}: the identifier-match path, the
 * mtcn-fallback path, the no-match path, and (for {@link RuleHitMatcher#scopedRuleHitMatches})
 * the exact-per-batch scoping that is this class's entire reason for existing over a simpler
 * unscoped join. Originally written in Phase 6b as an old-jOOQ-vs-new-hand-SQL equivalence test;
 * the old jOOQ version was deleted in Phase 6h once every consumer had migrated, so this now
 * asserts directly on the new matcher's real execution result against seeded Postgres data.
 */
class RuleHitMatcherEquivalenceTest extends PostgresIntegrationTest {
  private static final int GROUP = 7001;
  private final RuleHitMatcher newMatcher = new RuleHitMatcher();

  @BeforeEach
  void setUp() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + GROUP);

    insertJourney("B1", "800001", "MTCN-1");
    insertJourney("B2", "800001", "MTCN-2");
    insertJourney("B1", "800099", "MTCN-99");

    insertRuleHit("B1", 1, 800001L, null, "R1");
    insertRuleHit("B2", 2, 800001L, null, "R2");
    insertRuleHit("B1", 3, null, "MTCN-99", "R3");
    insertRuleHit("B1", 4, 999999L, "NO-MATCH", "R4");
  }

  private void insertJourney(String batchId, String identifier, String mtcn) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn) values (:groupId, :batchId, :identifier, "
        + ":mtcn)",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("mtcn", mtcn));
  }

  private void insertRuleHit(String batchId, long attemptId, Object externalTxnKey, String mtcn, String ruleId) {
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, external_txn_key, mtcn) "
        + "values (:groupId, 1, :ruleId, :attemptId, :batchId, :externalTxnKey, :mtcn)",
        new MapSqlParameterSource()
          .addValue("groupId", GROUP)
          .addValue("ruleId", ruleId)
          .addValue("attemptId", attemptId)
          .addValue("batchId", batchId)
          .addValue("externalTxnKey", externalTxnKey)
          .addValue("mtcn", mtcn));
  }

  /**
   * New hand-SQL journeyScoped fragment, restricted to one batch -- what BatchEvidenceQueries builds.
   */
  private SqlFragment newJourneyScopedForBatch(String batchId) {
    String sql =
        "select rpt_grp_id, batch_id, identifier, mtcn,\n"
        + "  case when identifier ~ '^[0-9]+$' then identifier::bigint else null end as identifier_bigint\n"
        + "from pharos.record_transformation_journey\n" + "where rpt_grp_id = :groupId and batch_id = :batchId";
    return SqlFragment.of(sql, Map.of("groupId", GROUP, "batchId", batchId));
  }

  /**
   * New hand-SQL journeyScoped fragment spanning every batch in the report group -- what PeriodEvidenceQueries builds.
   */
  private SqlFragment newJourneyScopedForGroup() {
    String sql =
        "select rpt_grp_id, batch_id, identifier, mtcn,\n"
        + "  case when identifier ~ '^[0-9]+$' then identifier::bigint else null end as identifier_bigint\n"
        + "from pharos.record_transformation_journey\n" + "where rpt_grp_id = :groupId";
    return SqlFragment.of(sql, Map.of("groupId", GROUP));
  }

  private String newMatchedIdentifierForBatch(String batchId) {
    SqlFragment scope =
        SqlFragment.of("rh.rpt_grp_id = :groupId and rh.efile_batch_id = :batchId", Map.of("groupId", GROUP, "batchId", batchId));
    SqlFragment result = newMatcher.ruleHitMatches(scope, newJourneyScopedForBatch(batchId));
    List<String> matches = jdbcTemplate.query("select matched_identifier from (" + result.sql() + ") m order by attempt_id", result.parameterSource(), (
                                                                                                                                                           rs,
                                                                                                                                                           rowNum
                                                                                                                                                       ) -> rs.getString(
        "matched_identifier"));
    return String.join(",", matches);
  }

  @Test
  void matchesByIdentifierMtcnFallbackAndNoMatch() {
    SqlFragment scope =
        SqlFragment.of("rh.rpt_grp_id = :groupId and rh.efile_batch_id = :batchId", Map.of("groupId", GROUP, "batchId", "B1"));
    SqlFragment result = newMatcher.ruleHitMatches(scope, newJourneyScopedForBatch("B1"));
    List<String> matches = jdbcTemplate.query("select matched_identifier from (" + result.sql() + ") m order by attempt_id", result.parameterSource(), (
                                                                                                                                                           rs,
                                                                                                                                                           rowNum
                                                                                                                                                       ) -> rs.getString(
        "matched_identifier"));
    // R1 matches by identifier (800001), R3 falls back to mtcn (800099), R4 matches nothing.
    assertEquals(java.util.Arrays.asList("800001", "800099", null), matches);
  }

  @Test
  void ruleHitMatchesResolvesB2IndependentlyOfB1sJourneyRow() {
    // B2's own journey row for identifier 800001 is a DIFFERENT row than B1's -- ruleHitMatches
    // must resolve R2 (attempt=2, in B2) against B2's own journeyScoped fragment, not B1's.
    assertEquals("800001", newMatchedIdentifierForBatch("B2"));
  }

  @Test
  void scopedRuleHitMatchesRestrictsEachRuleHitToItsOwnBatchsJourneyRow() {
    // Both B1's R1 and B2's R2 target external_txn_key=800001, but two DIFFERENT journey rows (one
    // per batch) carry identifier=800001 -- scopedRuleHitMatches must resolve each rule_hit row
    // against its own batch's journey row, not whichever one happens to match report-group-wide.
    SqlFragment ruleHitScope = SqlFragment.of("rh.rpt_grp_id = :groupId and rh.efile_batch_id in ('B1', 'B2')", Map.of("groupId", GROUP));
    SqlFragment newResult = newMatcher.scopedRuleHitMatches(ruleHitScope, newJourneyScopedForGroup());
    List<String> newMatches = jdbcTemplate.query("select attempt_id, matched_identifier from (" + newResult.sql()
        + ") m where attempt_id in (1, 2) order by attempt_id", newResult.parameterSource(), (rs, rowNum) -> rs.getLong("attempt_id") + "="
        + rs.getString("matched_identifier"));

    assertEquals(List.of("1=800001", "2=800001"), newMatches, "both resolve to identifier 800001, each from its own batch's journey row");
  }
}
