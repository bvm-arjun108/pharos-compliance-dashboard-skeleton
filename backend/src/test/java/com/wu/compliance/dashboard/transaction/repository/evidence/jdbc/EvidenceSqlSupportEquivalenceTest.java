package com.wu.compliance.dashboard.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlFragment;
import com.wu.compliance.dashboard.testsupport.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Execution-semantics coverage for {@link EvidenceSqlSupport#firstNonNullByRankExpr} -- the
 * single highest-risk hand-translation in the jOOQ-to-JDBC migration (the {@code
 * array_agg(...) filter(...) order by...)[1]} priority-merge idiom used by every evidence
 * pipeline). Originally written in Phase 6a as an old-jOOQ-vs-new-hand-SQL equivalence test; the
 * old jOOQ version was deleted in Phase 6h once every consumer had migrated, so this now asserts
 * directly on the expression's real execution result against seeded Postgres data.
 */
class EvidenceSqlSupportEquivalenceTest extends PostgresIntegrationTest {
  @BeforeEach
  void setUpTable() {
    jdbcTemplate.getJdbcOperations().update("drop table if exists rank_source_test");
    jdbcTemplate.getJdbcOperations().update("create table rank_source_test (source_rank int, record_key text, val text)");
  }

  private void insertRow(int rank, String key, String value) {
    jdbcTemplate.update("insert into rank_source_test (source_rank, record_key, val) values (:rank, :key, :val)",
        new MapSqlParameterSource().addValue("rank", rank).addValue("key", key).addValue("val", value));
  }

  private String result() {
    String expr = EvidenceSqlSupport.firstNonNullByRankExpr("val", "source_rank", "record_key");
    return jdbcTemplate.getJdbcOperations().queryForObject("select " + expr + " from rank_source_test", String.class);
  }

  @Test
  void picksTheHighestPriorityNonNullValueSkippingARank1Null() {
    insertRow(1, "b", null);
    insertRow(2, "a", "from-rank-2");
    insertRow(3, "c", "ignored-lower-priority");

    assertEquals("from-rank-2", result());
  }

  @Test
  void breaksTiesOnRecordKeyWhenTwoRowsShareTheSameRank() {
    insertRow(1, "z-key", "from-z-key");
    insertRow(1, "a-key", "from-a-key");
    // Same rank -> record_key ASC tiebreak picks "a-key" first.
    assertEquals("from-a-key", result());
  }

  @Test
  void returnsNullWhenEveryValueInTheGroupIsNull() {
    insertRow(1, "a", null);
    insertRow(2, "b", null);

    assertEquals(null, result());
  }

  @Test
  void singleRowGroupReturnsItsOwnValue() {
    insertRow(1, "only", "solo-value");

    assertEquals("solo-value", result());
  }

  private List<String> identifiersMatching(String search) {
    SqlFragment scope = EvidenceSqlSupport.searchScope(search, "identifier", "mtcn");
    return jdbcTemplate.queryForList("select identifier from (values ('12345_PAY', 'M-1'), ('12345XPAY', 'M-2'), ('123456PAY', 'M-3'), "
        + "('9_12345_PAY_1', null), ('50%OFF', null), ('5000FF', null)) v(identifier, mtcn) where " + scope.sql() + " order by 1",
        new MapSqlParameterSource(scope.params()), String.class);
  }

  @Test
  void searchScopeMatchesUnderscoreAndPercentLiterallyAgainstPostgres() {
    assertEquals(List.of("12345_PAY", "9_12345_PAY_1"), identifiersMatching("12345_pay"), "_ must not match X or 6 in that position");
    assertEquals(List.of("50%OFF"), identifiersMatching("50%"), "% must not match any string");
    assertEquals(List.of("12345_PAY", "9_12345_PAY_1"), identifiersMatching("_"),
        "a lone _ matches only values that contain one, not every row");
  }

  @Test
  void transactionKeyResolvesPlainAndPaysideSuffixedIdentifiersOnlyAgainstPostgres() {
    List<String> keys = jdbcTemplate
      .getJdbcOperations()
      .queryForList("select coalesce(" + EvidenceSqlSupport.transactionKey("v")
          + "::text, 'null') from (values ('12345'), ('12345_P'), ('12345_S'), ('12345_X'), ('12345_p'), ('12_34'), ('abc'), ('_P')) t(v)",
          String.class);
    assertEquals(List.of("12345", "12345", "12345", "null", "null", "null", "null", "null"), keys);
  }
}
