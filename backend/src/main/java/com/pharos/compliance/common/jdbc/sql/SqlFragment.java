package com.pharos.compliance.common.jdbc.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * A piece of named-parameter SQL text plus the parameter values it binds -- the hand-written
 * replacement for jOOQ's composable {@code Table<?>}/{@code Condition} objects, which let one
 * method build a derived table consumed by a different method without either side re-deriving the
 * other's SQL. A jOOQ {@code Table<?>} is not itself executed, only rendered as a {@code FROM}
 * source for whatever query consumes it; a {@link SqlFragment} plays the same role via {@link
 * #asCte} + {@link #combine}, and is likewise inert until a caller runs its {@link #sql()} through
 * a {@code NamedParameterJdbcTemplate}.
 *
 * <p>Deliberately not a query-building DSL: this is string composition with parameter-map
 * merging, nothing more. A generic SQL object model would reintroduce the abstraction layer this
 * migration exists to remove.
 */
public record SqlFragment(String sql, Map<String, Object> params) {
  public SqlFragment {
    params = Map.copyOf(params);
  }

  public static SqlFragment of(String sql) {
    return new SqlFragment(sql, Map.of());
  }

  public static SqlFragment of(String sql, Map<String, Object> params) {
    return new SqlFragment(sql, params);
  }

  /**
   * The Spring JDBC parameter source for this fragment's own bind values -- passed straight to
   * {@code NamedParameterJdbcTemplate.query(sql(), parameterSource(), ...)} when this fragment is
   * a complete, standalone statement rather than a CTE body being combined into a larger one.
   */
  public MapSqlParameterSource parameterSource() {
    return new MapSqlParameterSource(params);
  }

  /**
   * Wraps this fragment's SQL as the body of a named CTE: {@code "name AS (sql)"}. The result is
   * not runnable on its own -- it is meant to be passed to {@link #combine} alongside the query
   * that references {@code name} in its own {@code FROM}/{@code JOIN} clause, exactly as a jOOQ
   * {@code Table<?>.asTable("name")} is consumed by a different method today.
   */
  public SqlFragment asCte(String name) {
    return new SqlFragment(name + " AS (\n" + sql + "\n)", params);
  }

  /**
   * Splices one or more {@link #asCte}-wrapped fragments into a single {@code WITH ...} prefix
   * ahead of {@code body}, merging every fragment's parameters into one map. This is how the
   * two-pass evidence pagination reuses one ranking/merge fragment as both an upstream CTE and a
   * value referenced by the final SELECT, without duplicating its SQL text at each use.
   *
   * @throws IllegalStateException if two fragments bind the same parameter name to different
   *     values -- a programmer error (accidental name collision) that must fail fast rather than
   *     silently let one fragment's bind value clobber another's.
   */
  public static SqlFragment combine(List<SqlFragment> ctes, SqlFragment body) {
    if (ctes.isEmpty()) {
      return body;
    }
    Map<String, Object> merged = new LinkedHashMap<>();
    StringBuilder with = new StringBuilder("WITH ");
    for (int i = 0; i < ctes.size(); i++) {
      SqlFragment cte = ctes.get(i);
      mergeParams(merged, cte.params());
      if (i > 0) {
        with.append(",\n");
      }
      with.append(cte.sql());
    }
    mergeParams(merged, body.params());
    return new SqlFragment(with + "\n" + body.sql(), merged);
  }

  private static void mergeParams(Map<String, Object> target, Map<String, Object> source) {
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object existing = target.putIfAbsent(entry.getKey(), entry.getValue());
      if (existing != null && !Objects.equals(existing, entry.getValue())) {
        throw new IllegalStateException("Parameter name collision while combining SQL fragments: " + entry.getKey());
      }
    }
  }
}
