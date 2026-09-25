package com.pharos.compliance.common.jdbc.sql;

/**
 * Resolves a client-supplied sort-direction string to a fixed, compile-time-known SQL keyword,
 * never interpolating the raw string itself into a query. This is the hand-written equivalent of
 * what jOOQ's typed {@code Field.sortAsc()}/{@code Field.sortDesc()} API guaranteed for free.
 *
 * <p>{@link #from(String)} preserves this codebase's existing fallback exactly, as found in {@code
 * EvidencePaginator#evidenceOrder} and every other sort-direction check across the jOOQ
 * repositories: only the literal string {@code "ASC"} sorts ascending; every other value --
 * {@code "DESC"}, {@code null}, blank, or garbage -- sorts descending. This is a fallback, not
 * stricter validation, so migrating a query to use this class changes nothing about which requests
 * currently produce which order.
 */
public enum SqlSortDirection {
  ASC("ASC"),
  DESC("DESC");
  private final String keyword;

  SqlSortDirection(String keyword) {
    this.keyword = keyword;
  }

  public static SqlSortDirection from(String rawSortDirection) {
    return "ASC".equals(rawSortDirection) ? ASC : DESC;
  }

  /**
   * The literal, safe-to-concatenate SQL keyword -- never the caller's original string.
   */
  public String keyword() {
    return keyword;
  }
}
