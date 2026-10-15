package com.wu.compliance.dashboard.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class EvidenceSqlSupportTest {
  @Test
  void transactionKeyTakesTheDigitsOfAPlainOrPaysideSuffixedIdentifier() {
    assertEquals("(case when j.identifier ~ '^[0-9]+(_[PS])?$' then split_part(j.identifier, '_', 1)::bigint else null end)",
        EvidenceSqlSupport.transactionKey("j.identifier"));
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
    assertEquals("(lower(j.identifier) like :searchPattern escape '\\' or lower(coalesce(j.mtcn, '')) like :searchPattern escape '\\')",
        fragment.sql());
    assertEquals("%mtcn-1%", fragment.params().get("searchPattern"));
  }

  @Test
  void searchScopeEscapesLikeMetacharactersSoTheyMatchLiterally() {
    assertEquals("%12345\\_pay%", EvidenceSqlSupport.searchScope("12345_PAY", "i", "m").params().get("searchPattern"));
    assertEquals("%50\\%%", EvidenceSqlSupport.searchScope("50%", "i", "m").params().get("searchPattern"));
    assertEquals("%a\\\\b%", EvidenceSqlSupport.searchScope("a\\b", "i", "m").params().get("searchPattern"));
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
