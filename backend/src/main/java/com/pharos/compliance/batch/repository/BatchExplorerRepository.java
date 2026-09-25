package com.pharos.compliance.batch.repository;

import com.pharos.compliance.batch.repository.projection.BatchDetailsProjection;
import com.pharos.compliance.batch.repository.projection.BatchQueueProjection;
import com.pharos.compliance.batch.repository.projection.BatchSummaryProjection;
import com.pharos.compliance.common.jdbc.TransformationFailureQueries;
import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch Explorer summary, queue and selected-batch queries.
 */
@Repository
@Transactional(readOnly = true)
public class BatchExplorerRepository {
  private static final String BATCH_METRICS_SQL = "sql/batch/batch-metrics.sql";
  private static final String ENRICHED_BATCH_METRICS_SQL = "sql/batch/enriched-batch-metrics.sql";
  private static final String GET_BATCH_SUMMARY_SQL = "sql/batch/get-batch-summary.sql";
  private static final String BATCH_QUEUE_SQL = "sql/batch/batch-queue.sql";
  private static final String BATCH_DETAILS_SQL = "sql/batch/batch-details.sql";
  private static final RowMapper<BatchSummaryProjection> SUMMARY_ROW_MAPPER =
      (rs, rowNum) -> new BatchSummaryProjection(rs.getLong("allBatches"), rs.getLong("successfulBatches"), rs.getLong("attentionBatches"),
          rs.getLong("activityMissingBatches"), rs.getLong("missingAttemptBatches"), rs.getLong("transformationBatches"),
          rs.getLong("duplicateTransactionBatches"), rs.getLong("exclusionBatches"), rs.getLong("simulatedTransactionBatches"),
          rs.getLong("softDedupBatches"),
          rs.getString("reportGroupName"));
  private static final RowMapper<BatchQueueProjection> QUEUE_ROW_MAPPER =
      (rs, rowNum) -> new BatchQueueProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"), rs.getString("batchId"),
          rs.getInt("sequenceNumber"), rs.getString("reportingPeriodFrom"), rs.getString("reportingPeriodTo"),
          rs.getObject("startedAt", LocalDateTime.class), rs.getObject("completedAt", LocalDateTime.class),
          rs.getLong("transformationFailures"), rs.getLong("reportedTransformationFailures"), rs.getBoolean("transformationFailureMismatch"),
          rs.getLong("missingAttempts"), rs.getLong("activityMissing"), rs.getLong("filtrationErrors"),
          rs.getLong("reconciliationImbalance"), rs.getLong("transformerOutput"), rs.getLong("excludedTransactions"),
          rs.getLong("duplicateTransactions"), rs.getLong("simulatedTransactions"), rs.getLong("softDedupTransactions"),
          rs.getLong("totalIssues"), rs.getLong("matchingCount"));
  private static final RowMapper<BatchDetailsProjection> DETAILS_ROW_MAPPER =
      (rs, rowNum) -> new BatchDetailsProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"), rs.getString("batchId"),
          rs.getInt("sequenceNumber"), rs.getString("reportingPeriodFrom"), rs.getString("reportingPeriodTo"),
          rs.getObject("startedAt", LocalDateTime.class), rs.getObject("completedAt", LocalDateTime.class),
          rs.getLong("transformationFailures"), rs.getLong("reportedTransformationFailures"), rs.getBoolean("transformationFailureMismatch"),
          rs.getLong("missingAttempts"), rs.getLong("activityMissing"), rs.getLong("duplicateTransactions"), rs.getLong("filtrationErrors"),
          rs.getLong("reconciliationImbalance"), rs.getLong("selectedTransactions"), rs.getLong("transactionAttemptsFound"),
          rs.getLong("expectedReportableTransactions"), rs.getLong("actualReportableTransactions"),
          rs.getLong("expectedTransformationAttempts"), rs.getLong("actualTransformationAttempts"), rs.getLong("transformedActivities"),
          rs.getLong("transformerOutput"), rs.getLong("excludedTransactions"), rs.getLong("simulatedTransactions"),
          rs.getLong("alreadyReportedTransactions"), rs.getLong("softDedupTransactions"), rs.getBoolean("journeyAvailable"),
          rs.getBoolean("exclusionsAvailable"), (Integer) rs.getObject("reportSelectionVersionId"), rs.getString("transformerVersionId"));
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;

  public BatchExplorerRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
  }

  /**
   * The date-range/batchId-search/reportGroupId/country filters shared by {@link
   * #enrichedBatchMetricsCtes}' {@code batch_metrics} CTE and (via {@link
   * TransformationFailureQueries#journeyFailuresByBatch}) its {@code journey_failures_by_batch}
   * sibling -- the same scope condition text/params reused in both places, exactly as the jOOQ
   * version reused one {@code Condition} object. Rendered as bare {@code and ...} lines (no leading
   * {@code where}) so both consumers can append them after their own {@code where 1 = 1}.
   */
  private SqlFragment reconciliationScope(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    StringBuilder cond = new StringBuilder();
    Map<String, Object> params = new HashMap<>();
    cond.append("\n  and created_timestamp >= :fromTimestamp");
    cond.append("\n  and created_timestamp < :toTimestampExclusive");
    params.put("fromTimestamp", fromTimestamp);
    params.put("toTimestampExclusive", toTimestampExclusive);

    if (batchId != null && !batchId.isEmpty()) {
      cond.append("\n  and lower(batch_id) like lower(:batchIdPattern)");
      params.put("batchIdPattern", "%" + batchId + "%");
    }
    if (reportGroupId != null) {
      cond.append("\n  and rpt_grp_id = :reportGroupId");
      params.put("reportGroupId", reportGroupId);
    }
    if (filterByCountry) {
      if (reportGroupIds.isEmpty()) {
        // Mirrors jOOQ's own defensive handling of Field.in(emptyCollection): an empty IN-list
        // would either be invalid SQL or (with jOOQ) silently render as always-false -- do the
        // same here explicitly rather than attempt "in ()".
        cond.append("\n  and 1 = 0");
      } else {
        cond.append("\n  and rpt_grp_id in (:reportGroupIds)");
        params.put("reportGroupIds", reportGroupIds);
      }
    }
    return SqlFragment.of(cond.toString(), params);
  }

  /**
   * The three CTEs ({@code batch_metrics}, {@code journey_failures_by_batch}, {@code
   * enriched_batch_metrics}) shared by {@link #getBatchSummary} and {@link #getBatchQueue} --
   * each combines these with its own final SELECT via {@link SqlFragment#combine}.
   */
  private List<SqlFragment> enrichedBatchMetricsCtes(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    SqlFragment scope = reconciliationScope(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    SqlFragment batchMetricsCte =
        SqlFragment.of(sql.load(BATCH_METRICS_SQL).replace("/*SCOPE*/", scope.sql()), scope.params()).asCte("batch_metrics");
    SqlFragment journeyFailuresCte = TransformationFailureQueries.journeyFailuresByBatch(sql, scope).asCte("journey_failures_by_batch");
    SqlFragment enrichedCte = SqlFragment.of(sql.load(ENRICHED_BATCH_METRICS_SQL)).asCte("enriched_batch_metrics");
    return List.of(batchMetricsCte, journeyFailuresCte, enrichedCte);
  }

  @SqlQueryPurpose("Summarize batches matching the Batch Explorer filters")
  public BatchSummaryProjection getBatchSummary(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds) {
    var ctes = enrichedBatchMetricsCtes(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);
    SqlFragment combined = SqlFragment.combine(ctes, SqlFragment.of(sql.load(GET_BATCH_SUMMARY_SQL)));
    return jdbc
      .queryForOptional(combined.sql(), combined.parameterSource(), SUMMARY_ROW_MAPPER)
      .orElseThrow(() -> new IllegalStateException("Batch summary aggregate returned no row"));
  }

  @SqlQueryPurpose("Load the paginated batch investigation queue")
  public List<BatchQueueProjection> getBatchQueue(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, String batchId,
      Integer reportGroupId, boolean filterByCountry, List<Integer> reportGroupIds, String status, String issueType, String metricFocus,
      int size, long offset) {
    var ctes = enrichedBatchMetricsCtes(fromTimestamp, toTimestampExclusive, batchId, reportGroupId, filterByCountry, reportGroupIds);

    String statusCondition = switch (status) {
      case "ALL" -> "";
      case "SUCCESSFUL" -> "\n  and total_issues = 0";
      case "ATTENTION" -> "\n  and total_issues > 0";
      default -> "\n  and 1 = 0";
    };
    String issueTypeCondition = switch (issueType) {
      case "ALL" -> "";
      case "ACTIVITY_MISSING" -> "\n  and activity_missing > 0";
      case "MISSING_ATTEMPTS" -> "\n  and missing_attempts > 0";
      case "TRANSFORMATION" -> "\n  and transformation_failures > 0";
      case "DUPLICATE_TRANSFORMATION" -> "\n  and duplicate_transactions > 0";
      case "EXCLUSION" -> "\n  and excluded_transactions > 0";
      case "SIMULATED" -> "\n  and simulated_transactions > 0";
      case "SOFT_DEDUP" -> "\n  and soft_dedup_transactions > 0";
      default -> "\n  and 1 = 0";
    };
    String metricFocusCondition = switch (metricFocus) {
      case "DEFAULT" -> "";
      case "REPORTED" -> "\n  and transformer_output > 0";
      case "EXCLUDED" -> "\n  and excluded_transactions > 0";
      default -> "\n  and 1 = 0";
    };
    // The original SQL's metricFocus sort keys are conditional CASE-WHEN expressions that only
    // affect ordering when they match, and metricFocus is already known in Java before this query
    // is built -- so only add the matching sort key at all, rather than emit a same-value CASE for
    // every row.
    StringBuilder orderBy = new StringBuilder();
    if ("REPORTED".equals(metricFocus)) {
      orderBy.append("transformer_output desc nulls last, ");
    }
    if ("EXCLUDED".equals(metricFocus)) {
      orderBy.append("excluded_transactions desc nulls last, ");
    }
    orderBy.append("modified_timestamp desc nulls last, created_timestamp desc nulls last, batch_id asc");

    String body = sql
      .load(BATCH_QUEUE_SQL)
      .replace("/*STATUS_CONDITION*/", statusCondition)
      .replace("/*ISSUE_TYPE_CONDITION*/", issueTypeCondition)
      .replace("/*METRIC_FOCUS_CONDITION*/", metricFocusCondition)
      .replace("/*ORDER_BY*/", orderBy.toString());
    Map<String, Object> bodyParams = new HashMap<>();
    bodyParams.put("size", size);
    bodyParams.put("offset", offset);

    SqlFragment combined = SqlFragment.combine(ctes, SqlFragment.of(body, bodyParams));
    return jdbc.query(combined.sql(), combined.parameterSource(), QUEUE_ROW_MAPPER);
  }

  @SqlQueryPurpose("Selected batch > Data Selection, Data Transformation and Reconciliation cards > Load aggregate counters and evidence "
      + "availability")
  public Optional<BatchDetailsProjection> getBatchDetails(int reportGroupId, String batchId, int sequenceNumber) {
    // A LATERAL join computes journeyAvailable and the journey-derived failure count exactly once
    // per row -- both are then plain column references, safe to reuse across the CASE and mismatch
    // expressions without Postgres re-evaluating the underlying journey-table subquery each time
    // (see TransformationFailureQueries#journeyStatsLateral's Javadoc).
    SqlFragment journeyStats = TransformationFailureQueries.journeyStatsLateral(sql, "r.rpt_grp_id", "r.batch_id");
    String body = sql.load(BATCH_DETAILS_SQL).replace("/*JOURNEY_STATS_LATERAL*/", journeyStats.sql());
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("reportGroupId", reportGroupId)
      .addValue("batchId", batchId)
      .addValue("sequenceNumber", sequenceNumber);
    return jdbc.queryForOptional(body, params, DETAILS_ROW_MAPPER);
  }
}
