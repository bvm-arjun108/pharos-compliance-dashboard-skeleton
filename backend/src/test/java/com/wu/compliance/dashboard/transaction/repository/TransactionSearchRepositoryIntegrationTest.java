package com.wu.compliance.dashboard.transaction.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlResourceLoader;
import com.wu.compliance.dashboard.testsupport.PostgresIntegrationTest;
import com.wu.compliance.dashboard.transaction.model.TransactionSearchField;
import com.wu.compliance.dashboard.transaction.repository.projection.TransactionSearchResultProjection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Executes the Phase 5 JDBC migration of {@link TransactionSearchRepository} against a real
 * PostgreSQL instance, including the correlated-scalar-subquery report-group name/country lookup
 * (deliberately preserved, not rewritten to a join or a shared ranking CTE -- see the class
 * Javadoc).
 */
class TransactionSearchRepositoryIntegrationTest extends PostgresIntegrationTest {
  private static final int GROUP = 6001;
  private static final int OTHER_GROUP = 6002;
  private TransactionSearchRepository repository;

  @BeforeEach
  void setUp() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_group_config where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit_exclusion_audit where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + OTHER_GROUP);
    jdbcTemplate
      .getJdbcOperations()
      .update("delete from pharos.report_transformation_reconciliation where rpt_grp_id in (" + GROUP + ", " + OTHER_GROUP + ")");
    jdbcTemplate.getJdbcOperations().update("delete from pharos.reg_reportable_activity where txn_sur_key between 777000 and 777999");
    repository = new TransactionSearchRepository(tracingJdbcTemplate, new SqlResourceLoader(new DefaultResourceLoader()));

    jdbcTemplate.update("insert into pharos.report_group_config (rpt_grp_id, rpt_selection_version_id, transformer_version_id, rpt_grp_name, country_code, "
        + "country_name, modified_timestamp) values (:groupId, 1, '1.0', 'Config Group Name', 'FR', 'France', now())",
        new MapSqlParameterSource("groupId", GROUP));

    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, comments, "
        + "modified_timestamp) values (:groupId, 'BATCH-J', '555001', 'MTCN-J-1', 'TRANSFORMATION', 'SUCCESS', 'ok', "
        + "'2026-01-01T00:00:00Z')", new MapSqlParameterSource("groupId", GROUP));

    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, mtcn, external_txn_key, is_reported, "
        + "modified_timestamp) values (:groupId, 1, 'RULE-9', 1, 'BATCH-R', 'MTCN-R-1', 555002, true, '2026-01-02T00:00:00Z')",
        new MapSqlParameterSource("groupId", GROUP));

    jdbcTemplate.update("insert into pharos.rule_hit_exclusion_audit (attempt_id, rpt_grp_id, rule_id, bucket_id, rpt_grp_name, processing_batch_id, mtcn, "
        + "external_txn_key, exclusion_reason_id, modified_timestamp) "
        + "values (2, :groupId, 'RULE-1', 1, 'Exclusion Group Name', 'BATCH-E', 'MTCN-E-1', 555003, 'REASON-1', "
        + "'2026-01-03T00:00:00Z')", new MapSqlParameterSource("groupId", GROUP));
  }

  @Test
  void searchByMtcnFindsJourneyRowAndFallsBackToConfigForReportGroupNameAndCountry() {
    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.MTCN, "MTCN-J-1");

    assertEquals(1, results.size());
    TransactionSearchResultProjection result = results.get(0);
    assertEquals("JOURNEY", result.evidenceSource());
    assertEquals(GROUP, result.reportGroupId());
    assertEquals("Config Group Name", result.reportGroupName(), "journey carries no report-group name, so it falls back");
    assertEquals("FR", result.countryCode());
    assertEquals("France", result.countryName());
    assertEquals("mtcn", result.matchedOn());
  }

  @Test
  void searchByMtcnFindsRuleHitRowAndDerivesReportedStatusFromIsReported() {
    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.MTCN, "MTCN-R-1");

    assertEquals(1, results.size());
    TransactionSearchResultProjection result = results.get(0);
    assertEquals("RULE_HIT", result.evidenceSource());
    assertEquals("REPORTED", result.status());
    assertEquals("Config Group Name", result.reportGroupName(), "rule_hit's own rpt_grp_name is null here, so it falls back too");
  }

  @Test
  void searchByMtcnFindsExclusionAuditRowUsingItsOwnReportGroupNameWithoutFallback() {
    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.MTCN, "MTCN-E-1");

    assertEquals(1, results.size());
    TransactionSearchResultProjection result = results.get(0);
    assertEquals("EXCLUSION_AUDIT", result.evidenceSource());
    assertEquals("EXCLUDED", result.status());
    assertEquals("Exclusion Group Name", result.reportGroupName(), "exclusion_audit carries its own name and should not fall back");
    assertEquals("REASON-1", result.comments());
  }

  @Test
  void searchByMtcnReturnsEmptyForAnUnknownMtcn() {
    assertTrue(repository.search(TransactionSearchField.MTCN, "no-such-mtcn").isEmpty());
  }

  @Test
  void searchByExternalTxnIdMatchesJourneyIdentifierRuleHitAndExclusionAuditKey() {
    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555001").size(), "matches journey's identifier column");
    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555002").size(), "matches rule_hit's external_txn_key");
    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555003").size(), "matches exclusion_audit's external_txn_key");
  }

  @Test
  void searchByExternalTxnIdReturnsEmptyWithoutQueryingWhenNotANumber() {
    assertTrue(repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "not-a-number").isEmpty());
  }

  @Test
  void searchOrdersResultsByOccurredAtDescending() {
    // All three fixtures share nothing but report group 6001; none share an MTCN, so search by
    // report-group-wide external txn ids one at a time and instead verify ordering using two rows
    // that DO match the same query: reuse the rule_hit and exclusion_audit fixtures' MTCN values by
    // searching on the external-txn-id side is not comparable across evidence sources, so assert
    // ordering directly against the three known timestamps via the MTCN branch on a shared value.
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, mtcn, external_txn_key, is_reported, "
        + "modified_timestamp) values (:groupId, 2, 'RULE-10', 3, 'BATCH-R2', 'SHARED-MTCN', 999001, false, '2026-01-05T00:00:00Z')",
        new MapSqlParameterSource("groupId", GROUP));
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, modified_timestamp) "
        + "values (:groupId, 'BATCH-J2', '999002', 'SHARED-MTCN', 'TRANSFORMATION', 'SUCCESS', '2026-01-04T00:00:00Z')",
        new MapSqlParameterSource("groupId", GROUP));

    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.MTCN, "SHARED-MTCN");

    assertEquals(2, results.size());
    assertEquals("RULE_HIT", results.get(0).evidenceSource(), "2026-01-05 sorts before 2026-01-04 (descending)");
    assertEquals("JOURNEY", results.get(1).evidenceSource());
  }

  private static final LocalDateTime MARCH_START = LocalDateTime.of(2026, 3, 1, 0, 0);
  private static final LocalDateTime APRIL_START = LocalDateTime.of(2026, 4, 1, 0, 0);

  private static TransactionSearchRepository.SearchScope march() {
    return new TransactionSearchRepository.SearchScope(MARCH_START, APRIL_START, false, List.of(-1), false, -1);
  }

  private void insertReconciliation(int group, String batchId, int seqNo, LocalDateTime created) {
    jdbcTemplate.update("insert into pharos.report_transformation_reconciliation (rpt_grp_id, batch_id, seq_no, created_timestamp) "
        + "values (:groupId, :batchId, :seqNo, :created)",
        new MapSqlParameterSource()
          .addValue("groupId", group)
          .addValue("batchId", batchId)
          .addValue("seqNo", seqNo)
          .addValue("created", created));
  }

  private void insertRra(long txnSurKey, String mtcn) {
    jdbcTemplate.update("insert into pharos.reg_reportable_activity (txn_sur_key, mtcn) values (:key, :mtcn)",
        new MapSqlParameterSource().addValue("key", txnSurKey).addValue("mtcn", mtcn));
  }

  private void insertJourney(int group, String batchId, String identifier, String mtcn) {
    jdbcTemplate.update("insert into pharos.record_transformation_journey (rpt_grp_id, batch_id, identifier, mtcn, stage, status, modified_timestamp) "
        + "values (:groupId, :batchId, :identifier, :mtcn, 'SELECTION', 'NOT_YET_REPORTED', '2026-03-10T00:00:00Z')",
        new MapSqlParameterSource()
          .addValue("groupId", group)
          .addValue("batchId", batchId)
          .addValue("identifier", identifier)
          .addValue("mtcn", mtcn));
  }

  @Test
  void scopedSearchOnlyLooksInBatchesInsideThePeriodAndReturnsEachMatchOnceAcrossSequences() {
    insertReconciliation(GROUP, "BATCH-J", 1, MARCH_START.plusDays(9));
    insertReconciliation(GROUP, "BATCH-J", 2, MARCH_START.plusDays(10));

    assertEquals(1, repository.search(TransactionSearchField.MTCN, "MTCN-J-1", march()).size(),
        "two reconciliation sequences of one batch must not repeat the match");
    assertTrue(repository
          .search(TransactionSearchField.MTCN, "MTCN-J-1",
              new TransactionSearchRepository.SearchScope(APRIL_START, APRIL_START.plusMonths(1), false, List.of(-1), false, -1))
          .isEmpty(), "the batch ran in March, so an April period must not find it");
  }

  @Test
  void scopedSearchHonoursReportGroupAndCountryFilters() {
    insertReconciliation(GROUP, "BATCH-S1", 1, MARCH_START.plusDays(1));
    insertReconciliation(OTHER_GROUP, "BATCH-S2", 1, MARCH_START.plusDays(2));
    insertJourney(GROUP, "BATCH-S1", "777101", "SCOPE-MTCN");
    insertJourney(OTHER_GROUP, "BATCH-S2", "777102", "SCOPE-MTCN");

    assertEquals(2, repository.search(TransactionSearchField.MTCN, "SCOPE-MTCN", march()).size());
    List<TransactionSearchResultProjection> groupOnly = repository.search(TransactionSearchField.MTCN, "SCOPE-MTCN",
        new TransactionSearchRepository.SearchScope(MARCH_START, APRIL_START, false, List.of(-1), true, GROUP));
    assertEquals(1, groupOnly.size());
    assertEquals(GROUP, groupOnly.get(0).reportGroupId());
    List<TransactionSearchResultProjection> countryOnly = repository.search(TransactionSearchField.MTCN, "SCOPE-MTCN",
        new TransactionSearchRepository.SearchScope(MARCH_START, APRIL_START, true, List.of(OTHER_GROUP), false, -1));
    assertEquals(1, countryOnly.size());
    assertEquals(OTHER_GROUP, countryOnly.get(0).reportGroupId());
    assertTrue(repository
          .search(TransactionSearchField.MTCN, "SCOPE-MTCN",
              new TransactionSearchRepository.SearchScope(MARCH_START, APRIL_START, true, List.of(), false, -1))
          .isEmpty(), "a country with no report groups matches nothing");
  }

  @Test
  void scopedMtcnSearchFindsAJourneyRowWithNoMtcnThroughTheTransactionKeyInRegReportableActivity() {
    insertReconciliation(GROUP, "BATCH-N", 1, MARCH_START.plusDays(3));
    insertJourney(GROUP, "BATCH-N", "777001", null);
    insertRra(777001, "MTCN-N-1");

    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.MTCN, "MTCN-N-1", march());

    assertEquals(1, results.size());
    assertEquals("JOURNEY", results.get(0).evidenceSource());
    assertEquals(null, results.get(0).mtcn());
    assertEquals("777001", results.get(0).identifier(), "the row has no MTCN, so its identifier is what a caller can follow up on");
    assertTrue(repository.search(TransactionSearchField.MTCN, "MTCN-N-1").isEmpty(),
        "the unscoped search is unchanged and never consults RRA");
  }

  @Test
  void scopedMtcnSearchNeverPullsInARowCarryingADifferentMtcn() {
    insertReconciliation(GROUP, "BATCH-N", 1, MARCH_START.plusDays(3));
    insertJourney(GROUP, "BATCH-N", "777002", "OTHER-MTCN");
    insertRra(777002, "MTCN-N-2");

    assertTrue(repository.search(TransactionSearchField.MTCN, "MTCN-N-2", march()).isEmpty());
  }

  @Test
  void scopedExternalTxnIdSearchFindsRuleHitAndExclusionRowsWithNoKeyThroughTheMtcnInRegReportableActivity() {
    insertReconciliation(GROUP, "BATCH-K", 1, MARCH_START.plusDays(4));
    insertRra(777003, "MTCN-K-1");
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, mtcn, external_txn_key, is_reported) "
        + "values (:groupId, 7, 'RULE-K', 70, 'BATCH-K', 'MTCN-K-1', null, false)", new MapSqlParameterSource("groupId", GROUP));
    jdbcTemplate.update("insert into pharos.rule_hit (rpt_grp_id, bucket_id, rule_id, attempt_id, efile_batch_id, mtcn, external_txn_key, is_reported) "
        + "values (:groupId, 7, 'RULE-K', 71, 'BATCH-K', 'MTCN-K-1', 777999, false)", new MapSqlParameterSource("groupId", GROUP));
    jdbcTemplate.update("insert into pharos.rule_hit_exclusion_audit (attempt_id, rpt_grp_id, rule_id, bucket_id, processing_batch_id, mtcn, external_txn_key) "
        + "values (72, :groupId, 'RULE-K', 7, 'BATCH-K', 'MTCN-K-1', null)", new MapSqlParameterSource("groupId", GROUP));

    List<TransactionSearchResultProjection> results = repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "777003", march());

    assertEquals(2, results.size(), "the rule hit carrying a different key (777999) must not be pulled in by the shared MTCN");
    assertTrue(results
      .stream()
      .anyMatch(r -> r.evidenceSource().equals("RULE_HIT")));
    assertTrue(results
      .stream()
      .anyMatch(r -> r.evidenceSource().equals("EXCLUSION_AUDIT")));
  }

  @Test
  void scopedExternalTxnIdSearchStillMatchesEachTablesOwnKey() {
    insertReconciliation(GROUP, "BATCH-J", 1, MARCH_START.plusDays(5));
    insertReconciliation(GROUP, "BATCH-R", 1, MARCH_START.plusDays(5));
    insertReconciliation(GROUP, "BATCH-E", 1, MARCH_START.plusDays(5));

    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555001", march()).size(), "journey identifier");
    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555002", march()).size(), "rule_hit external_txn_key");
    assertEquals(1, repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "555003", march()).size(), "exclusion_audit external_txn_key");
    assertTrue(repository.search(TransactionSearchField.EXTERNAL_TXN_ID, "not-a-number", march()).isEmpty());
  }

  @Test
  void everyResultCarriesItsRowsTransactionKeyAsIdentifier() {
    assertEquals("555001", repository.search(TransactionSearchField.MTCN, "MTCN-J-1").get(0).identifier(), "journey identifier");
    assertEquals("555002", repository.search(TransactionSearchField.MTCN, "MTCN-R-1").get(0).identifier(), "rule_hit external_txn_key");
    assertEquals("555003", repository.search(TransactionSearchField.MTCN, "MTCN-E-1").get(0).identifier(),
        "exclusion_audit external_txn_key");
  }
}
