package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ACTIVITY_TYPE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ATTEMPT_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BATCH_GENERATED_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BATCH_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BATCH_INFO;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BUCKET_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.COMMENTS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_AMOUNT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_CODE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVER_EXCLUDED_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVER_REPORTED_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_STRATEGY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.GALACTIC_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.JOURNEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MODIFIED_AT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.NOT_REPORTED_REASON_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OTHER_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME_SUCCESS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.PROCESSING_COMPLETE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REASON_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECORD_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTED_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GENERATION_COMPLETED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORT_GROUP_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTING_TIMESTAMP_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RRA_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SEND_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SKIP_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SORT_TIMESTAMP;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_JOURNEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_RANK;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STAGE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STATUS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TOP_REASON_LIMIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SIDE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.UNSPECIFIED_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.journeyOutcome;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.matchesDigitsOnly;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Excluded/Not Reported, reached from the Transactions Overview dashboard tiles, are answered
 * from {@link #reportingRoll} -- the same "ever excluded"/"ever reported across full journey
 * history" definition the tile itself counted -- via {@link #reportingTarget} and {@link
 * #latestJourneyForTarget}, entirely independent of the per-batch evidence/merge pipeline every
 * other status still uses ({@link PeriodEvidenceQueries#evidenceForPeriod}/{@link
 * PeriodEvidenceQueries#filteredEvidenceForPeriod}). The two pipelines are deliberately not
 * shared: they answer genuinely different questions ("this batch's evidence rows" vs. "this
 * transaction's whole history"), and an earlier attempt to fold the roll-up into the per-batch
 * pipeline as an extra filter condition shipped a real bug -- a transaction reprocessed across
 * several batches surfaced once per batch instead of once, since the merge step groups by (batch,
 * identifier) while the roll-up is inherently per-identifier only.
 */
public class OverviewEvidenceQueries {
  private final DSLContext dsl;
  private final EvidencePaginator paginator;
  private final PeriodEvidenceQueries periodEvidenceQueries;

  public OverviewEvidenceQueries(DSLContext dsl, EvidencePaginator paginator, PeriodEvidenceQueries periodEvidenceQueries) {
    this.dsl = dsl;
    this.paginator = paginator;
    this.periodEvidenceQueries = periodEvidenceQueries;
  }

  /**
   * Rolls up every journey event (not just the latest-state row) per {@code (rpt_grp_id,
   * identifier)} in scope into {@code ever_excluded}/{@code ever_reported} booleans -- identical
   * logic and bucket definitions to {@code DashboardRepository#getTransactionOverview}, ported here
   * so the period-wide transaction list's Excluded/Not Reported filters match what the dashboard
   * tile they're clicked from actually counted, instead of the per-row latest-state definitions
   * {@link PeriodEvidenceQueries#filteredEvidenceForPeriod} otherwise uses for every other status
   * value.
   */
  private Table<?> reportingRoll(Table<?> batchScope) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);

    var batchEvidence = dsl
      .select(bsRptGrpId, bsBatchId,
          DSL
            .coalesce(BATCH_INFO.COMPILER_STATUS.eq(REPORT_GENERATION_COMPLETED).or(BATCH_INFO.REPORT_STATUS.in("ALL", "PARTIAL")), false)
            .as(BATCH_GENERATED_COLUMN))
      .from(batchScope)
      .leftJoin(BATCH_INFO)
      .on(BATCH_INFO.RPT_GRP_ID.eq(bsRptGrpId))
      .and(BATCH_INFO.BATCH_ID.eq(bsBatchId))
      .asTable("reporting_batch_evidence");

    Field<Integer> beRptGrpId = requiredField(batchEvidence, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> beBatchId = requiredField(batchEvidence, BATCH_ID_COLUMN, String.class);
    Field<Boolean> batchGenerated = requiredField(batchEvidence, BATCH_GENERATED_COLUMN, Boolean.class);

    Field<String> upperJourneyStatus = DSL.upper(DSL.coalesce(JOURNEY.STATUS, ""));
    Condition everExcludedCondition = upperJourneyStatus.in(VALUE_EXCLUDED, "EXCLUDED_SOFT_DEDUP");
    Condition everReportedCondition = JOURNEY.STAGE
      .eq("REPORT_GENERATION")
      .and(upperJourneyStatus.eq("GENERATED"))
      .or(JOURNEY.STAGE.eq("TRANSFORMATION").and(upperJourneyStatus.eq(OUTCOME_SUCCESS)).and(batchGenerated.isTrue()));
    // comments leads (falling back to skip_reason) because skip_reason is frequently a verbose,
    // per-record exception payload that embeds a record-specific index/path -- see
    // DashboardRepository#getTopExclusionReasons for why that defeats grouping; comments is
    // consistently a short, low-cardinality value instead.
    Field<String> exclusionReasonColumn = DSL.coalesce(JOURNEY.COMMENTS, JOURNEY.SKIP_REASON);
    // Same comments/skip_reason fallback as the exclusion reason column above, just taken across an
    // identifier's entire journey instead of only its EXCLUDED rows -- see
    // DashboardRepository#getNotReportedReasons for why (a not-reported identifier is never
    // excluded, so there's no status to condition this on).
    Field<String> notReportedReasonColumn = DSL.coalesce(JOURNEY.COMMENTS, JOURNEY.SKIP_REASON);

    return dsl
      .select(JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID_COLUMN), JOURNEY.IDENTIFIER, DSL
            .boolOr(everExcludedCondition)
            .as(EVER_EXCLUDED_COLUMN), DSL.boolOr(everReportedCondition).as(EVER_REPORTED_COLUMN),
          DSL.max(DSL.when(everExcludedCondition, exclusionReasonColumn)).as(REASON_COLUMN),
          DSL.max(notReportedReasonColumn).as(NOT_REPORTED_REASON_COLUMN))
      .from(JOURNEY)
      .join(batchEvidence)
      .on(beRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(beBatchId.eq(JOURNEY.BATCH_ID))
      .groupBy(JOURNEY.RPT_GRP_ID, JOURNEY.IDENTIFIER)
      .asTable("reporting_roll");
  }

  /**
   * The identifiers belonging to one {@link #reportingRoll} bucket -- already one row per {@code
   * (rpt_grp_id, identifier)} by construction (the roll itself is grouped that way), so this table's
   * own row count already answers "how many transactions are in this bucket" with no further
   * dedup needed. {@code reason} narrows further to the exact slice a dashboard breakdown legend row
   * represents -- a skip_reason/comments value for either status (see {@link #reportingRoll}'s
   * {@code REASON_COLUMN}/{@code NOT_REPORTED_REASON_COLUMN}), or the literal {@code "Other"} for
   * that card's catch-all row, matched via {@link #otherReasonCondition} against the same top-3
   * cutoff {@code DashboardRepository#topReasonsThenOther} used to build the card. Empty/null means
   * "no further narrowing," matching every other optional filter in this class.
   */
  private Table<?> reportingTarget(Table<?> roll, String status, String reason) {
    Field<Integer> rollRptGrpId = requiredField(roll, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> rollIdentifier = requiredField(roll, IDENTIFIER, String.class);
    Field<Boolean> everExcluded = requiredField(roll, EVER_EXCLUDED_COLUMN, Boolean.class);
    Field<Boolean> everReported = requiredField(roll, EVER_REPORTED_COLUMN, Boolean.class);
    Condition bucketCondition =
        VALUE_EXCLUDED.equals(status)
        ? everExcluded.isTrue().and(everReported.isFalse())
        : everReported.isFalse().and(everExcluded.isFalse());
    if (reason != null && !reason.isEmpty() && (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      String reasonColumnName = VALUE_EXCLUDED.equals(status) ? REASON_COLUMN : NOT_REPORTED_REASON_COLUMN;
      Field<String> rollReason = DSL.coalesce(requiredField(roll, reasonColumnName, String.class), DSL.inline(UNSPECIFIED_REASON));
      bucketCondition = bucketCondition.and(
          OTHER_REASON.equals(reason) ? otherReasonCondition(roll, rollReason, bucketCondition) : rollReason.eq(reason));
    }

    return dsl.select(rollRptGrpId, rollIdentifier).from(roll).where(bucketCondition).asTable("reporting_target");
  }

  /**
   * "Other" isn't one reason value -- it's every reason DashboardRepository#topReasonsThenOther
   *  didn't rank in its own top {@code TOP_REASON_LIMIT}. Reproducing that same ranking here (over
   *  the identical {@code roll}, scoped to the same {@code baseCondition} the caller already
   *  narrowed to EXCLUDED/NOT_REPORTED) keeps this "Other" click limited to exactly the rows the
   *  dashboard card's own "Other" count summed, without the two repositories sharing code.
   */
  private Condition otherReasonCondition(Table<?> roll, Field<String> rollReason, Condition baseCondition) {
    var topReasons = dsl
      .select(rollReason)
      .from(roll)
      .where(baseCondition)
      .groupBy(rollReason)
      .orderBy(DSL.count().desc(), rollReason)
      .limit(TOP_REASON_LIMIT);
    return rollReason.notIn(topReasons);
  }

  /**
   * One journey row per identifier in {@code target} -- its single most-recently-modified row,
   * regardless of which of that identifier's (possibly several) batches it came from. This is the
   * deliberate fix for the bug the per-batch merge pipeline has for these two statuses: {@code
   * ever_excluded}/{@code ever_reported} is computed across a transaction's *entire* batch history,
   * but the per-batch merge groups by {@code (evidence_batch_id, identifier)} -- so a
   * transaction that was reprocessed across N batches (exactly what a stuck "Not Reported"
   * transaction tends to do) would surface as N separate rows there, none of them collapsing,
   * wildly inflating the count relative to what the dashboard tile (correctly) counted once. Ranking
   * by identifier alone and taking the top row sidesteps the per-batch grain entirely -- there is
   * structurally only one output row per transaction, matching the tile's own definition exactly.
   */
  private Table<?> latestJourneyForTarget(Table<?> batchScope, Table<?> target) {
    Field<Integer> bsRptGrpId = requiredField(batchScope, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> bsBatchId = requiredField(batchScope, BATCH_ID_COLUMN, String.class);
    Field<Integer> targetRptGrpId = requiredField(target, REPORT_GROUP_ID_COLUMN, Integer.class);
    Field<String> targetIdentifier = requiredField(target, IDENTIFIER, String.class);

    Field<Integer> journeyRank = DSL
      .rowNumber()
      .over(DSL.partitionBy(JOURNEY.RPT_GRP_ID, JOURNEY.IDENTIFIER).orderBy(JOURNEY.MODIFIED_TIMESTAMP.desc().nullsLast()))
      .as(SOURCE_RANK);

    var ranked = dsl
      .select(DSL
            .concat(DSL.inline("JOURNEY:"), JOURNEY.RPT_GRP_ID, DSL.inline(":"), JOURNEY.BATCH_ID, DSL.inline(":"), JOURNEY.IDENTIFIER)
            .as(RECORD_KEY), JOURNEY.RPT_GRP_ID.as(REPORT_GROUP_ID_COLUMN), JOURNEY.IDENTIFIER.as(IDENTIFIER), JOURNEY.MTCN.as("mtcn"),
          JOURNEY.BATCH_ID.as(EVIDENCE_BATCH_ID), DSL.inline(SOURCE_JOURNEY).as(EVIDENCE_SOURCE), JOURNEY.STAGE.as(STAGE),
          JOURNEY.STATUS.as(STATUS), journeyOutcome(JOURNEY.STATUS).as(OUTCOME), JOURNEY.COMMENTS.as(COMMENTS),
          JOURNEY.SKIP_REASON.as(SKIP_REASON), DSL.cast(null, SQLDataType.CLOB).as(RULE_ID_COLUMN),
          DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_REASON), DSL.cast(null, SQLDataType.CLOB).as(EXCLUSION_STRATEGY),
          DSL.cast(null, SQLDataType.CLOB).as(REPORTED_BATCH_ID),
          JOURNEY.REPORTING_TIMESTAMP_LATEST.cast(SQLDataType.CLOB).as(REPORTING_TIMESTAMP_COLUMN),
          JOURNEY.MODIFIED_TIMESTAMP.cast(SQLDataType.CLOB).as(MODIFIED_AT), JOURNEY.MODIFIED_TIMESTAMP.as(SORT_TIMESTAMP),
          JOURNEY.PROCESSING_COMPLETE.as(PROCESSING_COMPLETE), DSL.cast(null, SQLDataType.DOUBLE).as(CURRENCY_AMOUNT),
          DSL.cast(null, SQLDataType.CLOB).as(CURRENCY_CODE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SIDE), DSL.cast(null, SQLDataType.CLOB).as(TRANSACTION_SOURCE),
          DSL.cast(null, SQLDataType.CLOB).as(ACTIVITY_TYPE), DSL.cast(null, SQLDataType.CLOB).as(SEND_DATE),
          DSL.cast(null, SQLDataType.CLOB).as(GALACTIC_ID), DSL.cast(null, SQLDataType.INTEGER).as(BUCKET_ID_COLUMN),
          DSL.cast(null, SQLDataType.BIGINT).as(ATTEMPT_ID_COLUMN),
          DSL.when(matchesDigitsOnly(JOURNEY.IDENTIFIER), JOURNEY.IDENTIFIER.cast(SQLDataType.BIGINT)).as(RRA_KEY), journeyRank)
      .from(JOURNEY)
      .join(batchScope)
      .on(bsRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(bsBatchId.eq(JOURNEY.BATCH_ID))
      .join(target)
      .on(targetRptGrpId.eq(JOURNEY.RPT_GRP_ID))
      .and(targetIdentifier.eq(JOURNEY.IDENTIFIER))
      .asTable("ranked_journey");

    Field<Integer> rank = requiredField(ranked, SOURCE_RANK, Integer.class);
    return dsl.select(ranked.fields()).from(ranked).where(rank.eq(1)).asTable("latest_journey");
  }

  /**
   * {@code filtered} here is already effectively one row per identifier ({@code
   * latestJourneyForTarget} already ranked to exactly one), so {@link
   * EvidencePaginator#pageEvidence}'s Pass 2 merge is a no-op in substance (nothing to collapse) --
   * but reusing it rather than a separate single-pass helper is what gives this path real
   * cursor-pagination support for free, instead of a client's cursor being silently ignored
   * whenever the requested status happens to route here.
   */
  public EvidencePage findOverviewEvidenceRecords(Table<?> batchScope, String status, String reason, String search, String outcome,
      String sortDirection, int size, long offset, EvidenceCursor cursor) {
    var roll = reportingRoll(batchScope);
    var target = reportingTarget(roll, status, reason);
    var latest = latestJourneyForTarget(batchScope, target);
    var filtered = periodEvidenceQueries.filteredEvidenceForPeriod(latest, search, outcome, "ALL");
    var ruleHitMatches = periodEvidenceQueries.ruleHitMatchesForPeriod(batchScope, status);
    return paginator.pageEvidence(filtered, ruleHitMatches, sortDirection, size, offset, cursor);
  }

  public long countOverviewEvidenceRecords(Table<?> batchScope, String status, String reason, String search, String outcome) {
    var roll = reportingRoll(batchScope);
    var target = reportingTarget(roll, status, reason);
    if (search.isEmpty() && "ALL".equals(outcome)) {
      return dsl.selectCount().from(target).fetchOne(0, Long.class);
    }
    var latest = latestJourneyForTarget(batchScope, target);
    var filtered = periodEvidenceQueries.filteredEvidenceForPeriod(latest, search, outcome, "ALL");
    return paginator.countDistinctIdentifiers(filtered);
  }
}
