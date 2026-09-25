package com.pharos.compliance.common.jdbc.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Locks in the exact fallback every jOOQ repository's own sort-direction check already uses (e.g.
 * {@code EvidencePaginator#evidenceOrder}): only the literal string {@code "ASC"} sorts ascending,
 * everything else -- including {@code "DESC"} -- falls back to descending. Migrating a query to use
 * {@link SqlSortDirection} must not change which requests currently produce which order.
 */
class SqlSortDirectionTest {
  @Test
  void exactAscStringSortsAscending() {
    assertEquals(SqlSortDirection.ASC, SqlSortDirection.from("ASC"));
    assertEquals("ASC", SqlSortDirection.from("ASC").keyword());
  }

  @Test
  void descStringFallsBackToDescending() {
    assertEquals(SqlSortDirection.DESC, SqlSortDirection.from("DESC"));
  }

  @Test
  void nullFallsBackToDescending() {
    assertEquals(SqlSortDirection.DESC, SqlSortDirection.from(null));
  }

  @Test
  void lowercaseOrGarbageFallsBackToDescending() {
    assertEquals(SqlSortDirection.DESC, SqlSortDirection.from("asc"));
    assertEquals(SqlSortDirection.DESC, SqlSortDirection.from("not-a-direction"));
    assertEquals(SqlSortDirection.DESC, SqlSortDirection.from(""));
  }
}
