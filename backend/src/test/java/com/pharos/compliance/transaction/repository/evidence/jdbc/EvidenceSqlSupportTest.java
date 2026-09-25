package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class EvidenceSqlSupportTest {
  @Test
  void matchesDigitsOnlyInlinesTheFixedRegexAgainstTheGivenColumn() {
    assertEquals("j.identifier ~ '^[0-9]+$'", EvidenceSqlSupport.matchesDigitsOnly("j.identifier"));
  }

  @Test
  void searchScopeIsAlwaysTrueForAnEmptySearch() {
    var fragment = EvidenceSqlSupport.searchScope("", "j.identifier", "j.mtcn");
    assertEquals("true", fragment.sql());
    assertTrue(fragment.params().isEmpty());
  }

  @Test
  void searchScopeBindsTheLowercasedWildcardPatternRatherThanInliningIt() {
    var fragment = EvidenceSqlSupport.searchScope("MTCN-1", "j.identifier", "j.mtcn");
    assertEquals("(lower(j.identifier) like :searchPattern or lower(coalesce(j.mtcn, '')) like :searchPattern)", fragment.sql());
    assertEquals("%mtcn-1%", fragment.params().get("searchPattern"));
  }

  @Test
  void journeyOutcomeMapsFixedStatusLiteralsToTheirBucket() {
    String expr = EvidenceSqlSupport.journeyOutcome("j.status");
    assertTrue(expr.contains("'ERROR', 'FAILED', 'FAILURE'"));
    assertTrue(expr.contains("'SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED'"));
    assertTrue(expr.contains("= 'EXCLUDED'"));
    assertTrue(expr.contains("else 'PENDING'"));
  }

  @Test
  void firstNonNullByRankBuildsTheFilteredArrayAggExpression() {
    String expr = EvidenceSqlSupport.firstNonNullByRankExpr("val", "source_rank", "record_key");
    assertEquals("(array_agg(val order by source_rank asc, record_key asc) filter (where val is not null))[1]", expr);
  }

  @Test
  void firstNonNullByRankAliasesBackToItsOwnColumnName() {
    String expr = EvidenceSqlSupport.firstNonNullByRank("comments", "source_rank", "record_key");
    assertEquals("(array_agg(comments order by source_rank asc, record_key asc) filter (where comments is not null))[1] as comments", expr);
  }
}
