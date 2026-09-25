package com.pharos.compliance.transaction.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
import com.pharos.compliance.transaction.model.TransactionSearchField;
import com.pharos.compliance.transaction.repository.projection.TransactionSearchResultProjection;
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
  private TransactionSearchRepository repository;

  @BeforeEach
  void setUp() {
    jdbcTemplate.getJdbcOperations().update("delete from pharos.report_group_config where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.record_transformation_journey where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit where rpt_grp_id = " + GROUP);
    jdbcTemplate.getJdbcOperations().update("delete from pharos.rule_hit_exclusion_audit where rpt_grp_id = " + GROUP);
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
}
