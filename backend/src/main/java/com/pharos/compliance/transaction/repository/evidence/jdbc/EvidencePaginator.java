package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceColumns;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;

/**
 * The two-pass pagination + 27-column priority merge engine shared by every evidence pipeline in
 * this package.
 *
 * <p>{@link #pageEvidence} is the single entry point: cheap Pass 1 ({@code identifier_sort_keys})
 * picks the page's identifiers via its own real JDBC round trip (its result determines Pass 2's
 * tuple-IN restriction, so it cannot be deferred into a single combined statement); Pass 2 (the
 * {@code merged} CTE) runs the full 27-column priority merge bounded to just those identifiers;
 * then the DETAIL/LIST final select re-applies sort order and attaches the per-row rule-hit
 * rollup and {@code reg_reportable_activity} enrichment.
 *
 * <p>{@code carriesReportGroupId} states explicitly whether a pipeline's evidence rows carry a
 * {@code rpt_grp_id} column: a hand-written query can't conditionally select a column that isn't
 * there, so each pipeline declares the fact rather than having it inferred at runtime. {@code
 * false} for {@link BatchEvidenceQueries} (batch-scoped evidence is already scoped to one report
 * group via its own {@code ruleHitMatches} condition, so the merge and the rule-hit rollup never
 * need it); {@code true} for the period/overview pipelines, whose rule-hit enrichment can span
 * every report group in view.
 */
