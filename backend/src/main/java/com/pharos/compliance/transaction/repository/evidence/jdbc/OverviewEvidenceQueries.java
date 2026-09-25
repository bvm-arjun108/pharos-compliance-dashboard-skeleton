package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceColumns;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excluded/Not Reported, reached from the Transactions Overview dashboard tiles, answered from
 * {@link #reportingRoll} (the same "ever excluded"/"ever reported" definition the tile itself
 * counted), entirely independent of the per-batch evidence/merge pipeline every other status still
 * uses ({@link PeriodEvidenceQueries}). "Ever" means "at any point across every batch in the
 * caller's date/country/report-group window" (the same {@code batch_scope} the period query
 * already resolves), not a transaction's entire all-time lifetime.
 *
 * <p>Reuses {@link PeriodEvidenceQueries#filteredEvidenceForPeriod} and {@link
 * PeriodEvidenceQueries#ruleHitMatchesForPeriodEnrichment} directly, following the same {@code
 * batch_scope}-is-already-a-named-CTE convention {@link PeriodEvidenceQueries}'s own Javadoc
 * describes -- every public entry point below adds {@code batch_scope} to its own CTE list exactly
 * once, before either of those shared methods can be called.
 */
public class OverviewEvidenceQueries {
  private static final String LATEST_JOURNEY_SQL = "sql/evidence/overview/latest-journey-for-target.sql";
  private static final String VALUE_EXCLUDED = EvidenceColumns.VALUE_EXCLUDED;
  private static final String VALUE_NOT_REPORTED = EvidenceColumns.VALUE_NOT_REPORTED;
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;
  private final EvidencePaginator paginator;
  private final PeriodEvidenceQueries periodEvidenceQueries;

