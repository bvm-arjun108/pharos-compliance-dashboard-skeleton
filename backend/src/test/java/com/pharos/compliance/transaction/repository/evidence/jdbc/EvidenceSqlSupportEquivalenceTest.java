package com.pharos.compliance.transaction.repository.evidence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.pharos.compliance.testsupport.PostgresIntegrationTest;
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
}
