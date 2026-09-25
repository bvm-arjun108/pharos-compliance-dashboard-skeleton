package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceColumns;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Period-scoped transaction evidence: Transactions Overview drilldowns spanning every batch in a
 * reporting-period date range, for every status except EXCLUDED/NOT_REPORTED (those two are
 * answered from {@code OverviewEvidenceQueries}'s entirely different "ever reported, across every
 * batch in this window" definition).
 *
 * <p>{@code batch_scope} is threaded through nearly every method as a fixed CTE name rather than a
 * re-embedded SQL fragment: every method below assumes {@code batch_scope} is already a named CTE
 * earlier in the same {@code WITH} clause (the same fixed-name convention {@link EvidencePaginator}
 * already uses for {@code ranked_evidence}/{@code filtered_evidence}), which every public entry
 * point below is responsible for adding to its own CTE list exactly once. {@code
 * OverviewEvidenceQueries} reuses {@link #filteredEvidenceForPeriod} and {@link
 * #ruleHitMatchesForPeriodEnrichment} directly -- both already public for that reason -- and must
 * follow the same convention: build its own {@code batch_scope} CTE via {@link #batchScope} before
 * calling either.
 */
public class PeriodEvidenceQueries {
  private static final String EVIDENCE_FOR_PERIOD_SQL = "sql/evidence/period/evidence-for-period.sql";
  private static final String VALUE_REPORTED = "REPORTED";
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;
  private final RuleHitMatcher ruleHitMatcher;
  private final EvidencePaginator paginator;

  public PeriodEvidenceQueries(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql, RuleHitMatcher ruleHitMatcher,
      EvidencePaginator paginator) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.ruleHitMatcher = ruleHitMatcher;
    this.paginator = paginator;
  }

  /**
   * The reconciliation batches in scope for a reporting period -- a plain SQL body, not yet a
   * named CTE, so every caller controls exactly where and how many times it's spliced into its own
   * {@code WITH} clause (always as a CTE literally named {@code batch_scope}, by the convention
   * this class's Javadoc describes).
   */
  public SqlFragment batchScope(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId) {
    StringBuilder sqlText = new StringBuilder("select rpt_grp_id, batch_id, rpt_grp_name, excluded_txn\n")
      .append("from ")
      .append(EvidenceTables.RECONCILIATION)
      .append("\n")
      .append("where created_timestamp >= :fromTimestamp\n")
      .append("  and created_timestamp < :toTimestampExclusive\n");
    Map<String, Object> params = new HashMap<>();
    params.put("fromTimestamp", fromTimestamp);
    params.put("toTimestampExclusive", toTimestampExclusive);

    if (filterByCountry) {
      if (reportGroupIds.isEmpty()) {
        // Mirrors jOOQ's own defensive handling of Field.in(emptyCollection): an empty IN-list
        // would either be invalid SQL or (with jOOQ) silently render as always-false -- do the
        // same here explicitly rather than attempt "in ()".
        sqlText.append("  and 1 = 0\n");
      } else {
        sqlText.append("  and rpt_grp_id in (:reportGroupIds)\n");
        params.put("reportGroupIds", reportGroupIds);
      }
    }
    if (filterByReportGroup) {
      sqlText.append("  and rpt_grp_id = :reportGroupId\n");
      params.put("reportGroupId", reportGroupId);
    }
    if (batchId != null && !batchId.isEmpty()) {
      sqlText.append("  and lower(batch_id) like lower(:batchIdPattern)\n");
      params.put("batchIdPattern", "%" + batchId + "%");
    }
    return SqlFragment.of(sqlText.toString(), params);
  }

  @SqlQueryPurpose("Summarize transaction evidence across the selected reporting period")
  public PeriodAggregateProjection findPeriodAggregate(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId) {
    SqlFragment scope =
        batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup, reportGroupId, batchId);
    SqlFragment body = SqlFragment.of(
        "select count(distinct (rpt_grp_id, batch_id)) as \"batchCount\",\n" + "  coalesce(sum(excluded_txn), 0) as \"totalExcluded\",\n"
        + "  max(rpt_grp_name) as \"reportGroupName\"\n" + "from batch_scope");
    SqlFragment combined = SqlFragment.combine(List.of(scope.asCte("batch_scope")), body);
    return jdbc
      .queryForOptional(combined.sql(), combined.parameterSource(), (rs, rowNum) -> new PeriodAggregateProjection(rs.getLong("batchCount"),
          rs.getLong("totalExcluded"), rs.getString("reportGroupName")))
      .orElseThrow(() -> new IllegalStateException("Period aggregate returned no row"));
  }

  /**
   * Backs a batch picker (typeahead) for the period report -- every distinct batch ID in scope,
   * ordered, not paginated (a reporting period's batch count is small enough to hand back whole).
   */
  public List<String> distinctBatchIds(SqlFragment scope) {
    SqlFragment body = SqlFragment.of("select distinct batch_id from batch_scope order by batch_id");
    SqlFragment combined = SqlFragment.combine(List.of(scope.asCte("batch_scope")), body);
    return jdbc.query(combined.sql(), combined.parameterSource(), (rs, rowNum) -> rs.getString("batch_id"));
  }

  /**
   * The <em>union branch's</em> rule_hit source only -- never the enrichment's, which must use
   * {@link #ruleHitMatchesForPeriodEnrichment}. A RULE_HIT evidence row's status is always
   * REPORTED/NOT_REPORTED, so for any other requested status {@link #filteredEvidenceForPeriod}
   * would discard every row this produces; short-circuiting to zero rows skips the
   * identifier-matching join rather than running it for rows that cannot survive the filter.
   *
   * <p>Unlike the batch-scoped version, NOT_REPORTED is deliberately excluded from this
   * short-circuit exemption: that status no longer reads rule_hit.is_reported at all (it's
   * answered from the journey-history ever_reported/ever_excluded roll-up in {@code
   * OverviewEvidenceQueries} instead), so including those rows in the union would add cost without
   * affecting the result -- and could double-count a transaction whose journey row and rule_hit
   * row disagree on evidence_batch_id vs efile_batch_id.
   */
  private SqlFragment ruleHitMatchesForPeriod(String status) {
    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status))) {
      return SqlFragment.of("select rh.*, cast(null as text) as matched_identifier from " + EvidenceTables.RULE_HIT + " rh where false");
    }
    return ruleHitBridge();
  }

  /**
   * The "Rule Hit Details" enrichment's rule_hit source: the same window-scoped identifier match as
   * {@link #ruleHitMatchesForPeriod}, minus the status short-circuit. The enrichment answers a
   * question about the <em>transaction</em> -- every rule_hit matched to its identifier inside this
   * scope -- not about the row's own evidence source or the status the list happens to be filtered
   * to, so a status filter must not narrow it.
   *
   * <p>"Independent of status" never meant unscoped. This matches on the exact {@code (rpt_grp_id,
   * efile_batch_id)} pairs {@code batch_scope} resolves rather than on the report group as a whole,
   * so a resubmitted transaction's rule_hit rows from another batch cannot be attributed here. It
   * is bounded further to {@code pageIdentifiers}: the transactions actually being rendered, which
   * keeps the match a keyed lookup instead of something that scales with the window.
   *
   * <p>Assumes {@code batch_scope} is already a named CTE in the same {@code WITH} clause -- see
   * this class's Javadoc.
   */
  public SqlFragment ruleHitMatchesForPeriodEnrichment(Collection<String> pageIdentifiers) {
    String journeyScopedSql = "select j.rpt_grp_id as rpt_grp_id, j.batch_id as batch_id, j.identifier as identifier, j.mtcn as mtcn,\n"
        + "  (case when " + EvidenceSqlSupport.matchesDigitsOnly("j.identifier") + " then j.identifier::bigint else null end) as "
        + "identifier_bigint\n" + "from " + EvidenceTables.JOURNEY + " j\n"
        + "join batch_scope bs on bs.rpt_grp_id = j.rpt_grp_id and bs.batch_id = j.batch_id\n"
        + "where j.identifier in (:enrichmentIdentifiers)";
    SqlFragment journeyScoped = SqlFragment.of(journeyScopedSql, Map.of("enrichmentIdentifiers", pageIdentifiers));
    SqlFragment ruleHitScope = SqlFragment.of("(rh.rpt_grp_id, rh.efile_batch_id) in (select rpt_grp_id, batch_id from batch_scope)");
    return ruleHitMatcher.scopedRuleHitMatches(ruleHitScope, journeyScoped);
  }

  /**
   * The union branch's rule_hit-to-journey-identifier bridge: unrestricted by page, because it
   * produces evidence rows that still have to be filtered, sorted and paginated, so it cannot be
   * narrowed to a page that has not been chosen yet.
   *
   * <p>"Unrestricted by page" is not "unrestricted by batch": these rows carry efile_batch_id as
   * their evidence_batch_id, and the paginator counts distinct (evidence_batch_id, identifier)
   * pairs, so this matches on the exact {@code (rpt_grp_id, efile_batch_id)} pairs {@code
   * batch_scope} resolves, never on the report group as a whole.
   */
  private SqlFragment ruleHitBridge() {
    String journeyScopedSql = "select j.identifier as identifier, j.mtcn as mtcn,\n" + "  (case when "
        + EvidenceSqlSupport.matchesDigitsOnly("j.identifier") + " then j.identifier::bigint else null end) as identifier_bigint\n"
        + "from " + EvidenceTables.JOURNEY + " j\n" + "join batch_scope bs on bs.rpt_grp_id = j.rpt_grp_id and bs.batch_id = j.batch_id";
    SqlFragment journeyScoped = SqlFragment.of(journeyScopedSql);
    SqlFragment ruleHitScope = SqlFragment.of("(rh.rpt_grp_id, rh.efile_batch_id) in (select rpt_grp_id, batch_id from batch_scope)");
    return ruleHitMatcher.ruleHitMatches(ruleHitScope, journeyScoped);
  }

  private SqlFragment evidenceForPeriod() {
    String journeyOutcome = EvidenceSqlSupport.journeyOutcome("j.status");
    String rraKeyGuard = EvidenceSqlSupport.matchesDigitsOnly("j.identifier");
    String evidenceSql =
        sql.load(EVIDENCE_FOR_PERIOD_SQL).replace("%%JOURNEY_OUTCOME%%", journeyOutcome).replace("%%RRA_KEY_GUARD%%", rraKeyGuard);
    return SqlFragment.of(evidenceSql);
  }

  /**
   * {@code batch_scope} + {@code rule_hit_matches} + {@code evidence}, as a flat, ordered CTE list
   * -- every caller below appends its own {@code filtered_evidence} stage on top.
   */
  private List<SqlFragment> evidenceCtes(SqlFragment scope, String status) {
    List<SqlFragment> ctes = new ArrayList<>();
    ctes.add(scope.asCte("batch_scope"));
    ctes.add(ruleHitMatchesForPeriod(status).asCte("rule_hit_matches"));
    ctes.add(evidenceForPeriod().asCte("evidence"));
    return ctes;
  }

  /**
   * Assumes {@code batch_scope} is already a named CTE in the same {@code WITH} clause -- see this
   * class's Javadoc. {@code evidenceCteName} is the name of the already-defined evidence-source CTE
   * to filter (always {@code "evidence"} for this class's own callers; {@code OverviewEvidenceQueries}
   * passes its own differently-shaped {@code "latest"} CTE instead).
   */
  public SqlFragment filteredEvidenceForPeriod(String evidenceCteName, String search, String outcome, String status) {
    SqlFragment searchCondition = EvidenceSqlSupport.searchScope(search, "identifier", "mtcn");
    Map<String, Object> params = new HashMap<>(searchCondition.params());

    if (!VALUE_REPORTED.equals(status)) {
      StringBuilder sqlText =
          new StringBuilder("select e.* from ").append(evidenceCteName).append(" e\n").append("where ").append(searchCondition.sql());
      if (!"ALL".equals(outcome)) {
        sqlText.append("\n  and e.outcome = :outcomeFilter");
        params.put("outcomeFilter", outcome);
      }
      if (!"ALL".equals(status)) {
        sqlText.append("\n  and upper(coalesce(e.status, '')) = :statusFilter");
        params.put("statusFilter", status);
      }
      return SqlFragment.of(sqlText.toString(), params);
    }
    // status=REPORTED must also match a JOURNEY row recorded as REPORT_GENERATION/GENERATED, not
    // only a literal "REPORTED" status -- the latter only ever appears on a RULE_HIT-sourced row
    // (synthesized from rule_hit.is_reported), so a reported transaction with no matching rule_hit
    // row had no way to pass this filter at all. A bare TRANSFORMATION/SUCCESS row also counts, but
    // only when that row's own batch actually generated its report (the same condition
    // OverviewEvidenceQueries#reportingRoll's everReportedCondition already applies to the
    // Transactions Overview tiles, mirrored here so this drilldown agrees with them) -- without the
    // batch_generated check, a transaction merely transformed in a batch whose report generation
    // later failed would count as reported when it is really just sitting there, still waiting.
    StringBuilder sqlText = new StringBuilder("select e.* from ")
      .append(evidenceCteName)
      .append(" e\n")
      .append("left join (\n")
      .append("  select bs.rpt_grp_id as rpt_grp_id, bs.batch_id as batch_id,\n")
      .append(
          "    coalesce(bi.compiler_status = 'Report Generation Completed' or bi.report_status in ('ALL', 'PARTIAL'), false) as "
          + "batch_generated\n")
      .append("  from batch_scope bs\n")
      .append("  left join ")
      .append(EvidenceTables.BATCH_INFO)
      .append(" bi on bi.rpt_grp_id = bs.rpt_grp_id and bi.batch_id = bs.batch_id\n")
      .append(") reported_filter_batch_generated\n")
      .append("  on reported_filter_batch_generated.rpt_grp_id = e.rpt_grp_id\n")
      .append("  and reported_filter_batch_generated.batch_id = e.evidence_batch_id\n")
      .append("where ")
      .append(searchCondition.sql());
    if (!"ALL".equals(outcome)) {
      sqlText.append("\n  and e.outcome = :outcomeFilter");
      params.put("outcomeFilter", outcome);
    }
    sqlText
      .append("\n  and (\n")
      .append("    upper(coalesce(e.status, '')) = '")
      .append(VALUE_REPORTED)
      .append("'\n")
      .append("    or (e.evidence_source = '")
      .append(EvidenceColumns.SOURCE_JOURNEY)
      .append("' and upper(coalesce(e.stage, '')) = 'REPORT_GENERATION' and upper(coalesce(e.status, '')) = 'GENERATED')\n")
      .append("    or (e.evidence_source = '")
      .append(EvidenceColumns.SOURCE_JOURNEY)
      .append("' and upper(coalesce(e.stage, '')) = 'TRANSFORMATION' and upper(coalesce(e.status, '')) = 'SUCCESS'\n")
      .append("      and reported_filter_batch_generated.batch_generated is true)\n")
      .append("  )");
    return SqlFragment.of(sqlText.toString(), params);
  }

  /**
   * The non-overview period path: every status except EXCLUDED/NOT_REPORTED, which the facade
   * routes to {@code OverviewEvidenceQueries} instead.
   */
  public EvidencePage findEvidenceRecords(SqlFragment scope, String search, String outcome, String status, String sortDirection, int size,
      long offset, EvidenceCursor cursor, EvidenceProjection projection) {
    List<SqlFragment> ctes = evidenceCtes(scope, status);
    ctes.add(filteredEvidenceForPeriod("evidence", search, outcome, status).asCte("filtered_evidence"));
    return paginator.pageEvidence(ctes, true, this::ruleHitMatchesForPeriodEnrichment, sortDirection, size, offset, cursor, projection);
  }

  public long countEvidenceRecords(SqlFragment scope, String search, String outcome, String status) {
    List<SqlFragment> ctes = evidenceCtes(scope, status);
    ctes.add(filteredEvidenceForPeriod("evidence", search, outcome, status).asCte("filtered_evidence"));
    return paginator.countDistinctIdentifiers(ctes);
  }

  /**
   * Journey-only, scoped to exactly the batches {@code scope} covers -- the simple, direct
   * definition that actually matches how a "total excluded transactions" KPI computed as {@code
   * SUM(report_transformation_reconciliation.excluded_txn)} over the same batch set is itself
   * defined, deliberately independent of {@code OverviewEvidenceQueries}'s "ever excluded, across
   * every batch in the window" rollup (that one matches a <em>different</em> KPI -- Transactions
   * Overview's own Excluded tile, a distinct-transaction, identity-deduplicated concept, scoped to
   * the same window rather than to the transaction's all-time history).
   *
   * <p>Narrowed to {@link EvidenceColumns#VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS} specifically --
   * confirmed against real production data that {@code excluded_txn} equals that count exactly on
   * every batch, while the SML/already-reported exclusion comments back their own separate
   * reconciliation scalars and must stay out of this bucket.
   */
  public SqlFragment filteredExcludedEvidenceForBatchTotal(String evidenceCteName, String search) {
    SqlFragment searchCondition = EvidenceSqlSupport.searchScope(search, "identifier", "mtcn");
    String sqlText = "select * from " + evidenceCteName + "\n" + "where " + searchCondition.sql() + "\n  and evidence_source = '"
        + EvidenceColumns.SOURCE_JOURNEY + "'\n  and upper(coalesce(stage, '')) = '" + EvidenceColumns.STAGE_FILTRATION
        + "'\n  and outcome = '" + EvidenceColumns.VALUE_EXCLUDED + "'\n  and upper(coalesce(comments, '')) = '"
        + EvidenceColumns.VALUE_EXCLUDED_BECAUSE_EXCLUSION_EXISTS + "'";
    return SqlFragment.of(sqlText, searchCondition.params());
  }

  /**
   * Backs the "Excluded" total on the Report Groups Requiring Attention table -- see {@link
   * #filteredExcludedEvidenceForBatchTotal}.
   */
  public EvidencePage findExcludedEvidenceRecordsForBatchTotal(SqlFragment scope, String search, String sortDirection, int size, long offset,
      EvidenceCursor cursor, EvidenceProjection projection) {
    List<SqlFragment> ctes = evidenceCtes(scope, EvidenceColumns.VALUE_EXCLUDED);
    ctes.add(filteredExcludedEvidenceForBatchTotal("evidence", search).asCte("filtered_evidence"));
    return paginator.pageEvidence(ctes, true, this::ruleHitMatchesForPeriodEnrichment, sortDirection, size, offset, cursor, projection);
  }

  public long countExcludedEvidenceRecordsForBatchTotal(SqlFragment scope, String search) {
    List<SqlFragment> ctes = evidenceCtes(scope, EvidenceColumns.VALUE_EXCLUDED);
    ctes.add(filteredExcludedEvidenceForBatchTotal("evidence", search).asCte("filtered_evidence"));
    return paginator.countDistinctIdentifiers(ctes);
  }
}
