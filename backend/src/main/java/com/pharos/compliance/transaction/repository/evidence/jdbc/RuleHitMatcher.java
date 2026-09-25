package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves {@code rule_hit} rows back to the journey identifier they belong to via a join, not a
 * correlated scalar subquery -- the correlated form was measured 100-600x slower, because it
 * re-scans the journey table once per {@code rule_hit} row instead of resolving every row's match
 * in a single grouped lookup.
 *
 * <p>{@code journeyScoped} is the caller's own already-scoped journey subquery body (one batch, or
 * one period's batches), supplied as a {@link SqlFragment} rather than a CTE name. Its SQL text is
 * embedded as an inline derived table in <em>both</em> the identifier lookup and the mtcn lookup
 * below -- deliberately re-rendered at each point rather than referenced once via a {@code
 * WITH}-declared CTE, so this class has no dependency on how the caller structures its own CTE
 * list. {@code journeyScoped} must expose {@code rpt_grp_id}, {@code batch_id}, {@code
 * identifier}, {@code mtcn} and {@code identifier_bigint} columns.
 */
public class RuleHitMatcher {
  private static final String RULE_HIT_TABLE = "pharos.rule_hit";

  /**
   * Unscoped-by-batch match: the caller's {@code ruleHitScope} already narrows {@code rule_hit} to
   * one batch (or otherwise makes cross-batch collisions moot), so the lookup tables only need to
   * group by the join key itself.
   */
  public SqlFragment ruleHitMatches(SqlFragment ruleHitScope, SqlFragment journeyScoped) {
    String sql = "select rh.*, coalesce(by_identifier_lookup.identifier, by_mtcn_lookup.identifier) as matched_identifier\n" + "from "
        + RULE_HIT_TABLE + " rh\n" + "left join (\n" + "  select identifier_bigint, min(identifier) as identifier\n" + "  from ("
        + journeyScoped.sql() + ") journey_scoped\n" + "  where identifier_bigint is not null\n" + "  group by identifier_bigint\n"
        + ") by_identifier_lookup\n" + "  on by_identifier_lookup.identifier_bigint = rh.external_txn_key\n" + "left join (\n"
        + "  select mtcn, min(identifier) as identifier\n" + "  from (" + journeyScoped.sql() + ") journey_scoped\n"
        + "  where mtcn is not null\n" + "  group by mtcn\n" + ") by_mtcn_lookup\n" + "  on by_mtcn_lookup.mtcn = rh.mtcn\n" + "where "
        + ruleHitScope.sql();
    return SqlFragment.of(sql, mergedParams(ruleHitScope, journeyScoped));
  }

  /**
   * Scoped-by-(report group, batch) match: {@code journeyScoped} may span more than one batch (a
   * whole period), so the lookup tables additionally group and join on {@code (rpt_grp_id,
   * batch_id)} to guarantee an exact per-batch match rather than one that could cross batch
   * boundaries within the scope.
   */
  public SqlFragment scopedRuleHitMatches(SqlFragment ruleHitScope, SqlFragment journeyScoped) {
    String sql = "select rh.*, coalesce(by_identifier_lookup.identifier, by_mtcn_lookup.identifier) as matched_identifier\n" + "from "
        + RULE_HIT_TABLE + " rh\n" + "left join (\n" + "  select rpt_grp_id, batch_id, identifier_bigint, min(identifier) as identifier\n"
        + "  from (" + journeyScoped.sql() + ") journey_scoped\n" + "  where identifier_bigint is not null\n"
        + "  group by rpt_grp_id, batch_id, identifier_bigint\n" + ") by_identifier_lookup\n"
        + "  on by_identifier_lookup.identifier_bigint = rh.external_txn_key\n" + "  and by_identifier_lookup.rpt_grp_id = rh.rpt_grp_id\n"
        + "  and by_identifier_lookup.batch_id = rh.efile_batch_id\n" + "left join (\n"
        + "  select rpt_grp_id, batch_id, mtcn, min(identifier) as identifier\n" + "  from (" + journeyScoped.sql() + ") journey_scoped\n"
        + "  where mtcn is not null\n" + "  group by rpt_grp_id, batch_id, mtcn\n" + ") by_mtcn_lookup\n"
        + "  on by_mtcn_lookup.mtcn = rh.mtcn\n" + "  and by_mtcn_lookup.rpt_grp_id = rh.rpt_grp_id\n"
        + "  and by_mtcn_lookup.batch_id = rh.efile_batch_id\n" + "where " + ruleHitScope.sql();
    return SqlFragment.of(sql, mergedParams(ruleHitScope, journeyScoped));
  }

  private static Map<String, Object> mergedParams(SqlFragment ruleHitScope, SqlFragment journeyScoped) {
    Map<String, Object> params = new HashMap<>(journeyScoped.params());
    params.putAll(ruleHitScope.params());
    return params;
  }
}
