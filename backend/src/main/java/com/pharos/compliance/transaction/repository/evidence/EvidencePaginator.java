package com.pharos.compliance.transaction.repository.evidence;

import static com.pharos.compliance.common.jooq.JooqFields.requiredField;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ACTIVITY_TYPE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ATTEMPT_ID_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.ATTEMPT_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BATCH_ID_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BUCKET_ID_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.BUCKET_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.COMMENTS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_AMOUNT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.CURRENCY_CODE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EVIDENCE_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.EXCLUSION_STRATEGY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.GALACTIC_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.IS_REPORTED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MATCHED_IDENTIFIER;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MERGE_COLUMNS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MERGE_SOURCE_RANK;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.MODIFIED_AT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.OUTCOME;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.PROCESSING_COMPLETE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RECORD_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTED_BATCH_ID;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTING_TIMESTAMP;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.REPORTING_TIMESTAMP_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RRA;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RRA_KEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_ID_ALIAS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.RULE_ID_COLUMN;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SEND_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SKIP_REASON;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SORT_TIMESTAMP;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_EXCLUSION_AUDIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_JOURNEY;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.SOURCE_RULE_HIT;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STAGE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.STATUS;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_DATE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SIDE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.TRANSACTION_SOURCE;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.mergeColumnType;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceSqlSupport.firstNonNullByRank;
import com.pharos.compliance.common.jooq.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * The two-pass pagination + merge engine shared by every evidence pipeline in this package
 * ({@link BatchEvidenceQueries}, {@link PeriodEvidenceQueries}, {@link OverviewEvidenceQueries}),
 * entirely independent of *where* the evidence rows came from. {@link #pageEvidence} is the single
 * entry point: cheap Pass 1 ({@link #identifierSortKeys}) picks the page's identifiers, paying the
 * page window's cost rather than the whole filtered result's; Pass 2 ({@link #mergeForPageKeys})
 * runs the full 27-column priority merge bounded to just those identifiers; then {@link
 * #selectFinalPage} re-applies sort order and attaches the per-row rule-hit rollup and {@code
 * reg_reportable_activity} enrichment.
 */
public class EvidencePaginator {
  private final DSLContext dsl;

  public EvidencePaginator(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Attaches the EXCLUSION_AUDIT &gt; RULE_HIT &gt; JOURNEY priority rank used everywhere a
   * transaction's evidence needs to be collapsed to one row -- shared foundation for both {@link
   * #identifierSortKeys} (a cheap 2-column-per-identifier pass used to paginate) and {@link
   * #mergeForPageKeys} (the full per-column merge, run only for the identifiers a page actually
   * needs), so both agree on exactly the same "which row wins" priority.
   */
  private Table<?> rankedEvidence(Table<?> filteredEvidence) {
    Field<String> evidenceSource = requiredField(filteredEvidence, EVIDENCE_SOURCE, String.class);
    Field<Integer> sourceRank =
        DSL
      .when(evidenceSource.eq(SOURCE_EXCLUSION_AUDIT), 1)
      .when(evidenceSource.eq(SOURCE_RULE_HIT), 2)
      .otherwise(3)
      .as(MERGE_SOURCE_RANK);
    return dsl.select(filteredEvidence.fields()).select(sourceRank).from(filteredEvidence).asTable("ranked");
  }

  /**
   * Pass 1 of paginating merged evidence: one row per (evidence_batch_id, identifier), but with
   * only the two columns pagination actually needs -- the winning row's sort_ts and record_key,
   * by the same source-rank priority the full merge uses. Computing only these two columns here
   * means the page window (offset or cursor, see {@link #fetchPageKeys}) applies *before* the
   * expensive 27-column {@code ARRAY_AGG(...).filterWhere(...)} merge runs, not after -- so {@link
   * #mergeForPageKeys} only ever does that work for the identifiers actually on the requested page,
   * not for every identifier matching the current filter. Previously the single-pass
   * {@code mergedEvidence()} ran that full merge for the whole filtered result before any
   * LIMIT/OFFSET applied, so page 1 and page 400 cost exactly the same -- the dominant reason a
   * big batch or wide date range "took forever" regardless of which page was requested.
   */
  private Table<?> identifierSortKeys(Table<?> ranked) {
    Field<String> rEvidenceBatchId = requiredField(ranked, EVIDENCE_BATCH_ID, String.class);
    Field<String> rIdentifier = requiredField(ranked, IDENTIFIER, String.class);
    Field<Integer> rSourceRank = requiredField(ranked, MERGE_SOURCE_RANK, Integer.class);
    Field<String> rRecordKey = requiredField(ranked, RECORD_KEY, String.class);

    Field<OffsetDateTime> sortTs = firstNonNullByRank(ranked, SORT_TIMESTAMP, OffsetDateTime.class, rSourceRank, rRecordKey);
    Field<String> recordKey = firstNonNullByRank(ranked, RECORD_KEY, String.class, rSourceRank, rRecordKey);

    return dsl
      .select(rEvidenceBatchId, rIdentifier, sortTs, recordKey)
      .from(ranked)
      .groupBy(rEvidenceBatchId, rIdentifier)
      .asTable("identifier_sort_keys");
  }

  /**
   * One (evidence_batch_id, identifier)'s winning sort key, as fetched by {@link #fetchPageKeys}.
   */
  private record PageKey(String evidenceBatchId, String identifier, OffsetDateTime sortTs, String recordKey) {}

  /**
   * Applies the requested page window to {@code sortKeys} (see {@link #identifierSortKeys}) and
   * materializes exactly the winning keys as a Java list -- needed up front (rather than staying
   * in SQL) so {@link #nextCursorAfter} can compute the next page's cursor from the last key
   * actually returned, and so {@link #mergeForPageKeys} has a concrete key list to restrict Pass 2
   * to.
   *
   * <p>Offset mode (default, {@code cursor == null}) is for the page-number paginator's arbitrary
   * page-jump UI; cursor mode (opaque {@code (sortTs, recordKey)} keyset) is for cheap sequential
   * "next page" access that never pays an O(offset) skip cost, at the price of not being able to
   * jump to an arbitrary page number -- exactly the hybrid the two access patterns each need.
   *
   * <p>Fetches one extra "peek" row beyond {@code size} in both modes, purely to know whether a
   * next page exists -- the standard fetch-N+1 technique, since neither mode can otherwise answer
   * that without a second query (cursor mode deliberately has no count query to fall back on).
   */
  @SqlQueryPurpose("Transaction list > Select page identifiers and one look-ahead row for next-page availability")
  private List<PageKey> fetchPageKeys(Table<?> sortKeys, String sortDirection, int size, long offset, EvidenceCursor cursor) {
    Field<String> skEvidenceBatchId = requiredField(sortKeys, EVIDENCE_BATCH_ID, String.class);
    Field<String> skIdentifier = requiredField(sortKeys, IDENTIFIER, String.class);
    Field<OffsetDateTime> skSortTs = requiredField(sortKeys, SORT_TIMESTAMP, OffsetDateTime.class);
    Field<String> skRecordKey = requiredField(sortKeys, RECORD_KEY, String.class);
    Condition condition = cursorCondition(cursor, sortDirection, skSortTs, skRecordKey);
    List<OrderField<?>> order = evidenceOrder(sortDirection, skSortTs, skRecordKey);

    var result = cursor != null
        ? dsl
      .select(skEvidenceBatchId, skIdentifier, skSortTs, skRecordKey)
      .from(sortKeys)
      .where(condition)
      .orderBy(order)
      .limit(size + 1)
      .fetch()
        : dsl
      .select(skEvidenceBatchId, skIdentifier, skSortTs, skRecordKey)
      .from(sortKeys)
      .where(condition)
      .orderBy(order)
      .limit(size + 1)
      .offset(offset)
      .fetch();

    return result.map(r -> new PageKey(r.get(skEvidenceBatchId), r.get(skIdentifier), r.get(skSortTs), r.get(skRecordKey)));
  }

  /**
   * Splits a {@link #fetchPageKeys} result (up to {@code size + 1} rows) into the page actually
   * returned to the caller plus the cursor for the next one, {@code null} once exhausted.
   */
  private EvidencePageKeys splitPage(List<PageKey> keysWithPeek, int size) {
    if (keysWithPeek.size() <= size) {
      return new EvidencePageKeys(keysWithPeek, null);
    }
    PageKey lastOnPage = keysWithPeek.get(size - 1);
    return new EvidencePageKeys(keysWithPeek.subList(0, size), EvidenceCursor.encode(lastOnPage.sortTs(), lastOnPage.recordKey()));
  }

  private record EvidencePageKeys(List<PageKey> keys, String nextCursor) {}

  /**
   * Pass 2: restricts {@code ranked} to exactly the (evidence_batch_id, identifier) pairs in
   * {@code pageKeys} -- {@code size} rows at most, never the whole filtered result -- then runs
   * the identical 27-column merge the old single-pass {@code mergedEvidence()} ran over
   * everything. Returns an empty, correctly-shaped-but-never-queried result for an empty page
   * rather than emitting {@code WHERE ... IN ()}, which is either invalid or trivially-false SQL
   * depending on dialect.
   */
  private Table<?> mergeForPageKeys(Table<?> ranked, List<PageKey> pageKeys) {
    Field<String> rEvidenceBatchId = requiredField(ranked, EVIDENCE_BATCH_ID, String.class);
    Field<String> rIdentifier = requiredField(ranked, IDENTIFIER, String.class);
    Field<Integer> rSourceRank = requiredField(ranked, MERGE_SOURCE_RANK, Integer.class);
    Field<String> rRecordKey = requiredField(ranked, RECORD_KEY, String.class);

    List<Field<?>> selectList = new ArrayList<>();
    selectList.add(rEvidenceBatchId);
    selectList.add(rIdentifier);
    if (ranked.field("rpt_grp_id") != null) {
      selectList.add(firstNonNullByRank(ranked, "rpt_grp_id", Integer.class, rSourceRank, rRecordKey));
    }
    for (String column : MERGE_COLUMNS) {
      Class<?> type = mergeColumnType(column);
      selectList.add(firstNonNullByRank(ranked, column, type, rSourceRank, rRecordKey));
    }
    selectList.add(DSL.min(rSourceRank).as(MERGE_SOURCE_RANK));

    Condition keyCondition = pageKeys.isEmpty()
        ? DSL.falseCondition()
        : DSL
      .row(rEvidenceBatchId, rIdentifier)
      .in(pageKeys
        .stream()
        .map(key -> DSL.row(key.evidenceBatchId(), key.identifier()))
        .toList());

    return dsl.select(selectList).from(ranked).where(keyCondition).groupBy(rEvidenceBatchId, rIdentifier).asTable("merged");
  }

  /**
   * Keyset predicate mirroring {@link #evidenceOrder}'s own tiebreak exactly: strictly past the
   * cursor row in whichever direction results are sorted. {@code sort_ts} is populated straight
   * from {@code modified_timestamp} on every one of the three evidence branches (see each
   * pipeline's own {@code evidenceFor...} builder) and is never null in practice; a null would
   * simply be excluded going forward rather than corrupt ordering, since SQL's three-valued logic
   * makes a NULL comparison here false either way.
   *
   * <p>{@code evidenceOrder} sorts by {@code sort_ts} in the requested direction but {@code
   * record_key} *always* ascending, as a pure tiebreak -- e.g. DESC order is {@code sort_ts DESC,
   * record_key ASC}. A single row-value comparison ({@code ROW(sortTs, recordKey) < ROW(...)})
   * applies the *same* direction to both columns, which is only correct when both are ascending;
   * for DESC it silently drops every row tied with the cursor on {@code sort_ts} but sorted after
   * it by the ascending tiebreak. Decomposing into an explicit OR handles the mixed directions
   * correctly in both cases.
   */
  private Condition cursorCondition(EvidenceCursor cursor, String sortDirection, Field<OffsetDateTime> sortTs, Field<String> recordKey) {
    if (cursor == null) {
      return DSL.trueCondition();
    }
    var cursorSortTs = DSL.val(cursor.sortTs());
    var cursorRecordKey = DSL.val(cursor.recordKey());
    Condition tieBreak = sortTs.eq(cursorSortTs).and(recordKey.gt(cursorRecordKey));
    return "ASC".equals(sortDirection) ? sortTs.gt(cursorSortTs).or(tieBreak) : sortTs.lt(cursorSortTs).or(tieBreak);
  }

  /**
   * Finishes a two-pass page (see {@link #fetchPageKeys}/{@link #mergeForPageKeys}): {@code
   * merged} is already exactly the page's rows (at most {@code size}, restricted by key up front),
   * so this only re-applies the sort order -- a join followed by {@code GROUP BY} doesn't guarantee
   * row order -- and runs the LATERAL rule_hit rollup + {@code reg_reportable_activity} join,
   * unchanged from the original single-pass design and already scoped to just this page.
   */
  @SqlQueryPurpose("Transaction list > Load complete evidence and enrichment for the selected page identifiers")
  private List<TransactionEvidenceProjection> selectFinalPage(Table<?> merged, Table<?> ruleHitMatches, String sortDirection) {
    return selectEvidenceProjection(merged, ruleHitMatches)
      .orderBy(
          evidenceOrder(sortDirection, requiredField(merged, SORT_TIMESTAMP, OffsetDateTime.class),
              requiredField(merged, RECORD_KEY, String.class)))
      .fetch(EvidencePaginator::toEvidenceProjection);
  }

  /**
   * The full two-pass pagination flow shared by the batch-scoped and period-scoped evidence
   * queries: cheap Pass 1 to pick the page's identifiers (paying the page window's cost, not the
   * whole result's), then Pass 2's full merge bounded to just those identifiers, then the existing
   * per-page rule_hit rollup.
   *
   * <p>{@code ruleHitBridge} feeds {@link #rollupRuleHits} and <strong>nothing else</strong> -- it
   * is the "Rule Hit Details" enrichment source, never evidence rows (callers build the union
   * branch themselves, before calling this). It must therefore be a status/metric-independent
   * match: pass {@code ruleHitMatchesFor*Enrichment}, not the short-circuited table the union
   * branch uses. Passing the latter is silent -- the page still renders, every expanded row just
   * claims the transaction has no rule hits.
   *
   * <p>It arrives as a function rather than a table because the identifiers to bound it to are not
   * known until Pass 1 has chosen the page. Building it eagerly across the whole window and then
   * correlating was what made it the most expensive part of this query; applied here it is built
   * once, already narrowed to the rows being rendered.
   */
  public EvidencePage pageEvidence(Table<?> filtered, Function<Collection<String>, Table<?>> ruleHitBridge, String sortDirection, int size,
      long offset, EvidenceCursor cursor, EvidenceProjection projection) {
    var ranked = rankedEvidence(filtered);
    var sortKeys = identifierSortKeys(ranked);
    if (projection.identifier() != null) {
      var exact = requiredField(sortKeys, IDENTIFIER, String.class)
        .eq(projection.identifier())
        .and(requiredField(sortKeys, EVIDENCE_BATCH_ID, String.class).eq(projection.batchId()));
      if (projection.recordKey() != null && !projection.recordKey().isBlank()) {
        exact = exact.and(requiredField(sortKeys, RECORD_KEY, String.class).eq(projection.recordKey()));
      }
      sortKeys = dsl.select(sortKeys.fields()).from(sortKeys).where(exact).asTable("exact_detail_keys");
    }
    var keysWithPeek = fetchPageKeys(sortKeys, sortDirection, size, offset, cursor);
    var page = splitPage(keysWithPeek, size);
    if (page.keys().isEmpty()) {
      return new EvidencePage(List.of(), null);
    }
    var merged = mergeForPageKeys(ranked, page.keys());
    if (!projection.details()) {
      // Nothing in the list projection reads rule hits, so the bridge is not built at all here.
      return new EvidencePage(selectListPage(merged, sortDirection), page.nextCursor());
    }
    List<String> pageIdentifiers = page.keys().stream().map(PageKey::identifier).distinct().toList();
    return new EvidencePage(selectFinalPage(merged, ruleHitBridge.apply(pageIdentifiers), sortDirection), page.nextCursor());
  }

  /**
   * {@code COUNT(DISTINCT (evidence_batch_id, identifier))} over an already-filtered evidence
   * table -- the "how many transactions match" companion to {@link #pageEvidence}, shared by all
   * three pipelines' own count methods.
   */
  /**
   * Counts distinct {@code (evidence_batch_id, identifier)} tuples, not distinct identifiers --
   * deliberately a per-batch-decision count, not a per-transaction one. This is the grain every
   * batch-scalar reconciliation aggregate this evidence gets compared against actually has: {@code
   * SUM(report_transformation_reconciliation.excluded_txn)} (and its siblings) is a sum of
   * per-batch scalars, so it can never be identity-deduplicated across batches -- only the
   * per-(batch, identifier) count can match it. Deduplicating by identifier alone would undercount
   * relative to that sum for any transaction re-excluded (or re-whatever) in more than one batch
   * within the same scope; this currently produces the same number as identifier-only dedup would
   * for most windows simply because that overlap is rare, not because the two are equivalent -- a
   * real sample had 40 rule-excluded rows collapse to just 10 distinct transactions recurring
   * across 4 batches, a gap a narrower comment filter elsewhere happened to hide for that
   * particular window, not fix in general.
   */
  public long countDistinctIdentifiers(Table<?> filtered) {
    Field<String> evidenceBatchId = requiredField(filtered, EVIDENCE_BATCH_ID, String.class);
    Field<String> identifier = requiredField(filtered, IDENTIFIER, String.class);
    Long count = dsl.select(DSL.countDistinct(DSL.row(evidenceBatchId, identifier))).from(filtered).fetchOne(0, Long.class);
    return count == null ? 0L : count;
  }

  private static List<OrderField<?>> evidenceOrder(String sortDirection, Field<OffsetDateTime> sortTs, Field<String> recordKey) {
    List<OrderField<?>> order = new ArrayList<>();
    order.add("ASC".equals(sortDirection) ? sortTs.asc().nullsLast() : sortTs.desc().nullsLast());
    order.add(recordKey.asc());
    return order;
  }

  /**
   * The columns the table renders, and nothing else. No {@code reg_reportable_activity} join and no
   * rule-hit lateral, so a page load neither pays for them nor returns personal data for rows
   * nobody opened -- the detail request fetches those per transaction instead.
   */
  @SqlQueryPurpose("Transaction list > Load the columns the table renders for the selected page identifiers")
  private List<TransactionEvidenceProjection> selectListPage(Table<?> page, String sortDirection) {
    return dsl
      .select(requiredField(page, RECORD_KEY, String.class).as("recordKey"), requiredField(page, IDENTIFIER, String.class).as(IDENTIFIER),
          requiredField(page, "mtcn", String.class).as("mtcn"), requiredField(page, EVIDENCE_BATCH_ID, String.class).as(BATCH_ID_ALIAS),
          requiredField(page, EVIDENCE_SOURCE, String.class).as("evidenceSource"), requiredField(page, STAGE, String.class).as(STAGE),
          requiredField(page, STATUS, String.class).as(STATUS), requiredField(page, OUTCOME, String.class).as(OUTCOME),
          requiredField(page, COMMENTS, String.class).as(COMMENTS), requiredField(page, SKIP_REASON, String.class).as("skipReason"),
          requiredField(page, EXCLUSION_REASON, String.class).as("exclusionReason"),
          requiredField(page, REPORTED_BATCH_ID, String.class).as("reportedBatchId"),
          requiredField(page, MODIFIED_AT, String.class).as("modifiedAt"),
          requiredField(page, PROCESSING_COMPLETE, Boolean.class).as("processingComplete"))
      .from(page)
      .orderBy(
          evidenceOrder(sortDirection, requiredField(page, SORT_TIMESTAMP, OffsetDateTime.class),
              requiredField(page, RECORD_KEY, String.class)))
      .fetch(EvidencePaginator::toListProjection);
  }

  /**
   * Detail-only columns are absent from the list query, so they are null here by construction.
   */
  private static TransactionEvidenceProjection toListProjection(Record r) {
    return new TransactionEvidenceProjection(r.get("recordKey", String.class), r.get(IDENTIFIER, String.class), r.get("mtcn", String.class),
        r.get(BATCH_ID_ALIAS, String.class), r.get("evidenceSource", String.class), r.get(STAGE, String.class), r.get(STATUS, String.class),
        r.get(OUTCOME, String.class), r.get(COMMENTS, String.class), r.get("skipReason", String.class), null,
        r.get("exclusionReason", String.class), null, r.get("reportedBatchId", String.class), null, r.get("modifiedAt", String.class),
        r.get("processingComplete", Boolean.class), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private org.jooq.SelectConditionStep<Record> selectEvidenceProjection(Table<?> page, Table<?> ruleHitMatches) {
    Field<String> pIdentifier = requiredField(page, IDENTIFIER, String.class);
    Field<String> pEvidenceSource = requiredField(page, EVIDENCE_SOURCE, String.class);
    Field<Long> pRraKey = requiredField(page, RRA_KEY, Long.class);
    Field<Double> pCurrencyAmount = requiredField(page, CURRENCY_AMOUNT, Double.class);
    Field<String> pCurrencyCode = requiredField(page, CURRENCY_CODE, String.class);
    Field<String> pTransactionDate = requiredField(page, TRANSACTION_DATE, String.class);
    Field<String> pSendDate = requiredField(page, SEND_DATE, String.class);

    Field<Double> currencyAmountOut = DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_LOCAL_PRINCIPAL, RRA.R_LOCAL_PRINCIPAL))
      .otherwise(pCurrencyAmount)
      .as("currencyAmount");
    Field<String> currencyCodeOut =
        DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_CURRENCY, RRA.R_CURRENCY))
      .otherwise(pCurrencyCode)
      .as("currencyCode");
    Field<String> transactionDateOut =
        DSL
      .when(pEvidenceSource.eq(SOURCE_JOURNEY), DSL.coalesce(RRA.S_DATE, RRA.R_DATE))
      .otherwise(pTransactionDate)
      .as("transactionDate");
    Field<String> sendDateOut = DSL.when(pEvidenceSource.eq(SOURCE_JOURNEY), RRA.GROUP_SEND_DATE).otherwise(pSendDate).as("sendDate");

    var rollupRuleHits = rollupRuleHits(ruleHitMatches, pIdentifier, page.field("rpt_grp_id", Integer.class));

    return dsl
      .select(requiredField(page, RECORD_KEY, String.class).as("recordKey"), pIdentifier.as(IDENTIFIER),
          requiredField(page, "mtcn", String.class).as("mtcn"), requiredField(page, EVIDENCE_BATCH_ID, String.class).as(BATCH_ID_ALIAS),
          pEvidenceSource.as("evidenceSource"), requiredField(page, STAGE, String.class).as(STAGE),
          requiredField(page, STATUS, String.class).as(STATUS), requiredField(page, OUTCOME, String.class).as(OUTCOME),
          requiredField(page, COMMENTS, String.class).as(COMMENTS), requiredField(page, SKIP_REASON, String.class).as("skipReason"),
          requiredField(page, RULE_ID_COLUMN, String.class).as(RULE_ID_ALIAS),
          requiredField(page, EXCLUSION_REASON, String.class).as("exclusionReason"),
          requiredField(page, EXCLUSION_STRATEGY, String.class).as("exclusionStrategy"),
          requiredField(page, REPORTED_BATCH_ID, String.class).as("reportedBatchId"),
          requiredField(page, REPORTING_TIMESTAMP_COLUMN, String.class).as(REPORTING_TIMESTAMP),
          requiredField(page, MODIFIED_AT, String.class).as("modifiedAt"),
          requiredField(page, PROCESSING_COMPLETE, Boolean.class).as("processingComplete"), currencyAmountOut, currencyCodeOut,
          transactionDateOut, requiredField(page, TRANSACTION_SIDE, String.class).as("transactionSide"),
          requiredField(page, TRANSACTION_SOURCE, String.class).as("txnSource"),
          requiredField(page, ACTIVITY_TYPE, String.class).as("activityType"), sendDateOut,
          requiredField(page, GALACTIC_ID, String.class).as("galacticId"),
          requiredField(page, BUCKET_ID_COLUMN, Integer.class).as(BUCKET_ID_ALIAS),
          requiredField(page, ATTEMPT_ID_COLUMN, Long.class).as(ATTEMPT_ID_ALIAS), RRA.S_PARTY_NAME.as("senderName"),
          RRA.R_PARTY_NAME.as("receiverName"), RRA.S_PARTY_CITY.as("senderCity"), RRA.S_PARTY_COUNTRY_OF_RESIDENCE.as("senderCountry"),
          RRA.S_PARTY_PHONE_NUMBER.as("senderPhone"), RRA.S_PARTY_DATE_OF_BIRTH.as("senderDateOfBirth"),
          RRA.S_PARTY_ID_TYPE.as("senderIdType"), RRA.S_PARTY_ID_NUMBER.as("senderIdNumber"), RRA.R_PARTY_CITY.as("receiverCity"),
          RRA.R_PARTY_COUNTRY_OF_RESIDENCE.as("receiverCountry"), RRA.R_PARTY_PHONE_NUMBER.as("receiverPhone"),
          RRA.R_PARTY_DATE_OF_BIRTH.as("receiverDateOfBirth"), RRA.R_PARTY_ID_TYPE.as("receiverIdType"),
          RRA.R_PARTY_ID_NUMBER.as("receiverIdNumber"), RRA.TXN_STATUS.as("transactionStatus"), RRA.SUB_STATUS.as("transactionSubStatus"),
          DSL.coalesce(rollupRuleHits, DSL.inline("[]")).as("ruleHitsJson"))
      .from(page)
      .leftJoin(RRA)
      .on(RRA.TXN_SUR_KEY.eq(pRraKey))
      .where(DSL.trueCondition());
  }

  /**
   * {@code LEFT JOIN LATERAL (SELECT json_agg(json_build_object(...)) ...) rollup ON TRUE}.
   */
  private Field<String> rollupRuleHits(Table<?> ruleHitMatches, Field<String> identifier, Field<Integer> reportGroupId) {
    Field<String> rhmMatchedIdentifier = requiredField(ruleHitMatches, MATCHED_IDENTIFIER, String.class);
    Field<String> rhmRuleId = requiredField(ruleHitMatches, RULE_ID_COLUMN, String.class);
    Field<Boolean> rhmIsReported = requiredField(ruleHitMatches, IS_REPORTED, Boolean.class);
    Field<LocalDateTime> rhmReportingTimestamp = requiredField(ruleHitMatches, REPORTING_TIMESTAMP_COLUMN, LocalDateTime.class);
    Field<Integer> rhmBucketId = requiredField(ruleHitMatches, BUCKET_ID_COLUMN, Integer.class);
    Field<Long> rhmAttemptId = requiredField(ruleHitMatches, ATTEMPT_ID_COLUMN, Long.class);

    var rollup = DSL
      .lateral(dsl
        .select(DSL
          .jsonArrayAgg(DSL.jsonObject(DSL.jsonEntry(RULE_ID_ALIAS, rhmRuleId), DSL.jsonEntry("isReported", rhmIsReported),
              DSL.jsonEntry(REPORTING_TIMESTAMP, rhmReportingTimestamp.cast(SQLDataType.CLOB)), DSL.jsonEntry(BUCKET_ID_ALIAS, rhmBucketId),
              DSL.jsonEntry(ATTEMPT_ID_ALIAS, rhmAttemptId)))
          .orderBy(rhmRuleId)
          .cast(SQLDataType.CLOB)
          .as("rule_hits_json"))
        .from(ruleHitMatches)
        .where(rhmMatchedIdentifier.isNotNull())
        .and(rhmMatchedIdentifier.eq(identifier))
        .and(reportGroupId == null ? DSL.trueCondition() : requiredField(ruleHitMatches, "rpt_grp_id", Integer.class).eq(reportGroupId)))
      .asTable("rollup");

    return DSL.field(dsl.select(requiredField(rollup, "rule_hits_json", String.class)).from(rollup));
  }

  private static TransactionEvidenceProjection toEvidenceProjection(Record r) {
    Double currencyAmount = r.get("currencyAmount", Double.class);
    return new TransactionEvidenceProjection(r.get("recordKey", String.class), r.get(IDENTIFIER, String.class), r.get("mtcn", String.class),
        r.get(BATCH_ID_ALIAS, String.class), r.get("evidenceSource", String.class), r.get(STAGE, String.class), r.get(STATUS, String.class),
        r.get(OUTCOME, String.class), r.get(COMMENTS, String.class), r.get("skipReason", String.class), r.get(RULE_ID_ALIAS, String.class),
        r.get("exclusionReason", String.class), r.get("exclusionStrategy", String.class), r.get("reportedBatchId", String.class),
        r.get(REPORTING_TIMESTAMP, String.class), r.get("modifiedAt", String.class), r.get("processingComplete", Boolean.class),
        currencyAmount == null ? null : BigDecimal.valueOf(currencyAmount), r.get("currencyCode", String.class),
        r.get("transactionDate", String.class), r.get("transactionSide", String.class), r.get("txnSource", String.class),
        r.get("activityType", String.class), r.get("sendDate", String.class), r.get("galacticId", String.class),
        r.get(BUCKET_ID_ALIAS, Integer.class), r.get(ATTEMPT_ID_ALIAS, Long.class), r.get("senderName", String.class),
        r.get("receiverName", String.class), r.get("senderCity", String.class), r.get("senderCountry", String.class),
        r.get("senderPhone", String.class), r.get("senderDateOfBirth", String.class), r.get("senderIdType", String.class),
        r.get("senderIdNumber", String.class), r.get("receiverCity", String.class), r.get("receiverCountry", String.class),
        r.get("receiverPhone", String.class), r.get("receiverDateOfBirth", String.class), r.get("receiverIdType", String.class),
        r.get("receiverIdNumber", String.class), r.get("transactionStatus", String.class), r.get("transactionSubStatus", String.class),
        r.get("ruleHitsJson", String.class));
  }
}
