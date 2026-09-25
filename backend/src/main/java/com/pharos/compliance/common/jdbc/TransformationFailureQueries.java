package com.pharos.compliance.common.jdbc;

import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;

/**
 * {@code report_transformation_reconciliation.activity_transformation_failed} is a scalar written
 * by the upstream transformer job by counting failed-transformation rows before they're collapsed
 * into {@code record_transformation_journey} (whose primary key is {@code (rpt_grp_id, batch_id,
 * identifier)}, no {@code seq_no}). When one transaction raises more than one validation failure,
 * the transformer's own reconciliation count reflects every failure event, but the journey table
 * can only ever hold one row per identifier -- so the two disagree whenever that happens, and the
 * reconciliation scalar is the one telling the story that never actually reached a real
 * transaction. (The reverse can also happen: if an identifier later succeeds, its earlier FAILURE
 * journey row is overwritten by the SUCCESS upsert, so journey can also undercount relative to
 * reconciliation -- the raw column is worth keeping visible precisely because journey isn't
 * strictly a superset of it.) These two methods compute the corrected, journey-derived count
 * ({@code COUNT(DISTINCT identifier)} at the TRANSFORMATION stage with an ERROR/FAILED/FAILURE
 * status) for the two shapes callers need it in. Every caller falls back to the raw reconciliation
 * column only when the batch has no journey rows at all (its own {@link #JOURNEY_AVAILABLE_COLUMN}
 * signal) -- otherwise the corrected value already reflects "zero failures" correctly on its own.
 */
public final class TransformationFailureQueries {
  private static final String JOURNEY_FAILURES_BY_BATCH_SQL = "sql/common/journey-failures-by-batch.sql";
  private static final String JOURNEY_STATS_LATERAL_SQL = "sql/common/journey-stats-lateral.sql";
  public static final String JOURNEY_AVAILABLE_COLUMN = "journey_available";
  public static final String JOURNEY_TRANSFORMATION_FAILURES_COLUMN = "journey_transformation_failures";

  private TransformationFailureQueries() {
  }

  /**
   * Per-batch grouped subquery for list/aggregate queries: callers {@code LEFT JOIN} this on
   * {@code (rpt_grp_id, batch_id)} and {@code COALESCE} the count to 0 for batches with no
   * failures. {@code reconciliationScope} must be the exact same scope condition text/params the
   * caller already applies to its own {@code report_transformation_reconciliation} query (date
   * range, batch/report-group filters, ...), written as bare {@code and ...} lines with no leading
   * {@code where} -- reused here, not rebuilt, exactly as the jOOQ version reused one {@code
   * Condition} object in two places.
   */
  public static SqlFragment journeyFailuresByBatch(SqlResourceLoader sql, SqlFragment reconciliationScope) {
    String template = sql.load(JOURNEY_FAILURES_BY_BATCH_SQL).replace("/*SCOPE*/", reconciliationScope.sql());
    return SqlFragment.of(template, reconciliationScope.params());
  }

  /**
   * {@code CROSS JOIN LATERAL} derived table for single-batch queries, exposing {@link
   * #JOURNEY_AVAILABLE_COLUMN} and {@link #JOURNEY_TRANSFORMATION_FAILURES_COLUMN}, each computed
   * once per outer row. {@code outerRptGrpIdColumn}/{@code outerBatchIdColumn} are the outer
   * query's own column references (e.g. {@code "r.rpt_grp_id"}) -- fixed SQL text supplied by the
   * calling repository, never a bound value or request-derived string.
   */
  public static SqlFragment journeyStatsLateral(SqlResourceLoader sql, String outerRptGrpIdColumn, String outerBatchIdColumn) {
    String rendered = sql
      .load(JOURNEY_STATS_LATERAL_SQL)
      .replace("/*OUTER_RPT_GRP_ID*/", outerRptGrpIdColumn)
      .replace("/*OUTER_BATCH_ID*/", outerBatchIdColumn);
    return SqlFragment.of(rendered);
  }
}
