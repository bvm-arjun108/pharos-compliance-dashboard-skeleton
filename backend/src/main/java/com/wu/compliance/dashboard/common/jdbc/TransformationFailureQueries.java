package com.wu.compliance.dashboard.common.jdbc;

import com.wu.compliance.dashboard.common.jdbc.sql.SqlFragment;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlResourceLoader;

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
 * strictly a superset of it.) {@link #journeyFailuresByBatch} computes the corrected,
 * journey-derived count ({@code COUNT(DISTINCT identifier)} at the TRANSFORMATION stage with an
 * ERROR/FAILED/FAILURE status) as a per-batch grouped subquery for list/aggregate queries, falling
 * back to the raw reconciliation column only for a batch with no journey rows at all. Single-batch
 * lookups ({@code report-context.sql}, {@code batch-details.sql}) apply the identical fallback rule
 * via their own inline {@code CROSS JOIN LATERAL}.
 */
public final class TransformationFailureQueries {
  private static final String JOURNEY_FAILURES_BY_BATCH_SQL = "sql/common/journey-failures-by-batch.sql";
  private static final String JOURNEY_FAILURES_BY_BATCH_TAIL_SQL = "sql/common/journey-failures-by-batch-tail.sql";

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
    String template = sql.load(JOURNEY_FAILURES_BY_BATCH_SQL) + reconciliationScope.sql() + sql.load(JOURNEY_FAILURES_BY_BATCH_TAIL_SQL);
    return SqlFragment.of(template, reconciliationScope.params());
  }
}
