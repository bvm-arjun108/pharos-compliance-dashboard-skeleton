package com.pharos.compliance.common.jooq;

import static com.pharos.compliance.jooq.tables.RecordTransformationJourney.RECORD_TRANSFORMATION_JOURNEY;
import com.pharos.compliance.jooq.tables.RecordTransformationJourney;
import java.util.Set;
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
  private static final String STAGE_TRANSFORMATION = "TRANSFORMATION";
  private static final Set<String> FAILURE_STATUSES = Set.of("ERROR", "FAILED", "FAILURE");
  public static final String JOURNEY_TRANSFORMATION_FAILURES_COLUMN = "journey_transformation_failures";

  private TransformationFailureQueries() {
  }

  /**
   * Correlated scalar subquery for single-batch queries (e.g. Batch Explorer's details view,
   * Transaction Report's context) -- {@code rptGrpId}/{@code batchId} are the outer query's own
   * columns.
   */
  public static Field<Long> correlatedJourneyFailureCount(DSLContext dsl, Field<Integer> rptGrpId, Field<String> batchId) {
    return DSL.field(dsl
      .select(DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT))
      .from(JOURNEY)
      .where(JOURNEY.RPT_GRP_ID.eq(rptGrpId))
      .and(JOURNEY.BATCH_ID.eq(batchId))
      .and(DSL.upper(JOURNEY.STAGE).eq(STAGE_TRANSFORMATION))
      .and(DSL.upper(JOURNEY.STATUS).in(FAILURE_STATUSES)));
  }

  /**
   * Per-batch grouped subquery for list/aggregate queries (Batch Explorer's queue, Dashboard's
   * headline counts and trend) -- callers {@code LEFT JOIN} this on {@code (rpt_grp_id, batch_id)}
   * and {@code COALESCE} the count to 0 for batches with no failures. Exposes {@link
   * #JOURNEY_TRANSFORMATION_FAILURES_COLUMN}.
   */
  public static Table<?> journeyFailuresByBatch(DSLContext dsl) {
    return dsl
      .select(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID,
          DSL.countDistinct(JOURNEY.IDENTIFIER).cast(SQLDataType.BIGINT).as(JOURNEY_TRANSFORMATION_FAILURES_COLUMN))
      .from(JOURNEY)
      .where(DSL.upper(JOURNEY.STAGE).eq(STAGE_TRANSFORMATION))
      .and(DSL.upper(JOURNEY.STATUS).in(FAILURE_STATUSES))
      .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.BATCH_ID)
      .asTable("journey_failures_by_batch");
  }
}