  public OverviewEvidenceQueries(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql, EvidencePaginator paginator,
      PeriodEvidenceQueries periodEvidenceQueries) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.paginator = paginator;
    this.periodEvidenceQueries = periodEvidenceQueries;
  }

  /**
   * Assumes {@code batch_scope} is already a named CTE in the same {@code WITH} clause.
   */
  private SqlFragment reportingBatchEvidence() {
    return SqlFragment.of(
        "select bs.rpt_grp_id as rpt_grp_id, bs.batch_id as batch_id,\n"
        + "  coalesce(bi.compiler_status = 'Report Generation Completed' or bi.report_status in ('ALL', 'PARTIAL'), false) as "
        + "batch_generated\n" + "from batch_scope bs\n" + "left join " + EvidenceTables.BATCH_INFO
        + " bi on bi.rpt_grp_id = bs.rpt_grp_id and bi.batch_id = bs.batch_id");
  }

  /**
   * Rolls up every journey event (not just the latest-state row) per {@code (rpt_grp_id,
   * identifier)} in scope into {@code ever_excluded}/{@code ever_reported} booleans -- identical
   * logic and bucket definitions to {@code DashboardRepository#getTransactionOverview}, ported here
   * so the period-wide transaction list's Excluded/Not Reported filters match what the dashboard
   * tile they're clicked from actually counted. Assumes {@code reporting_batch_evidence} is already
   * a named CTE earlier in the same {@code WITH} clause.
   *
   * <p>{@code stage = 'REPORT_GENERATION'}/{@code stage = 'TRANSFORMATION'} are deliberately bare,
   * case-sensitive, NULL-unsafe equality (unlike the {@code upper(coalesce(status, ''))} comparisons
   * alongside them) -- translated exactly as the jOOQ version wrote it, not "fixed" for apparent
   * inconsistency.
   */
  private SqlFragment reportingRoll() {
    String sqlText = "select j.rpt_grp_id as rpt_grp_id, j.identifier as identifier,\n"
        + "  bool_or(upper(coalesce(j.status, '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP')) as ever_excluded,\n"
        + "  bool_or((j.stage = 'REPORT_GENERATION' and upper(coalesce(j.status, '')) = 'GENERATED')\n"
        + "    or (j.stage = 'TRANSFORMATION' and upper(coalesce(j.status, '')) = 'SUCCESS' and rbe.batch_generated is true)"
        + ") as ever_reported,\n"
        + "  max(case when upper(coalesce(j.status, '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP') then coalesce(j.comments, j.skip_reason) "
        + "end) as reason,\n" + "  max(coalesce(j.comments, j.skip_reason)) as not_reported_reason\n" + "from " + EvidenceTables.JOURNEY
        + " j\n" + "join reporting_batch_evidence rbe on rbe.rpt_grp_id = j.rpt_grp_id and rbe.batch_id = j.batch_id\n"
        + "group by j.rpt_grp_id, j.identifier";
    return SqlFragment.of(sqlText);
  }

  /**
   * The identifiers belonging to one {@link #reportingRoll} bucket. {@code reason} narrows further
   * to the exact slice a dashboard breakdown legend row represents -- a comments/skip_reason value
   * for either status, or the literal {@code "Other"} for that card's catch-all row, matched via a
   * {@code NOT IN} against the same top-3 cutoff {@code DashboardRepository#topReasonsThenOther}
   * used to build the card. Assumes {@code reporting_roll} is already a named CTE.
   */
  private SqlFragment reportingTarget(String status, String reason) {
    String bucketCondition = VALUE_EXCLUDED.equals(status)
        ? "ever_excluded is true and ever_reported is false"
        : "ever_reported is false and ever_excluded is false";

    Map<String, Object> params = new HashMap<>();
    StringBuilder sqlText = new StringBuilder("select rpt_grp_id, identifier from reporting_roll where ").append(bucketCondition);

    if (reason != null && !reason.isEmpty() && (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      String reasonColumn = VALUE_EXCLUDED.equals(status) ? "reason" : "not_reported_reason";
      String rollReason = "coalesce(" + reasonColumn + ", '" + EvidenceColumns.UNSPECIFIED_REASON + "')";
      if (EvidenceColumns.OTHER_REASON.equals(reason)) {
        sqlText
          .append("\n  and ")
          .append(rollReason)
          .append(" not in (\n")
          .append("    select ")
          .append(rollReason)
          .append(" from reporting_roll\n")
          .append("    where ")
          .append(bucketCondition)
          .append("\n    group by ")
          .append(rollReason)
          .append("\n    order by count(*) desc, ")
          .append(rollReason)
          .append("\n    limit ")
          .append(EvidenceColumns.TOP_REASON_LIMIT)
          .append("\n  )");
      } else {
        sqlText.append("\n  and ").append(rollReason).append(" = :reasonFilter");
        params.put("reasonFilter", reason);
      }
    }
    return SqlFragment.of(sqlText.toString(), params);
  }

  private SqlFragment latestJourneyForTarget() {
    String journeyOutcome = EvidenceSqlSupport.journeyOutcome("j.status");
    String rraKeyGuard = EvidenceSqlSupport.matchesDigitsOnly("j.identifier");
    String sqlText = sql
      .load(LATEST_JOURNEY_SQL)
      .replace("%%JOURNEY_OUTCOME%%", journeyOutcome)
      .replace("%%RRA_KEY_GUARD%%", rraKeyGuard);
    return SqlFragment.of(sqlText);
  }

  /**
   * {@code batch_scope} + {@code reporting_batch_evidence} + {@code reporting_roll}, as a flat,
   * ordered CTE list -- every caller below appends its own {@code reporting_target} stage on top.
   */
  private List<SqlFragment> rollCtes(SqlFragment scope) {
    List<SqlFragment> ctes = new ArrayList<>();
    ctes.add(scope.asCte("batch_scope"));
    ctes.add(reportingBatchEvidence().asCte("reporting_batch_evidence"));
    ctes.add(reportingRoll().asCte("reporting_roll"));
    return ctes;
  }

  /**
   * {@code filtered} here is already effectively one row per identifier ({@link
   * #latestJourneyForTarget} already ranked to exactly one), so {@link
   * EvidencePaginator#pageEvidence}'s Pass 2 merge is a no-op in substance (nothing to collapse) --
   * but reusing it rather than a separate single-pass helper is what gives this path real
   * cursor-pagination support for free.
   */
  public EvidencePage findOverviewEvidenceRecords(SqlFragment scope, String status, String reason, String search, String outcome,
      String sortDirection, int size, long offset, EvidenceCursor cursor, EvidenceProjection projection) {
    List<SqlFragment> ctes = rollCtes(scope);
    ctes.add(reportingTarget(status, reason).asCte("reporting_target"));
    ctes.add(latestJourneyForTarget().asCte("latest_journey"));
    ctes.add(periodEvidenceQueries.filteredEvidenceForPeriod("latest_journey", search, outcome, "ALL").asCte("filtered_evidence"));
    return paginator.pageEvidence(ctes, true, periodEvidenceQueries::ruleHitMatchesForPeriodEnrichment, sortDirection, size, offset, cursor,
        projection);
  }

  public long countOverviewEvidenceRecords(SqlFragment scope, String status, String reason, String search, String outcome) {
    List<SqlFragment> ctes = rollCtes(scope);
    ctes.add(reportingTarget(status, reason).asCte("reporting_target"));

    if (search.isEmpty() && "ALL".equals(outcome)) {
      SqlFragment combined = SqlFragment.combine(ctes, SqlFragment.of("select count(*) as cnt from reporting_target"));
      Long count = jdbc.queryForScalar(combined.sql(), combined.parameterSource(), Long.class);
      return count == null ? 0L : count;
    }

    ctes.add(latestJourneyForTarget().asCte("latest_journey"));
    ctes.add(periodEvidenceQueries.filteredEvidenceForPeriod("latest_journey", search, outcome, "ALL").asCte("filtered_evidence"));
    return paginator.countDistinctIdentifiers(ctes);
  }
}
