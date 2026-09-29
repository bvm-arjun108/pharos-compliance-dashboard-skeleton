package com.wu.compliance.dashboard.dashboard.repository;

import com.wu.compliance.dashboard.common.jdbc.TransformationFailureQueries;
import com.wu.compliance.dashboard.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlFragment;
import com.wu.compliance.dashboard.common.jdbc.sql.SqlResourceLoader;
import com.wu.compliance.dashboard.common.jdbc.logging.SqlQueryPurpose;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchHealthTrendProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.BatchSnapshotRowProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.DashboardCountsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ExclusionReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.NotReportedReasonProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.ReportGroupMetricsProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionDashboardSnapshotProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionOverviewProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionSnapshotRowProjection;
import com.wu.compliance.dashboard.dashboard.repository.projection.TransactionVolumeTrendProjection;
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
  private static final String SCOPED_BATCH_METRICS_SQL = "sql/dashboard/scoped-batch-metrics.sql";
  private static final String REPORT_GROUP_METRICS_SQL = "sql/dashboard/report-group-metrics.sql";
  private static final String GET_BATCH_DASHBOARD_SNAPSHOT_SQL = "sql/dashboard/get-batch-dashboard-snapshot.sql";
  private static final String GET_BATCH_HEALTH_TREND_SQL = "sql/dashboard/get-batch-health-trend.sql";
  private static final String GET_TRANSACTION_VOLUME_TREND_SQL = "sql/dashboard/get-transaction-volume-trend.sql";
  private static final String BATCH_SCOPE_SQL = "sql/dashboard/batch-scope.sql";
  private static final String BATCH_EVIDENCE_SQL = "sql/dashboard/batch-evidence.sql";
  private static final String JOURNEY_ROLL_SQL = "sql/dashboard/journey-roll.sql";
  private static final String GET_TRANSACTION_OVERVIEW_SQL = "sql/dashboard/get-transaction-overview.sql";
  private static final String EXCLUSION_REASON_COUNTS_SQL = "sql/dashboard/exclusion-reason-counts.sql";
  private static final String EXCLUSION_RANKED_REASONS_SQL = "sql/dashboard/exclusion-ranked-reasons.sql";
  private static final String EXCLUSION_BUCKETED_REASONS_SQL = "sql/dashboard/exclusion-bucketed-reasons.sql";
  private static final String NOT_REPORTED_REASON_COUNTS_SQL = "sql/dashboard/not-reported-reason-counts.sql";
  private static final String NOT_REPORTED_RANKED_REASONS_SQL = "sql/dashboard/not-reported-ranked-reasons.sql";
  private static final String NOT_REPORTED_BUCKETED_REASONS_SQL = "sql/dashboard/not-reported-bucketed-reasons.sql";
  private static final String GET_TRANSACTION_DASHBOARD_SNAPSHOT_SQL = "sql/dashboard/get-transaction-dashboard-snapshot.sql";
  private static final String OVERVIEW_ROW = "OVERVIEW";
  private static final String EXCLUSION_REASON_ROW = "EXCLUSION_REASON";
  private static final String NOT_REPORTED_REASON_ROW = "NOT_REPORTED_REASON";
  private static final DashboardCountsProjection EMPTY_COUNTS = new DashboardCountsProjection(0, 0, 0, 0, 0, 0, 0, 0, 0);
  private static final RowMapper<BatchSnapshotRowProjection> BATCH_SNAPSHOT_ROW_MAPPER =
      (rs, rowNum) -> {
    DashboardCountsProjection counts = new DashboardCountsProjection(rs.getLong("overallBatchesRan"),
        rs.getLong("overallBatchesNeedingAttention"), rs.getLong("overallTransformationFailureBatches"),
        rs.getLong("overallMissingAttemptBatches"), rs.getLong("overallActivityMissingBatches"),
        rs.getLong("overallDuplicateTransactionBatches"), rs.getLong("overallExclusionBatches"),
        rs.getLong("overallSimulatedTransactionBatches"), rs.getLong("overallSoftDedupBatches"));
    ReportGroupMetricsProjection reportGroup = new ReportGroupMetricsProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
        rs.getLong("batchesRan"), rs.getLong("successfulBatches"), rs.getLong("batchesNeedingAttention"),
        rs.getLong("transformationFailureBatches"), rs.getLong("missingAttemptBatches"), rs.getLong("activityMissingBatches"),
        rs.getLong("totalReportedTransactions"), rs.getLong("totalExcludedTransactions"));
    return new BatchSnapshotRowProjection(counts, reportGroup);
  };
  private static final RowMapper<BatchHealthTrendProjection> BATCH_HEALTH_TREND_ROW_MAPPER =
      (rs, rowNum) -> new BatchHealthTrendProjection(rs.getObject("periodStart", LocalDate.class), rs.getLong("batchesRan"),
          rs.getLong("successfulBatches"), rs.getLong("batchesNeedingAttention"));
  private static final RowMapper<TransactionVolumeTrendProjection> VOLUME_TREND_ROW_MAPPER =
      (rs, rowNum) -> new TransactionVolumeTrendProjection(rs.getObject("periodStart", LocalDate.class),
          rs.getLong("totalReportedTransactions"), rs.getLong("totalExcludedTransactions"));
  private static final RowMapper<TransactionSnapshotRowProjection> TRANSACTION_SNAPSHOT_ROW_MAPPER =
      (rs, rowNum) -> new TransactionSnapshotRowProjection(rs.getString("rowType"), rs.getLong("selected"), rs.getLong("expected"),
          rs.getLong("excluded"), rs.getLong("notReported"), rs.getString("reason"), rs.getLong("count"));
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

  /**
   * Loads the Batch View headline counts and report-group table from one scoped aggregation. The
   * overall counters are window sums of the report-group counters: because a reconciliation row's
   * primary key starts with {@code rpt_grp_id}, every batch belongs to exactly one group and these
   * sums are identical to aggregating the same scoped rows a second time globally.
   *
   * <p>For the broad, unfiltered dashboard, clean report groups remain part of the window totals
   * but are removed from the returned investigation table. Country/report-group scopes retain all
   * groups exactly as the previous dedicated report-group query did.
   */
  @SqlQueryPurpose("Batch View > KPI cards and report-group table > Load the shared batch snapshot")
  public BatchDashboardSnapshotProjection getBatchDashboardSnapshot(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");
    SqlFragment scopedBatchMetricsCte = scopedBatchMetricsCte(scope);
    SqlFragment reportGroupMetricsCte = SqlFragment.of(sql.load(REPORT_GROUP_METRICS_SQL)).asCte("report_group_metrics");
    SqlFragment combined = SqlFragment.combine(List.of(journeyFailuresCte, scopedBatchMetricsCte, reportGroupMetricsCte),
        SqlFragment.of(sql.load(GET_BATCH_DASHBOARD_SNAPSHOT_SQL)));
    List<BatchSnapshotRowProjection> rows = jdbc.query(combined.sql(), combined.parameterSource(), BATCH_SNAPSHOT_ROW_MAPPER);
    DashboardCountsProjection counts = rows.isEmpty() ? EMPTY_COUNTS : rows.getFirst().counts();
    boolean includeCleanGroups = filterByReportGroup || filterByCountry;
    List<ReportGroupMetricsProjection> reportGroups = rows
      .stream()
      .map(BatchSnapshotRowProjection::reportGroup)
      .filter(group -> includeCleanGroups || group.batchesNeedingAttention() > 0)
      .toList();
    return new BatchDashboardSnapshotProjection(counts, reportGroups);
  }

  private SqlFragment scopedBatchMetricsCte(SqlFragment scope) {
    String body = sql.load(SCOPED_BATCH_METRICS_SQL) + scope.sql();
    return SqlFragment.of(body, scope.params()).asCte("scoped_batch_metrics");
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
  private TrendPeriods buildTrendPeriods(String granularity, LocalDate fromDate, LocalDate toDate, String timestampColumn) {
    String periodsSql = switch (granularity) {
      case "DAILY" -> "select generate_series(:fromDate::date, :toDate::date, interval '1 day')::date as period_start";
      case "WEEKLY" -> "select generate_series(:fromDate::date, :toDate::date, interval '7 days')::date as period_start";
      default -> "select generate_series(date_trunc('month', :fromDate::date), :toDate::date, interval '1 month')::date as period_start";
    };
    // fromDate + (((createdDate - fromDate) / 7) * 7): floor the day offset from fromDate down to
    // the nearest whole week, reproducing the original's integer-division bucketing exactly.
    String periodStartExpr = switch (granularity) {
      case "DAILY" -> timestampColumn + "::date";
      case "WEEKLY" -> "(:fromDate::date + (((" + timestampColumn + "::date - :fromDate::date) / 7) * 7))";
      default -> "date_trunc('month', " + timestampColumn + ")::date";
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
    TrendPeriods trendPeriods = buildTrendPeriods(granularity, fromDate, toDate, "sbm.created_timestamp");
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");
    SqlFragment scopedBatchMetricsCte = scopedBatchMetricsCte(scope);

    String periodMetricsSql = "select "
        + trendPeriods.periodStartExpr()
        + " as period_start,\n"
        + "  count(*)::bigint as batches_ran,\n"
        + "  count(*) filter (where sbm.needs_attention)::bigint as batches_needing_attention\n"
        + "from scoped_batch_metrics sbm"
        // Group by the SELECT list's ordinal position, not periodStartExpr's own text repeated: the
    // WEEKLY branch embeds :fromDate, and NamedParameterJdbcTemplate expands each textual
    // occurrence of a named parameter to its own distinct positional placeholder, so a second
    // copy of the same expression here would bind a different parameter marker than the SELECT
    // list's copy -- Postgres then treats them as different expressions and rejects the query
    // ("column must appear in the GROUP BY clause"), even though both hold the same value.
    + "\ngroup by 1";
    Map<String, Object> periodMetricsParams = new HashMap<>();
    periodMetricsParams.put("fromDate", fromDate);
    periodMetricsParams.put("toDate", toDate);
    SqlFragment periodMetricsCte = SqlFragment.of(periodMetricsSql, periodMetricsParams).asCte("period_metrics");

    SqlFragment combined = SqlFragment.combine(List.of(journeyFailuresCte, scopedBatchMetricsCte, trendPeriods.periodsCte(),
            periodMetricsCte), SqlFragment.of(sql.load(GET_BATCH_HEALTH_TREND_SQL)));
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
    TrendPeriods trendPeriods = buildTrendPeriods(granularity, fromDate, toDate, "r.created_timestamp");
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
   * Builds the expensive scope/evidence/journey aggregation once for every non-trend component
   * on the Transactions Overview page. The roll evaluates every journey event per transaction,
   * derives its reported/excluded state, and captures both reason variants in the same grouped
   * pass so the three downstream sections never rescan the journey table independently.
   */
  private List<SqlFragment> transactionRollCtes(SqlFragment scope) {
    SqlFragment batchScopeCte = SqlFragment.of(sql.load(BATCH_SCOPE_SQL) + scope.sql(), scope.params()).asCte("batch_scope");
    SqlFragment batchEvidenceCte = SqlFragment.of(sql.load(BATCH_EVIDENCE_SQL)).asCte("batch_evidence");
    SqlFragment rollCte = SqlFragment.of(sql.load(JOURNEY_ROLL_SQL)).asCte("transaction_roll");
    return List.of(batchScopeCte, batchEvidenceCte, rollCte);
  }

  /**
   * Top-three-plus-Other reason pipeline for exclusion reasons over the shared transaction roll.
   * {@code get-transaction-dashboard-snapshot.sql} references {@code exclusion_bucketed_reasons} by
   * this exact name.
   */
  private List<SqlFragment> exclusionReasonBreakdownCtes() {
    return List.of(SqlFragment.of(sql.load(EXCLUSION_REASON_COUNTS_SQL)).asCte("exclusion_reason_counts"),
        SqlFragment.of(sql.load(EXCLUSION_RANKED_REASONS_SQL)).asCte("exclusion_ranked_reasons"),
        SqlFragment.of(sql.load(EXCLUSION_BUCKETED_REASONS_SQL)).asCte("exclusion_bucketed_reasons"));
  }

  /**
   * Top-three-plus-Other reason pipeline for not-reported reasons over the shared transaction roll.
   * {@code get-transaction-dashboard-snapshot.sql} references {@code not_reported_bucketed_reasons}
   * by this exact name.
   */
  private List<SqlFragment> notReportedReasonBreakdownCtes() {
    return List.of(SqlFragment.of(sql.load(NOT_REPORTED_REASON_COUNTS_SQL)).asCte("not_reported_reason_counts"),
        SqlFragment.of(sql.load(NOT_REPORTED_RANKED_REASONS_SQL)).asCte("not_reported_ranked_reasons"),
        SqlFragment.of(sql.load(NOT_REPORTED_BUCKETED_REASONS_SQL)).asCte("not_reported_bucketed_reasons"));
  }

  /**
   * Loads the Transaction View KPI totals and both reason charts from one shared journey roll.
   * The final tagged row set is split back into the same projections the service previously
   * received from three repository calls; the public response and ordering remain unchanged.
   */
  @SqlQueryPurpose("Transactions Overview > KPI cards and reason charts > Load the shared transaction snapshot")
  public TransactionDashboardSnapshotProjection getTransactionDashboardSnapshot(LocalDateTime fromTimestamp,
      LocalDateTime toTimestampExclusive, String batchId, boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup,
      int reportGroupId) {
    SqlFragment scope = reconciliationScope("r.", fromTimestamp, toTimestampExclusive, batchId, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId);
    List<SqlFragment> ctes = new ArrayList<>(transactionRollCtes(scope));
    ctes.add(SqlFragment.of(sql.load(GET_TRANSACTION_OVERVIEW_SQL)).asCte("transaction_overview"));
    ctes.addAll(exclusionReasonBreakdownCtes());
    ctes.addAll(notReportedReasonBreakdownCtes());

    SqlFragment combined = SqlFragment.combine(ctes, SqlFragment.of(sql.load(GET_TRANSACTION_DASHBOARD_SNAPSHOT_SQL)));
    List<TransactionSnapshotRowProjection> rows = jdbc.query(combined.sql(), combined.parameterSource(), TRANSACTION_SNAPSHOT_ROW_MAPPER);
    TransactionOverviewProjection overview = null;
    List<ExclusionReasonProjection> exclusionReasons = new ArrayList<>();
    List<NotReportedReasonProjection> notReportedReasons = new ArrayList<>();
    for (TransactionSnapshotRowProjection row : rows) {
      switch (row.rowType()) {
        case OVERVIEW_ROW -> overview = new TransactionOverviewProjection(row.selected(), row.expected(), row.excluded(), row.notReported());
        case EXCLUSION_REASON_ROW -> exclusionReasons.add(new ExclusionReasonProjection(row.reason(), row.count()));
        case NOT_REPORTED_REASON_ROW -> notReportedReasons.add(new NotReportedReasonProjection(row.reason(), row.count()));
        default -> throw new IllegalStateException("Unknown transaction snapshot row type: " + row.rowType());
      }
    }
    if (overview == null) {
      throw new IllegalStateException("Transaction dashboard snapshot returned no overview row");
    }
    return new TransactionDashboardSnapshotProjection(overview, List.copyOf(exclusionReasons), List.copyOf(notReportedReasons));
  }
}
