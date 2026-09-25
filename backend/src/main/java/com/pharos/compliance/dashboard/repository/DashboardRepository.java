package com.pharos.compliance.dashboard.repository;

import com.pharos.compliance.common.jdbc.TransformationFailureQueries;
import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.dashboard.repository.projection.BatchHealthTrendProjection;
import com.pharos.compliance.dashboard.repository.projection.DashboardCountsProjection;
import com.pharos.compliance.dashboard.repository.projection.ExclusionReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.NotReportedReasonProjection;
import com.pharos.compliance.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionOverviewProjection;
import com.pharos.compliance.dashboard.repository.projection.TransactionVolumeTrendProjection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch and transaction dashboard queries, implemented as parameterized PostgreSQL resources.
 */
@Repository
@Transactional(readOnly = true)
public class DashboardRepository {
  private static final String RECONCILIATION_WITH_JOURNEY_FAILURES_SQL = "sql/dashboard/reconciliation-with-journey-failures.sql";
  private static final String DASHBOARD_COUNTS_AGGREGATES_SQL = "sql/dashboard/dashboard-counts-aggregates.sql";
  private static final String GET_DASHBOARD_COUNTS_SQL = "sql/dashboard/get-dashboard-counts.sql";
  private static final String REPORT_GROUP_METRICS_SQL = "sql/dashboard/report-group-metrics.sql";
  private static final String GET_REPORT_GROUPS_REQUIRING_ATTENTION_SQL = "sql/dashboard/get-report-groups-requiring-attention.sql";
  private static final String GET_BATCH_HEALTH_TREND_SQL = "sql/dashboard/get-batch-health-trend.sql";
  private static final String GET_TRANSACTION_VOLUME_TREND_SQL = "sql/dashboard/get-transaction-volume-trend.sql";
  private static final String BATCH_SCOPE_SQL = "sql/dashboard/batch-scope.sql";
  private static final String BATCH_EVIDENCE_SQL = "sql/dashboard/batch-evidence.sql";
  private static final String JOURNEY_ROLL_SQL = "sql/dashboard/journey-roll.sql";
  private static final String GET_TRANSACTION_OVERVIEW_SQL = "sql/dashboard/get-transaction-overview.sql";
  private static final String REASON_COUNTS_SQL = "sql/dashboard/reason-counts.sql";
  private static final String RANKED_REASONS_SQL = "sql/dashboard/ranked-reasons.sql";
  private static final String BUCKETED_REASONS_SQL = "sql/dashboard/bucketed-reasons.sql";
  private static final String TOP_REASONS_FINAL_SQL = "sql/dashboard/top-reasons-final.sql";
  private static final String EXCLUDED_STATUSES_SQL = "upper(coalesce(journey.status, '')) in ('EXCLUDED', 'EXCLUDED_SOFT_DEDUP')";
  private static final RowMapper<DashboardCountsProjection> COUNTS_ROW_MAPPER =
      (rs, rowNum) -> new DashboardCountsProjection(rs.getLong("batchesRan"), rs.getLong("batchesNeedingAttention"),
          rs.getLong("transformationFailureBatches"), rs.getLong("missingAttemptBatches"), rs.getLong("activityMissingBatches"),
          rs.getLong("duplicateTransactionBatches"), rs.getLong("exclusionBatches"), rs.getLong("simulatedTransactionBatches"),
          rs.getLong("softDedupBatches"));
  private static final RowMapper<ReportGroupMetricsProjection> REPORT_GROUP_METRICS_ROW_MAPPER =
      (rs, rowNum) -> new ReportGroupMetricsProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"), rs.getLong("batchesRan"),
          rs.getLong("successfulBatches"), rs.getLong("batchesNeedingAttention"), rs.getLong("transformationFailureBatches"),
          rs.getLong("missingAttemptBatches"), rs.getLong("activityMissingBatches"), rs.getLong("totalReportedTransactions"),
          rs.getLong("totalExcludedTransactions"));
  private static final RowMapper<BatchHealthTrendProjection> BATCH_HEALTH_TREND_ROW_MAPPER =
      (rs, rowNum) -> new BatchHealthTrendProjection(rs.getObject("periodStart", LocalDate.class), rs.getLong("batchesRan"),
          rs.getLong("successfulBatches"), rs.getLong("batchesNeedingAttention"));
  private static final RowMapper<TransactionVolumeTrendProjection> VOLUME_TREND_ROW_MAPPER =
      (rs, rowNum) -> new TransactionVolumeTrendProjection(rs.getObject("periodStart", LocalDate.class),
          rs.getLong("totalReportedTransactions"), rs.getLong("totalExcludedTransactions"));
  private static final RowMapper<TransactionOverviewProjection> OVERVIEW_ROW_MAPPER =
      (rs, rowNum) -> new TransactionOverviewProjection(rs.getLong("selected"), rs.getLong("expected"), rs.getLong("excluded"),
          rs.getLong("notReported"));
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public DashboardRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  /**
   * The date-range/batchId-search/country/reportGroup filters shared by every query in this class.
   * {@code columnPrefix} lets the same condition text be embedded either bare (a query with only
   * {@code report_transformation_reconciliation} in scope) or qualified (e.g. {@code "r."}, needed
   * once a query also joins {@code journey_failures_by_batch}, whose own columns share the same
   * unqualified names) -- every call site in this class uses {@code "r."} since every query here
   * aliases the table that way.
   */
  private SqlFragment reconciliationScope(String columnPrefix, LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    StringBuilder cond = new StringBuilder();
    Map<String, Object> params = new HashMap<>();
    cond.append("\n  and ").append(columnPrefix).append("created_timestamp >= :fromTimestamp");
    cond.append("\n  and ").append(columnPrefix).append("created_timestamp < :toTimestampExclusive");
    params.put("fromTimestamp", fromTimestamp);
    params.put("toTimestampExclusive", toTimestampExclusive);

    if (batchId != null && !batchId.isEmpty()) {
      cond.append("\n  and lower(").append(columnPrefix).append("batch_id) like lower(:batchIdPattern)");
      params.put("batchIdPattern", "%" + batchId + "%");
    }
    if (filterByCountry) {
      if (reportGroupIds.isEmpty()) {
        cond.append("\n  and 1 = 0");
      } else {
        cond.append("\n  and ").append(columnPrefix).append("rpt_grp_id in (:countryReportGroupIds)");
        params.put("countryReportGroupIds", reportGroupIds);
      }
    }
    if (filterByReportGroup) {
      cond.append("\n  and ").append(columnPrefix).append("rpt_grp_id = :reportGroupId");
      params.put("reportGroupId", reportGroupId);
    }
    return SqlFragment.of(cond.toString(), params);
  }

  @SqlQueryPurpose("Batch View > Headline KPI cards > Load batch totals and issue counts")
  public DashboardCountsProjection getDashboardCounts(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");
    SqlFragment rtrScopeCte =
        SqlFragment.of(sql.load(RECONCILIATION_WITH_JOURNEY_FAILURES_SQL) + scope.sql(), scope.params()).asCte("rtr_scope");
    SqlFragment rtrAggregatesCte = SqlFragment.of(sql.load(DASHBOARD_COUNTS_AGGREGATES_SQL)).asCte("rtr_aggregates");

    SqlFragment combined =
        SqlFragment.combine(List.of(journeyFailuresCte, rtrScopeCte, rtrAggregatesCte), SqlFragment.of(sql.load(GET_DASHBOARD_COUNTS_SQL)));
    return jdbc
      .queryForOptional(combined.sql(), combined.parameterSource(), COUNTS_ROW_MAPPER)
      .orElseThrow(() -> new IllegalStateException("Dashboard count aggregate returned no row"));
  }

  /**
   * Across every report group in scope (unfiltered, or every group in the selected country), this
   * narrows to only the ones with at least one batch needing attention -- otherwise a broad,
   * unscoped view would be dozens of rows deep in groups with nothing to investigate. Once the
   * caller has already narrowed scope to one specific report group or country (`filterByReportGroup`
   * / `filterByCountry`), that narrowing is redundant and actively unhelpful -- the caller picked
   * that scope specifically to see its full batch health, attention-needing or not, so this returns
   * every group in scope unfiltered instead of possibly hiding the one row they came here for.
   */
  @SqlQueryPurpose("Batch View > Report Groups Requiring Attention table > Load prioritized group metrics")
  public List<ReportGroupMetricsProjection> getReportGroupsRequiringAttention(LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive, String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup,
      int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");
    String reportGroupMetricsSql = sql.load(REPORT_GROUP_METRICS_SQL) + scope.sql() + "\ngroup by r.rpt_grp_id";
    SqlFragment reportGroupMetricsCte = SqlFragment.of(reportGroupMetricsSql, scope.params()).asCte("report_group_metrics");
    // A specific report group or country is exactly what the caller wants full detail on -- don't
    // additionally hide rows within that already-narrow scope for having nothing to flag.
    String attentionFilter = filterByReportGroup || filterByCountry ? "" : "where batches_needing_attention > 0";
    String body = sql.load(GET_REPORT_GROUPS_REQUIRING_ATTENTION_SQL).replace("%%ATTENTION_FILTER%%", attentionFilter);

    SqlFragment combined = SqlFragment.combine(List.of(journeyFailuresCte, reportGroupMetricsCte), SqlFragment.of(body));
    return jdbc.query(combined.sql(), combined.parameterSource(), REPORT_GROUP_METRICS_ROW_MAPPER);
  }

  private record TrendPeriods(SqlFragment periodsCte, String periodStartExpr) {}

  /**
   * Bucket boundaries and the per-row bucketing expression, shared by every trend query that
   * groups {@code report_transformation_reconciliation} rows into DAILY/WEEKLY/MONTHLY buckets
   * over the requested date range. {@code granularity} is already resolved to exactly one of
   * DAILY/WEEKLY/MONTHLY before either caller runs (via {@code TrendGranularity.forPeriod(...)}),
   * so only the one branch that actually applies is ever built -- a fixed, Java-selected SQL
   * fragment, never a request-derived string reaching the query text.
   */
  private TrendPeriods buildTrendPeriods(String granularity, LocalDate fromDate, LocalDate toDate) {
    String periodsSql = switch (granularity) {
      case "DAILY" -> "select generate_series(:fromDate::date, :toDate::date, interval '1 day')::date as period_start";
      case "WEEKLY" -> "select generate_series(:fromDate::date, :toDate::date, interval '7 days')::date as period_start";
      default -> "select generate_series(date_trunc('month', :fromDate::date), :toDate::date, interval '1 month')::date as period_start";
    };
    // fromDate + (((createdDate - fromDate) / 7) * 7): floor the day offset from fromDate down to
    // the nearest whole week, reproducing the original's integer-division bucketing exactly.
    String periodStartExpr = switch (granularity) {
      case "DAILY" -> "r.created_timestamp::date";
      case "WEEKLY" -> "(:fromDate::date + (((r.created_timestamp::date - :fromDate::date) / 7) * 7))";
      default -> "date_trunc('month', r.created_timestamp)::date";
    };
    SqlFragment periodsCte = SqlFragment.of(periodsSql, Map.of("fromDate", fromDate, "toDate", toDate)).asCte("periods");
    return new TrendPeriods(periodsCte, periodStartExpr);
  }

  /**
   * Batch View's Daily Batch Health chart alone -- ran/successful/needing-attention per bucket.
   * Deliberately doesn't compute reported/excluded transaction totals: those are a different
   * page's concern (see {@link #getTransactionVolumeTrend}).
   */
  @SqlQueryPurpose("Batch View > Daily Batch Health chart > Load successful and failed batch counts")
  public List<BatchHealthTrendProjection> getBatchHealthTrend(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      LocalDate fromDate, LocalDate toDate, String granularity, String batchId, boolean filterByCountry, List<Integer> reportGroupIds,
      boolean filterByReportGroup, int reportGroupId) {
    TrendPeriods trendPeriods = buildTrendPeriods(granularity, fromDate, toDate);
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");

    String periodMetricsSql = "select "
        + trendPeriods.periodStartExpr()
        + " as period_start,\n"
        + "  count(distinct (r.rpt_grp_id, r.batch_id, r.seq_no)) as batches_ran,\n"
        + "  count(distinct (r.rpt_grp_id, r.batch_id, r.seq_no)) filter (\n"
        + "    where coalesce(jf.journey_transformation_failures, coalesce(r.activity_transformation_failed, 0)::bigint) > 0\n"
        + "      or coalesce(r.txn_missing_attempt_count, 0) > 0\n"
        + "      or coalesce(r.activity_missing, 0) > 0\n"
        + "  ) as batches_needing_attention\n"
        + "from pharos.report_transformation_reconciliation r\n"
        + "left join journey_failures_by_batch jf on jf.rpt_grp_id = r.rpt_grp_id and jf.batch_id = r.batch_id\n"
        + "where 1 = 1"
        // Group by the SELECT list's ordinal position, not periodStartExpr's own text repeated: the
    // WEEKLY branch embeds :fromDate, and NamedParameterJdbcTemplate expands each textual
    // occurrence of a named parameter to its own distinct positional placeholder, so a second
    // copy of the same expression here would bind a different parameter marker than the SELECT
    // list's copy -- Postgres then treats them as different expressions and rejects the query
    // ("column must appear in the GROUP BY clause"), even though both hold the same value.
    + scope.sql()
        + "\ngroup by 1";
    Map<String, Object> periodMetricsParams = new HashMap<>(scope.params());
    periodMetricsParams.put("fromDate", fromDate);
    periodMetricsParams.put("toDate", toDate);
    SqlFragment periodMetricsCte = SqlFragment.of(periodMetricsSql, periodMetricsParams).asCte("period_metrics");

    SqlFragment combined = SqlFragment.combine(List.of(journeyFailuresCte, trendPeriods.periodsCte(), periodMetricsCte),
        SqlFragment.of(sql.load(GET_BATCH_HEALTH_TREND_SQL)));
    return jdbc.query(combined.sql(), combined.parameterSource(), BATCH_HEALTH_TREND_ROW_MAPPER);
  }

  /**
   * Transactions Overview's trend heatmap/line charts alone -- reported/excluded transaction
   * totals per bucket. Reported totals use {@code activity_transformed}, not {@code
   * actual_reportable_txn}, which can include failed transformation attempts.
   */
  @SqlQueryPurpose("Transactions Overview > Trend heatmap/line charts > Load reported and excluded transaction totals")
  public List<TransactionVolumeTrendProjection> getTransactionVolumeTrend(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      LocalDate fromDate, LocalDate toDate, String granularity, String batchId, boolean filterByCountry, List<Integer> reportGroupIds,
      boolean filterByReportGroup, int reportGroupId) {
    TrendPeriods trendPeriods = buildTrendPeriods(granularity, fromDate, toDate);
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);

    String periodMetricsSql = "select "
        + trendPeriods.periodStartExpr()
        + " as period_start,\n"
        + "  coalesce(sum(r.activity_transformed), 0) as total_reported_transactions,\n"
        + "  coalesce(sum(r.excluded_txn), 0) as total_excluded_transactions\n"
        + "from pharos.report_transformation_reconciliation r\n"
        // See getBatchHealthTrend's identical comment: group by ordinal position, not a second copy
    // of periodStartExpr's text, since a repeated :fromDate/:toDate reference would otherwise
    // bind a different parameter marker than the SELECT list's copy.
    + "where 1 = 1"
        + scope.sql()
        + "\ngroup by 1";
    Map<String, Object> periodMetricsParams = new HashMap<>(scope.params());
    periodMetricsParams.put("fromDate", fromDate);
    periodMetricsParams.put("toDate", toDate);
    SqlFragment periodMetricsCte = SqlFragment.of(periodMetricsSql, periodMetricsParams).asCte("period_metrics");

    SqlFragment combined =
        SqlFragment.combine(List.of(trendPeriods.periodsCte(), periodMetricsCte), SqlFragment.of(sql.load(GET_TRANSACTION_VOLUME_TREND_SQL)));
    return jdbc.query(combined.sql(), combined.parameterSource(), VOLUME_TREND_ROW_MAPPER);
  }

  /**
   * The shared {@code batch_scope}/{@code batch_evidence}/{@code roll} CTE chain behind {@link
   * #getTransactionOverview}, {@link #getTopExclusionReasons} and {@link #getNotReportedReasons}:
   * rolls up every journey event (not just the latest-state row) per {@code (rpt_grp_id,
   * identifier)} into {@code ever_excluded}/{@code ever_reported}, joined against {@code
   * report_batch_info} to confirm a batch's report was actually generated (not merely that its
   * transformation step succeeded) before counting a transaction as reported. {@code
   * extraReasonColumn} lets {@link #getTopExclusionReasons}/{@link #getNotReportedReasons} add
   * their own {@code reason} column onto the same roll without {@link #getTransactionOverview}
   * paying for it.
   */
  private List<SqlFragment> journeyRollCtes(SqlFragment scope, String extraReasonColumn) {
    SqlFragment batchScopeCte = SqlFragment.of(sql.load(BATCH_SCOPE_SQL) + scope.sql(), scope.params()).asCte("batch_scope");
    SqlFragment batchEvidenceCte = SqlFragment.of(sql.load(BATCH_EVIDENCE_SQL)).asCte("batch_evidence");
    SqlFragment rollCte = SqlFragment.of(sql.load(JOURNEY_ROLL_SQL).replace("%%EXTRA_COLUMN%%", extraReasonColumn)).asCte("roll");
    return List.of(batchScopeCte, batchEvidenceCte, rollCte);
  }

  @SqlQueryPurpose("Transactions Overview > Selected / Expected / Excluded / Not Reported KPI cards > Aggregate transaction evidence")
  public TransactionOverviewProjection getTransactionOverview(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    List<SqlFragment> ctes = journeyRollCtes(scope, "");

    SqlFragment combined = SqlFragment.combine(ctes, SqlFragment.of(sql.load(GET_TRANSACTION_OVERVIEW_SQL)));
    return jdbc
      .queryForOptional(combined.sql(), combined.parameterSource(), OVERVIEW_ROW_MAPPER)
      .orElseThrow(() -> new IllegalStateException("Transaction overview aggregate returned no row"));
  }

  /**
   * Groups {@code roll}'s rows matching {@code filterSql} by its {@code reason} column, keeps the
   * top 3 by count, and collapses every remaining group into a single {@code "Other"} row --
   * shared by {@link #getTopExclusionReasons} and {@link #getNotReportedReasons}.
   */
  private List<SqlFragment> topReasonsCtes(String filterSql) {
    SqlFragment reasonCountsCte = SqlFragment.of(sql.load(REASON_COUNTS_SQL).replace("%%FILTER%%", filterSql)).asCte("reason_counts");
    SqlFragment rankedReasonsCte = SqlFragment.of(sql.load(RANKED_REASONS_SQL)).asCte("ranked_reasons");
    SqlFragment bucketedReasonsCte = SqlFragment.of(sql.load(BUCKETED_REASONS_SQL)).asCte("bucketed_reasons");
    return List.of(reasonCountsCte, rankedReasonsCte, bucketedReasonsCte);
  }

  private <T> List<T> queryReasons(SqlFragment scope, String extraReasonColumn, String filterSql,
      java.util.function.BiFunction<String, Long, T> factory) {
    List<SqlFragment> allCtes = new ArrayList<>(journeyRollCtes(scope, extraReasonColumn));
    allCtes.addAll(topReasonsCtes(filterSql));
    SqlFragment combined = SqlFragment.combine(allCtes, SqlFragment.of(sql.load(TOP_REASONS_FINAL_SQL)));
    return jdbc.query(combined.sql(), combined.parameterSource(), (rs, rowNum) -> factory.apply(rs.getString("reason"), rs.getLong("count")));
  }

  /**
   * The same journey-derived "excluded" bucket as {@link #getTransactionOverview}'s {@code
   * excluded} count, broken out by reason instead of collapsed to one total. Each excluded
   * identifier's reason is {@code comments} (falling back to {@code skip_reason} when null) --
   * {@code comments} leads because {@code skip_reason} is frequently a verbose, per-record
   * exception payload that embeds a record-specific index, so two rows with the exact same
   * underlying issue still fail to group together.
   */
  @SqlQueryPurpose("Transactions Overview > Top Exclusion Reasons chart > Aggregate excluded transactions by reason")
  public List<ExclusionReasonProjection> getTopExclusionReasons(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    String extraReasonColumn =
        ", max(case when " + EXCLUDED_STATUSES_SQL + " then coalesce(journey.comments, journey.skip_reason) end) as reason";
    return queryReasons(scope, extraReasonColumn, "ever_excluded and not ever_reported", ExclusionReasonProjection::new);
  }

  /**
   * Breaks the same journey-derived "not reported" bucket {@link #getTransactionOverview}'s {@code
   * notReported} counts down by *why* -- the same {@code comments} (falling back to {@code
   * skip_reason}) free text {@link #getTopExclusionReasons} uses, just taken from the
   * lexicographically-greatest non-null value across an identifier's *entire* journey (not just
   * its EXCLUDED rows, since a not-reported identifier is never excluded by definition).
   */
  @SqlQueryPurpose("Transactions Overview > Not Reported Breakdown chart > Aggregate transactions by reason")
  public List<NotReportedReasonProjection> getNotReportedReasons(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    String extraReasonColumn = ", max(coalesce(journey.comments, journey.skip_reason)) as reason";
    return queryReasons(scope, extraReasonColumn, "not ever_reported and not ever_excluded", NotReportedReasonProjection::new);
  }
}
