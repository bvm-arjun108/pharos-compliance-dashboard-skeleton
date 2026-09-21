package com.pharos.compliance.common.jooq;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import static com.pharos.compliance.jooq.tables.ReportTransformationReconciliation.REPORT_TRANSFORMATION_RECONCILIATION;
import com.pharos.compliance.jooq.tables.RecordTransformationJourney;
import com.pharos.compliance.jooq.tables.ReportTransformationReconciliation;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

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
 * (`COUNT(DISTINCT identifier)` at the TRANSFORMATION stage with an ERROR/FAILED/FAILURE status --
 * the same status set {@code EvidenceSqlSupport#journeyOutcome} normalizes to {@code OUTCOME_ERROR})
 * for the two shapes callers need it in. Every caller falls back to the raw reconciliation column
 * only when the batch has no journey rows at all (its own {@code journeyAvailable} signal) --
 * otherwise the corrected value already reflects "zero failures" correctly on its own.
 */
public final class TransformationFailureQueries {
  private static final RecordTransformationJourney JOURNEY = RECORD_TRANSFORMATION_JOURNEY;
  private static final ReportTransformationReconciliation RECONCILIATION = REPORT_TRANSFORMATION_RECONCILIATION;
  private static final String STAGE_TRANSFORMATION = "TRANSFORMATION";
  private static final Set<String> FAILURE_STATUSES = Set.of("ERROR", "FAILED", "FAILURE");
  public static final String JOURNEY_TRANSFORMATION_FAILURES_COLUMN = "journey_transformation_failures";
  public static final String JOURNEY_AVAILABLE_COLUMN = "journey_available";
  private static final String SCOPE_RPT_GRP_ID_COLUMN = "scope_rpt_grp_id";
  private static final String SCOPE_BATCH_ID_COLUMN = "scope_batch_id";

  private TransformationFailureQueries() {
  }

  /**
   * {@code LATERAL} derived table for single-batch queries (Batch Explorer's details view,
   * Transaction Report's context) exposing {@link #JOURNEY_AVAILABLE_COLUMN} (boolean) and {@link
   * #JOURNEY_TRANSFORMATION_FAILURES_COLUMN} (long), each computed exactly once. A plain {@code
   * Condition}/{@code Field} built from an EXISTS/correlated-COUNT expression renders its full SQL
   * text again every time it's referenced elsewhere in the same query -- reusing one across a CASE
   * expression, a boolean flag column, and the exposed availability column (as the very first cut
   * of this fix did) made Postgres evaluate the same journey-table subquery 5 times per row instead
   * of computing it once. A LATERAL join computes both values once per outer row and callers just
   * read the resulting columns.
   */
  public static Table<?> journeyStatsLateral(DSLContext dsl, Field<Integer> rptGrpId, Field<String> batchId) {
    Condition failureCondition = DSL.upper(JOURNEY.STAGE).eq(STAGE_TRANSFORMATION).and(DSL.upper(JOURNEY.STATUS).in(FAILURE_STATUSES));
    // Both aggregates read the same (rpt_grp_id, batch_id)-scoped row set in one pass -- no nested
    // EXISTS subquery needed for availability, since "at least one journey row for this batch" is
    // just COUNT(*) > 0 over the rows this query already scans for the failure count.
    return DSL
      .lateral(dsl
        .select(DSL.field(DSL.count().gt(0)).as(JOURNEY_AVAILABLE_COLUMN),
            DSL.countDistinct(JOURNEY.IDENTIFIER).filterWhere(failureCondition).cast(SQLDataType.BIGINT).as(
                JOURNEY_TRANSFORMATION_FAILURES_COLUMN))
        .from(JOURNEY)
        .where(JOURNEY.RPT_GRP_ID.eq(rptGrpId))
        .and(JOURNEY.BATCH_ID.eq(batchId)))
      .asTable("journey_stats");
  }

  /**
   * Per-batch grouped subquery for list/aggregate queries (Batch Explorer's queue, Dashboard's
   * headline counts and trend) -- callers {@code LEFT JOIN} this on {@code (rpt_grp_id, batch_id)}
   * and {@code COALESCE} the count to 0 for batches with no failures. Exposes {@link
   * #JOURNEY_TRANSFORMATION_FAILURES_COLUMN}.
   *
   * <p>{@code reconciliationScope} is the same scope condition the caller already applies to {@code
   * report_transformation_reconciliation} for its own query (date range, batch/report-group
   * filters, ...) -- it narrows this aggregate to only the batches actually being displayed via a
   * semi-join, instead of grouping every TRANSFORMATION-stage failure row that has ever existed on
   * every call. Without it, this aggregate's cost grows with the whole history of {@code
   * record_transformation_journey} regardless of how narrow the caller's own date filter is.
   */
  public static Table<?> journeyFailuresByBatch(DSLContext dsl, Condition reconciliationScope) {
    var scopedBatches = dsl
      .selectDistinct(RECONCILIATION.RPT_GRP_ID.as(SCOPE_RPT_GRP_ID_COLUMN), RECONCILIATION.BATCH_ID.as(SCOPE_BATCH_ID_COLUMN))
      .from(RECONCILIATION)
      .where(reconciliationScope)
      .asTable("scoped_batches");
    Field<Integer> scopeRptGrpId = requiredField(scopedBatches, SCOPE_RPT_GRP_ID_COLUMN, Integer.class);
    Field<String> scopeBatchId = requiredField(scopedBatches, SCOPE_BATCH_ID_COLUMN, String.class);

    return dsl
      .select(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID,
          DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as(JOURNEY_TRANSFORMATION_FAILURES_COLUMN))
      .from(JOURNEY)
      .join(scopedBatches)
      .on(scopeRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(scopeBatchId.eq(JOURNEY.BATCH_ID))
      .where(DSL.upper(JOURNEY.STAGE).eq(STAGE_TRANSFORMATION))
      .and(DSL.upper(JOURNEY.STATUS).in(FAILURE_STATUSES))
      .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
      .asTable("journey_failures_by_batch");
  }
}
