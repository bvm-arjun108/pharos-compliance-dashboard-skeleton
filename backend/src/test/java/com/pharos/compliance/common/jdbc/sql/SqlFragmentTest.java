package com.pharos.compliance.common.jdbc.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlFragmentTest {
  @Test
  void asCteWrapsSqlAsNamedBody() {
    SqlFragment fragment = SqlFragment.of("select 1", Map.of("x", 1)).asCte("sort_keys");
    assertTrue(fragment.sql().startsWith("sort_keys AS ("));
    assertTrue(fragment.sql().contains("select 1"));
    assertEquals(1, fragment.params().get("x"));
  }

  @Test
  void combineBuildsOneWithClauseAndMergesParams() {
    SqlFragment cteA = SqlFragment.of("select :a as a", Map.of("a", 1)).asCte("a_cte");
    SqlFragment cteB = SqlFragment.of("select :b as b", Map.of("b", 2)).asCte("b_cte");
    SqlFragment body = SqlFragment.of("select * from a_cte, b_cte where a_cte.a = :a", Map.of("a", 1));

    SqlFragment combined = SqlFragment.combine(List.of(cteA, cteB), body);

    assertTrue(combined.sql().startsWith("WITH a_cte AS ("));
    assertTrue(combined.sql().contains("b_cte AS ("));
    assertTrue(combined.sql().contains("select * from a_cte, b_cte"));
    assertEquals(2, combined.params().size());
    assertEquals(1, combined.params().get("a"));
    assertEquals(2, combined.params().get("b"));
  }

  @Test
  void combineWithNoCtesReturnsBodyUnchanged() {
    SqlFragment body = SqlFragment.of("select 1");
    assertEquals(body, SqlFragment.combine(List.of(), body));
  }

  @Test
  void combineRejectsConflictingParameterNamesAcrossFragments() {
    SqlFragment cteA = SqlFragment.of("select :shared", Map.of("shared", "one")).asCte("a_cte");
    SqlFragment body = SqlFragment.of("select * from a_cte where x = :shared", Map.of("shared", "two"));

    assertThrows(IllegalStateException.class, () -> SqlFragment.combine(List.of(cteA), body));
  }

  @Test
  void combineAllowsTheSameParameterValueRepeatedAcrossFragments() {
    SqlFragment cteA = SqlFragment.of("select :shared", Map.of("shared", "same")).asCte("a_cte");
    SqlFragment body = SqlFragment.of("select * from a_cte where x = :shared", Map.of("shared", "same"));

    SqlFragment combined = SqlFragment.combine(List.of(cteA), body);

    assertEquals("same", combined.params().get("shared"));
  }

  @Test
  void parameterSourceExposesEveryBoundValue() {
    SqlFragment fragment = SqlFragment.of("select :a, :b", Map.of("a", 1, "b", "two"));
    var source = fragment.parameterSource();
    assertEquals(1, source.getValue("a"));
    assertEquals("two", source.getValue("b"));
  }
}