public class EvidencePaginator {
  private static final String RANKED_EVIDENCE_SQL = "sql/evidence/ranked-evidence.sql";
  private static final String SELECT_LIST_PAGE_SQL = "sql/evidence/select-list-page.sql";
  private static final String SELECT_DETAIL_PAGE_SQL = "sql/evidence/select-detail-page.sql";
  private static final RowMapper<PageKey> PAGE_KEY_ROW_MAPPER =
      (rs, rowNum) -> new PageKey(rs.getString("evidence_batch_id"), rs.getString("identifier"),
          rs.getObject("sort_ts", OffsetDateTime.class), rs.getString("record_key"));
  private static final RowMapper<TransactionEvidenceProjection> LIST_ROW_MAPPER =
      (rs, rowNum) -> new TransactionEvidenceProjection(rs
            // recordKey
            .getString("recordKey"), rs
            // identifier
            .getString("identifier"), rs
            // mtcn
            .getString("mtcn"), rs
            // batchId
            .getString("batchId"), rs
            // evidenceSource
            .getString("evidenceSource"), rs
            // stage
            .getString("stage"), rs
            // status
            .getString("status"), rs
            // outcome
            .getString("outcome"), rs
            // comments
            .getString("comments"), rs
            // skipReason
            .getString("skipReason"),
          // ruleId (detail-only)
          null, rs
            // exclusionReason
            .getString("exclusionReason"),
          // exclusionStrategy (detail-only)
          null, rs
            // reportedBatchId
            .getString("reportedBatchId"),
          // reportingTimestamp (detail-only)
          null, rs
            // modifiedAt
            .getString("modifiedAt"),
          // processingComplete
          (Boolean) rs.getObject("processingComplete"),
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // currencyAmount..receiverName (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // senderCity..receiverCity (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null,
          // receiverCountry..receiverIdNumber (detail-only)
          null, null, null,
          // transactionStatus, transactionSubStatus, ruleHitsJson (detail-only)
          null);
  private static final RowMapper<TransactionEvidenceProjection> DETAIL_ROW_MAPPER =
      (rs, rowNum) -> {
    Double currencyAmount = (Double) rs.getObject("currencyAmount");
    return new TransactionEvidenceProjection(rs.getString("recordKey"), rs.getString("identifier"), rs.getString("mtcn"),
        rs.getString("batchId"), rs.getString("evidenceSource"), rs.getString("stage"), rs.getString("status"), rs.getString("outcome"),
        rs.getString("comments"), rs.getString("skipReason"), rs.getString("ruleId"), rs.getString("exclusionReason"),
        rs.getString("exclusionStrategy"), rs.getString("reportedBatchId"), rs.getString("reportingTimestamp"), rs.getString("modifiedAt"),
        (Boolean) rs.getObject("processingComplete"), currencyAmount == null ? null : BigDecimal.valueOf(currencyAmount),
        rs.getString("currencyCode"), rs.getString("transactionDate"), rs.getString("transactionSide"), rs.getString("txnSource"),
        rs.getString("activityType"), rs.getString("sendDate"), rs.getString("galacticId"), (Integer) rs.getObject("bucketId"),
        (Long) rs.getObject("attemptId"), rs.getString("senderName"), rs.getString("receiverName"), rs.getString("senderCity"),
        rs.getString("senderCountry"), rs.getString("senderPhone"), rs.getString("senderDateOfBirth"), rs.getString("senderIdType"),
        rs.getString("senderIdNumber"), rs.getString("receiverCity"), rs.getString("receiverCountry"), rs.getString("receiverPhone"),
        rs.getString("receiverDateOfBirth"), rs.getString("receiverIdType"), rs.getString("receiverIdNumber"),
        rs.getString("transactionStatus"), rs.getString("transactionSubStatus"), rs.getString("ruleHitsJson"));
  };
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public EvidencePaginator(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  private record PageKey(String evidenceBatchId, String identifier, OffsetDateTime sortTs, String recordKey) {}

  private record EvidencePageKeys(List<PageKey> keys, String nextCursor) {}

  private static String orderBy(String sortDirection, String prefix) {
    boolean ascending = "ASC".equals(sortDirection);
    return ascending
        ? prefix + "sort_ts asc nulls last, " + prefix + "record_key asc"
        : prefix + "sort_ts desc nulls last, " + prefix + "record_key asc";
  }

  private EvidencePageKeys splitPage(List<PageKey> keysWithPeek, int size) {
    if (keysWithPeek.size() <= size) {
      return new EvidencePageKeys(keysWithPeek, null);
    }
    PageKey lastOnPage = keysWithPeek.get(size - 1);
    return new EvidencePageKeys(keysWithPeek.subList(0, size), EvidenceCursor.encode(lastOnPage.sortTs(), lastOnPage.recordKey()));
  }

  /**
   * Pass 2's tuple-IN restriction: {@code (evidence_batch_id, identifier) IN ((:b0,:i0), ...)},
   * degenerating to {@code where false} for an empty page -- an empty {@code IN ()} list is invalid
   * SQL, so an empty page is handled as its own explicit branch instead.
   */
  private SqlFragment buildMergeCte(List<PageKey> pageKeys, boolean carriesReportGroupId) {
    StringBuilder select = new StringBuilder("select evidence_batch_id, identifier");
    if (carriesReportGroupId) {
      select.append(",\n  ").append(EvidenceSqlSupport.firstNonNullByRank("rpt_grp_id", "merge_source_rank", "record_key"));
    }
    for (String column : EvidenceColumns.MERGE_COLUMNS) {
      select.append(",\n  ").append(EvidenceSqlSupport.firstNonNullByRank(column, "merge_source_rank", "record_key"));
    }
    select.append(",\n  min(merge_source_rank) as merge_source_rank");
    select.append("\nfrom ranked_evidence\nwhere ");

    Map<String, Object> params = new HashMap<>();
    if (pageKeys.isEmpty()) {
      select.append("false");
    } else {
      select.append("(evidence_batch_id, identifier) in (");
      for (int i = 0; i < pageKeys.size(); i++) {
        if (i > 0) {
          select.append(", ");
        }
        select.append("(:mergeBatch").append(i).append(", :mergeIdentifier").append(i).append(")");
        params.put("mergeBatch" + i, pageKeys.get(i).evidenceBatchId());
        params.put("mergeIdentifier" + i, pageKeys.get(i).identifier());
      }
      select.append(")");
    }
    select.append("\ngroup by evidence_batch_id, identifier");
    return SqlFragment.of(select.toString(), params).asCte("merged");
  }

  /**
   * {@code filteredCtes} is the caller's own flat list of preceding CTEs, the last of which must
   * be named {@code filtered_evidence} -- callers build this chain themselves (e.g. {@code
   * rule_hit_matches}, {@code evidence}, {@code metric_scoped}, {@code filtered_evidence}) rather
   * than handing this method an unaliased fragment to wrap, so every stage's name is fixed once,
   * by whichever class defines it, with no risk of two different aliasing points colliding on the
   * same CTE name.
   */
  public EvidencePage pageEvidence(List<SqlFragment> filteredCtes, boolean carriesReportGroupId,
      Function<Collection<String>, SqlFragment> ruleHitBridge, String sortDirection, int size, long offset, EvidenceCursor cursor,
      EvidenceProjection projection) {
    SqlFragment rankedCte = SqlFragment.of(sql.load(RANKED_EVIDENCE_SQL)).asCte("ranked_evidence");

    String sortKeysSql = "select evidence_batch_id, identifier,\n  "
        + EvidenceSqlSupport.firstNonNullByRankExpr("sort_ts", "merge_source_rank", "record_key") + " as sort_ts,\n  "
        + EvidenceSqlSupport.firstNonNullByRankExpr("record_key", "merge_source_rank", "record_key")
        + " as record_key\nfrom ranked_evidence\ngroup by evidence_batch_id, identifier";
    SqlFragment sortKeysCte = SqlFragment.of(sortKeysSql).asCte("identifier_sort_keys");

    List<SqlFragment> fetchCtes = new ArrayList<>(filteredCtes);
    fetchCtes.add(rankedCte);
    fetchCtes.add(sortKeysCte);
    String sortKeysSource = "identifier_sort_keys";
    if (projection.identifier() != null) {
      StringBuilder exact =
          new StringBuilder("select * from identifier_sort_keys where identifier = :exactIdentifier and evidence_batch_id = :exactBatchId");
      Map<String, Object> exactParams = new HashMap<>();
      exactParams.put("exactIdentifier", projection.identifier());
      exactParams.put("exactBatchId", projection.batchId());
      if (projection.recordKey() != null && !projection.recordKey().isBlank()) {
        exact.append(" and record_key = :exactRecordKey");
        exactParams.put("exactRecordKey", projection.recordKey());
      }
      fetchCtes.add(SqlFragment.of(exact.toString(), exactParams).asCte("exact_detail_keys"));
      sortKeysSource = "exact_detail_keys";
    }

    boolean ascending = "ASC".equals(sortDirection);
    Map<String, Object> fetchParams = new HashMap<>();
    StringBuilder fetchWhere = new StringBuilder();
    if (cursor != null) {
      // Decomposed as an explicit OR, never a single ROW comparison: sort_ts sorts in the
      // requested direction but record_key is *always* an ascending tiebreak, so a single
      // ROW(sortTs, recordKey) comparison (which applies the same direction to both columns)
      // silently drops rows tied on sort_ts but sorted after the cursor by the ascending tiebreak
      // whenever the requested direction is DESC.
      String cmp = ascending ? ">" : "<";
      fetchWhere
        .append("where (sort_ts ")
        .append(cmp)
        .append(" :cursorSortTs or (sort_ts = :cursorSortTs and record_key > :cursorRecordKey))\n");
      fetchParams.put("cursorSortTs", cursor.sortTs());
      fetchParams.put("cursorRecordKey", cursor.recordKey());
    }
    fetchParams.put("fetchLimit", size + 1);
    StringBuilder fetchSql = new StringBuilder("select evidence_batch_id, identifier, sort_ts, record_key from ")
      .append(sortKeysSource)
      .append("\n")
      .append(fetchWhere)
      .append("order by ")
      .append(orderBy(sortDirection, ""))
      .append("\nlimit :fetchLimit");
    if (cursor == null) {
      fetchSql.append(" offset :fetchOffset");
      fetchParams.put("fetchOffset", offset);
    }

    SqlFragment fetchCombined = SqlFragment.combine(fetchCtes, SqlFragment.of(fetchSql.toString(), fetchParams));
    List<PageKey> keysWithPeek = jdbc.query(fetchCombined.sql(), fetchCombined.parameterSource(), PAGE_KEY_ROW_MAPPER);

    EvidencePageKeys page = splitPage(keysWithPeek, size);
    if (page.keys().isEmpty()) {
      return new EvidencePage(List.of(), null);
    }

    SqlFragment mergeCte = buildMergeCte(page.keys(), carriesReportGroupId);
    List<SqlFragment> mergeCtes = new ArrayList<>(filteredCtes);
    mergeCtes.add(rankedCte);
    mergeCtes.add(mergeCte);

    if (!projection.details()) {
      // Nothing in the list projection reads rule hits, so the bridge is not built at all here.
      String listSql = sql.load(SELECT_LIST_PAGE_SQL).replace("%%ORDER_BY%%", orderBy(sortDirection, "m."));
      SqlFragment combined = SqlFragment.combine(mergeCtes, SqlFragment.of(listSql));
      List<TransactionEvidenceProjection> records = jdbc.query(combined.sql(), combined.parameterSource(), LIST_ROW_MAPPER);
      return new EvidencePage(records, page.nextCursor());
    }

    List<String> pageIdentifiers = page.keys().stream().map(PageKey::identifier).distinct().toList();
    SqlFragment ruleHitFragment = ruleHitBridge.apply(pageIdentifiers).asCte("rule_hit_matches_enrichment");
    List<SqlFragment> detailCtes = new ArrayList<>(mergeCtes);
    detailCtes.add(ruleHitFragment);

    String reportGroupFilter = carriesReportGroupId ? "and rhm.rpt_grp_id = m.rpt_grp_id" : "";
    String detailSql = sql
      .load(SELECT_DETAIL_PAGE_SQL)
      .replace("%%REPORT_GROUP_FILTER%%", reportGroupFilter)
      .replace("%%ORDER_BY%%", orderBy(sortDirection, "m."));
    SqlFragment combined = SqlFragment.combine(detailCtes, SqlFragment.of(detailSql));
    List<TransactionEvidenceProjection> records = jdbc.query(combined.sql(), combined.parameterSource(), DETAIL_ROW_MAPPER);
    return new EvidencePage(records, page.nextCursor());
  }

  /**
   * {@code COUNT(DISTINCT (evidence_batch_id, identifier))} over an already-filtered evidence
   * table -- deliberately a per-batch-decision count, not a per-transaction one, since every
   * batch-scalar reconciliation aggregate this gets compared against is itself a sum of per-batch
   * scalars (never identifier-deduplicated across batches).
   */
  public long countDistinctIdentifiers(List<SqlFragment> filteredCtes) {
    SqlFragment body = SqlFragment.of("select count(distinct (evidence_batch_id, identifier)) as cnt from filtered_evidence");
    SqlFragment combined = SqlFragment.combine(filteredCtes, body);
    Long count = jdbc.queryForScalar(combined.sql(), combined.parameterSource(), Long.class);
    return count == null ? 0L : count;
  }
}
