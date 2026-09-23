package com.pharos.compliance.transaction.repository.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.function.Function;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Guards the split between the two rule_hit consumers. The union branch's source is deliberately
 * short-circuited to zero rows for statuses a RULE_HIT evidence row can never carry; the "Rule Hit
 * Details" enrichment's source must never be, because it answers a question about the transaction
 * rather than about this row's evidence source. Sharing one table between them shipped a real bug
 * -- every expanded row on an EXCLUDED or NOT_REPORTED drilldown claimed the transaction had no
 * rule hits, while the panel's own subnote promised the opposite -- so these tests assert the
 * wiring at the exact call sites that regressed, not just the matcher methods in isolation.
 */
class RuleHitEnrichmentScopeTest {
  /**
   * Statuses that actually reach the period list path; EXCLUDED/NOT_REPORTED route to the overview path instead.
   */
  private static final List<String> PERIOD_LIST_STATUSES = List.of("ALL", "SUCCESS", "FAILED", "ERROR", "NOT_YET_REPORTED", "REPORTED");
  private static final List<String> OVERVIEW_STATUSES = List.of("EXCLUDED", "NOT_REPORTED");
  private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
  private final EvidencePaginator paginator = mock(EvidencePaginator.class);
  private final PeriodEvidenceQueries periodEvidenceQueries = new PeriodEvidenceQueries(dsl, new RuleHitMatcher(dsl), paginator);
  private final OverviewEvidenceQueries overviewEvidenceQueries = new OverviewEvidenceQueries(dsl, paginator, periodEvidenceQueries);
  private final BatchEvidenceQueries batchEvidenceQueries = new BatchEvidenceQueries(dsl, new RuleHitMatcher(dsl), paginator);

  private Table<?> scope() {
    return periodEvidenceQueries.batchScope(LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0), false, List.of(), false,
        -1, "");
  }

  private String capturedEnrichmentSql() {
    var captor = captor();
    verify(paginator).pageEvidence(any(), captor.capture(), anyString(), anyInt(), anyLong(), any(), any());
    return bridgeFor(captor);
  }

  /**
   * Rendering an aliased derived table on its own yields only its alias -- wrapping it in a select
   * puts it in a FROM clause, where jOOQ renders the full definition this test needs to inspect.
   * Inlined so the page identifiers appear as literals rather than as {@code ?} placeholders.
   */
  private String declarationOf(Table<?> table) {
    return dsl.renderInlined(dsl.select(DSL.asterisk()).from(table));
  }

  /**
   * The identifiers a page would contain; the bridge must come back bounded to exactly these.
   */
  private static final List<String> PAGE_IDENTIFIERS = List.of("8000000000000023401", "8000000000000035101");

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Function<Collection<String>, Table<?>>> captor() {
    return ArgumentCaptor.forClass((Class<Function<Collection<String>, Table<?>>>) (Class<?>) Function.class);
  }

  /**
   * Applies the captured bridge factory with a representative page and renders the result.
   */
  private String bridgeFor(ArgumentCaptor<Function<Collection<String>, Table<?>>> captor) {
    return declarationOf(captor.getValue().apply(PAGE_IDENTIFIERS));
  }

  private void assertRealMatch(String sql, String context) {
    assertTrue(sql.contains("journey_scoped"),
        context + " should resolve rule hits through the journey identifier lookup, but rendered: " + sql);
    assertFalse(sql.contains("where false"), context + " must not receive the zero-row union-branch table, but rendered: " + sql);
    for (String identifier : PAGE_IDENTIFIERS) {
      assertTrue(sql.contains(identifier), context + " should bound the bridge to the page's identifiers, but rendered: " + sql);
    }
  }

  @Test
  void periodListEnrichmentKeepsRuleHitsForEveryStatus() {
    for (String status : PERIOD_LIST_STATUSES) {
      EvidencePaginator perStatusPaginator = mock(EvidencePaginator.class);
      var queries = new PeriodEvidenceQueries(dsl, new RuleHitMatcher(dsl), perStatusPaginator);
      queries.findEvidenceRecords(scope(), "", "ALL", status, "DESC", 50, 0, null, EvidenceProjection.DETAIL);

      var captor = captor();
      verify(perStatusPaginator).pageEvidence(any(), captor.capture(), anyString(), anyInt(), anyLong(), any(), any());
      assertRealMatch(bridgeFor(captor), "Period list enrichment for status=" + status);
    }
  }

  @Test
  void overviewDrilldownEnrichmentKeepsRuleHitsForExcludedAndNotReported() {
    for (String status : OVERVIEW_STATUSES) {
      EvidencePaginator perStatusPaginator = mock(EvidencePaginator.class);
      var period = new PeriodEvidenceQueries(dsl, new RuleHitMatcher(dsl), perStatusPaginator);
      var overview = new OverviewEvidenceQueries(dsl, perStatusPaginator, period);
      overview.findOverviewEvidenceRecords(scope(), status, "", "", "ALL", "DESC", 50, 0, null, EvidenceProjection.DETAIL);

      var captor = captor();
      verify(perStatusPaginator).pageEvidence(any(), captor.capture(), anyString(), anyInt(), anyLong(), any(), any());
      assertRealMatch(bridgeFor(captor), "Overview drilldown enrichment for status=" + status);
    }
  }

  @Test
  void batchTotalExcludedDrilldownKeepsRuleHits() {
    periodEvidenceQueries.findExcludedEvidenceRecordsForBatchTotal(scope(), "", "DESC", 50, 0, null, EvidenceProjection.DETAIL);
    assertRealMatch(capturedEnrichmentSql(), "Report Groups Requiring Attention excluded drilldown enrichment");
  }

  @Test
  void batchListEnrichmentKeepsRuleHitsForEveryStatus() {
    for (String status : List.of("ALL", "SUCCESS", "FAILED", "ERROR", "EXCLUDED", "NOT_YET_REPORTED", "REPORTED", "NOT_REPORTED")) {
      EvidencePaginator perStatusPaginator = mock(EvidencePaginator.class);
      var queries = new BatchEvidenceQueries(dsl, new RuleHitMatcher(dsl), perStatusPaginator);
      queries.findEvidenceRecords(51, "BIN512609021152002340", "ALL", "", "ALL", "ALL", "ALL", status, "DESC", 50, 0, null,
          EvidenceProjection.DETAIL);

      var captor = captor();
      verify(perStatusPaginator).pageEvidence(any(), captor.capture(), anyString(), anyInt(), anyLong(), any(), any());
      assertRealMatch(bridgeFor(captor), "Batch list enrichment for status=" + status);
    }
  }

  /**
   * The list projection reads no rule hits, so it must not build the bridge at all -- that is what
   * took the enrichment's cost off the per-page path once detail moved to its own request.
   */
  @Test
  void listProjectionNeverBuildsTheRuleHitBridge() {
    EvidencePaginator listPaginator = mock(EvidencePaginator.class);
    var queries = new PeriodEvidenceQueries(dsl, new RuleHitMatcher(dsl), listPaginator);
    queries.findEvidenceRecords(scope(), "", "ALL", "ALL", "DESC", 50, 0, null, EvidenceProjection.LIST);

    var captor = captor();
    verify(listPaginator).pageEvidence(any(), captor.capture(), anyString(), anyInt(), anyLong(), any(), any());
    assertEquals(EvidenceProjection.LIST, projectionOf(listPaginator), "list path should request the LIST projection");
  }

  @Test
  void periodEnrichmentMatchesGroupAndBatchAndRestrictsRuleHitScope() {
    String sql = declarationOf(periodEvidenceQueries.ruleHitMatchesForPeriodEnrichment(scope(), PAGE_IDENTIFIERS));
    assertTrue(sql.contains("\"by_identifier_lookup\".\"rpt_grp_id\" = \"pharos\".\"rule_hit\".\"rpt_grp_id\""));
    assertTrue(sql.contains("\"by_identifier_lookup\".\"batch_id\" = \"pharos\".\"rule_hit\".\"efile_batch_id\""));
    assertTrue(sql.contains("\"by_mtcn_lookup\".\"rpt_grp_id\" = \"pharos\".\"rule_hit\".\"rpt_grp_id\""));
    assertTrue(sql.contains("\"by_mtcn_lookup\".\"batch_id\" = \"pharos\".\"rule_hit\".\"efile_batch_id\""));
    assertTrue(sql.contains(
        "(\"pharos\".\"rule_hit\".\"rpt_grp_id\", \"pharos\".\"rule_hit\".\"efile_batch_id\") in"), sql);
  }

  /**
   * Union rows are counted as well as displayed: a rule hit from outside the selected
   * group/batch pairs must not add another evidence-batch/identifier pair to the result.
   */
  @Test
  void periodUnionBranchScopesRuleHitsToTheWindowsOwnBatches() {
    for (String status : List.of("ALL", "REPORTED")) {
      String sql = declarationOf(periodEvidenceQueries.ruleHitMatchesForPeriod(scope(), status));
      assertTrue(sql.contains("(\"pharos\".\"rule_hit\".\"rpt_grp_id\", \"pharos\".\"rule_hit\".\"efile_batch_id\") in"),
          "period union branch for status=" + status + " must scope rule hits to the window's own (group, batch) pairs, "
              + "not to the report group as a whole, but rendered: " + sql);
    }
  }

  private EvidenceProjection projectionOf(EvidencePaginator paginator) {
    ArgumentCaptor<EvidenceProjection> mode = ArgumentCaptor.forClass(EvidenceProjection.class);
    verify(paginator).pageEvidence(any(), any(), anyString(), anyInt(), anyLong(), any(), mode.capture());
    return mode.getValue();
  }

  /**
   * The other half of the contract: fixing the enrichment must not widen the union branch, or the
   * list would gain RULE_HIT rows for statuses they can't carry and the counts would move.
   */
  @Test
  void unionBranchStillShortCircuitsForStatusesRuleHitRowsCannotCarry() {
    assertTrue(declarationOf(periodEvidenceQueries.ruleHitMatchesForPeriod(scope(), "EXCLUDED")).contains("where false"),
        "Period union branch should still skip the rule_hit join for EXCLUDED");
    assertTrue(declarationOf(periodEvidenceQueries.ruleHitMatchesForPeriod(scope(), "NOT_REPORTED")).contains("where false"),
        "Period union branch should still skip the rule_hit join for NOT_REPORTED");
    assertTrue(declarationOf(batchEvidenceQueries.ruleHitMatchesForBatch(51, "BIN1", "SUCCESS")).contains("where false"),
        "Batch union branch should still skip the rule_hit join for SUCCESS");

    assertFalse(declarationOf(periodEvidenceQueries.ruleHitMatchesForPeriod(scope(), "ALL")).contains("where false"),
        "Period union branch should still resolve rule hits for ALL");
    assertFalse(declarationOf(batchEvidenceQueries.ruleHitMatchesForBatch(51, "BIN1", "REPORTED")).contains("where false"),
        "Batch union branch should still resolve rule hits for REPORTED");
  }
}
