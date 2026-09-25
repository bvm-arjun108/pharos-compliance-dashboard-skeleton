package com.pharos.compliance.transaction.repository;

import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_EXCLUDED;
import static com.pharos.compliance.transaction.repository.evidence.EvidenceColumns.VALUE_NOT_REPORTED;
import com.pharos.compliance.common.jdbc.TransformationFailureQueries;
import com.pharos.compliance.common.jdbc.logging.TracingNamedParameterJdbcTemplate;
import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.evidence.jdbc.BatchEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.jdbc.EvidencePaginator;
import com.pharos.compliance.transaction.repository.evidence.jdbc.OverviewEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.jdbc.PeriodEvidenceQueries;
import com.pharos.compliance.transaction.repository.evidence.jdbc.RuleHitMatcher;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import com.pharos.compliance.transaction.repository.projection.PeriodAggregateProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionEvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.TransactionReportContextProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin facade over the transaction-evidence query pipelines, kept as the single Spring bean and
 * public contract this package exposes (unchanged by the split below -- {@link
 * TransactionEvidenceCache} calls the exact same 6 methods it always has). Every "transaction
 * evidence" row is a merge across three possible sources for the same underlying transaction:
 * JOURNEY (record_transformation_journey), EXCLUSION_AUDIT (rule_hit_exclusion_audit), and
 * RULE_HIT (rule_hit, resolved to its journey identifier via an external_txn_key/mtcn bridge). The
 * actual query construction lives in {@code com.pharos.compliance.transaction.repository.evidence},
 * split by pipeline so each is readable on its own instead of interleaved in one file:
 *
 * <ul>
 *   <li>{@link EvidencePaginator} -- the shared two-pass pagination + 27-column priority merge
 *       engine every pipeline below uses, entirely independent of where the evidence rows came
 *       from.
 *   <li>{@link RuleHitMatcher} -- the shared join-based rule_hit-to-journey-identifier resolution.
 *   <li>{@link BatchEvidenceQueries} -- one reconciliation batch's evidence (Batch Explorer
 *       drilldowns): {@link #findEvidenceRecords}/{@link #countEvidenceRecords}.
 *   <li>{@link PeriodEvidenceQueries} -- every batch in a date range (Transactions Overview
 *       drilldowns) for every status except EXCLUDED/NOT_REPORTED.
 *   <li>{@link OverviewEvidenceQueries} -- EXCLUDED/NOT_REPORTED specifically, answered from a
 *       transaction's outcome across every batch in the caller's window rather than one batch's
 *       evidence rows; {@link
 *       #findPeriodEvidenceRecords}/{@link #countPeriodEvidenceRecords} route to this instead of
 *       {@link PeriodEvidenceQueries} for those two statuses, exactly as before the split.
 * </ul>
 */
@Repository
@Transactional(readOnly = true)
public class TransactionReportRepository {
  private static final String REPORT_CONTEXT_SQL = "sql/transaction/report-context.sql";
  private static final RowMapper<TransactionReportContextProjection> REPORT_CONTEXT_ROW_MAPPER =
      (rs, rowNum) -> new TransactionReportContextProjection(rs.getInt("reportGroupId"), rs.getString("reportGroupName"),
          rs.getString("batchId"), rs.getInt("sequenceNumber"), rs.getString("reportingPeriodFrom"), rs.getString("reportingPeriodTo"),
          rs.getLong("selectedTransactions"), rs.getLong("attemptsFound"), rs.getLong("missingAttempts"), rs.getLong("activityMissing"),
          rs.getLong("expectedEligible"), rs.getLong("actualEligible"), rs.getLong("transformed"), rs.getLong("failed"),
          rs.getLong("reportedFailed"), rs.getBoolean("failedMismatch"), rs.getLong("expectedReportable"), rs.getLong("actualReportable"),
          rs.getLong("excluded"), rs.getLong("simulated"), rs.getLong("alreadyReported"), rs.getLong("softDedup"),
          rs.getLong("filtrationVariance"), rs.getLong("reconciliationVariance"));
  private final TracingNamedParameterJdbcTemplate jdbc;
  private final SqlResourceLoader sql;
  private final BatchEvidenceQueries batchEvidenceQueries;
  private final PeriodEvidenceQueries periodEvidenceQueries;
  private final OverviewEvidenceQueries overviewEvidenceQueries;

  public TransactionReportRepository(TracingNamedParameterJdbcTemplate jdbc, SqlResourceLoader sql) {
    this.jdbc = jdbc;
    this.sql = sql;
    EvidencePaginator paginator = new EvidencePaginator(jdbc, sql);
    RuleHitMatcher ruleHitMatcher = new RuleHitMatcher();
    this.batchEvidenceQueries = new BatchEvidenceQueries(sql, ruleHitMatcher, paginator);
    this.periodEvidenceQueries = new PeriodEvidenceQueries(jdbc, sql, ruleHitMatcher, paginator);
    this.overviewEvidenceQueries = new OverviewEvidenceQueries(jdbc, sql, paginator, periodEvidenceQueries);
  }

  @SqlQueryPurpose("Load transaction reconciliation context for one batch")
  public Optional<TransactionReportContextProjection> findReportContext(int reportGroupId, String batchId, int sequenceNumber) {
    // See TransformationFailureQueries' Javadoc: activity_transformation_failed can disagree with
    // what record_transformation_journey actually recorded, so "failed" (and SKIPPED, which sums
    // it in) is corrected to the journey-derived count whenever journey has any coverage for this
    // batch, falling back to the raw reconciliation scalar otherwise -- exactly mirroring
    // BatchExplorerRepository#getBatchDetails. The LATERAL join computes both journeyAvailable and
    // the journey-derived count once per row; report-context.sql references them as plain column
    // references, safe to reuse across the CASE and mismatch expressions.
    SqlFragment journeyStats = TransformationFailureQueries.journeyStatsLateral(sql, "r.rpt_grp_id", "r.batch_id");
    String body = sql.load(REPORT_CONTEXT_SQL).replace("/*JOURNEY_STATS_LATERAL*/", journeyStats.sql());
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("reportGroupId", reportGroupId)
      .addValue("batchId", batchId)
      .addValue("sequenceNumber", sequenceNumber);
    return jdbc.queryForOptional(body, params, REPORT_CONTEXT_ROW_MAPPER);
  }

  @SqlQueryPurpose("Load paginated transaction evidence for one batch")
  public EvidencePage findEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status, String sortDirection, int size, long offset, EvidenceCursor cursor) {
    return batchEvidenceQueries.findEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status, sortDirection,
        size, offset, cursor, EvidenceProjection.LIST);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records for one batch")
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    return batchEvidenceQueries.countEvidenceRecords(reportGroupId, batchId, metric, search, source, stage, outcome, status);
  }

  @SqlQueryPurpose("Summarize transaction evidence across the selected reporting period")
  public PeriodAggregateProjection findPeriodAggregate(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId) {
    return periodEvidenceQueries.findPeriodAggregate(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
  }

  @SqlQueryPurpose("List every distinct batch ID in a period-report scope")
  public List<String> findPeriodBatchIds(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId) {
    SqlFragment scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, "");
    return periodEvidenceQueries.distinctBatchIds(scope);
  }

  /**
   * Routes EXCLUDED/NOT_REPORTED to {@link OverviewEvidenceQueries} (a transaction's outcome
   * across every batch in this window, matching the Transactions Overview dashboard tile's own
   * "ever excluded"/"ever reported" definition) and every other status to {@link
   * PeriodEvidenceQueries} (this period's batch evidence rows) -- see {@link
   * OverviewEvidenceQueries}'s class Javadoc for why the two are deliberately not shared. {@code
   * batchScopedExcluded} is the one exception: the Report Groups Requiring Attention table's own
   * "Excluded" column is {@code SUM(excluded_txn)} over the exact same batch set this scope
   * already resolves -- a fundamentally different, simpler definition than "ever excluded across
   * every batch in this window" -- so a caller showing evidence for
   * *that* number passes this flag to get {@link PeriodEvidenceQueries
   * #findExcludedEvidenceRecordsForBatchTotal} instead, which actually matches it. Ignored unless
   * status is EXCLUDED; NOT_REPORTED always uses the overview rollup, since it has no batch-scoped
   * KPI to match in the first place.
   */
  /**
   * One transaction's full evidence for the detail request, produced by running the <em>same</em>
   * pipeline and the <em>same</em> filters the list ran and then keeping the one matching row.
   *
   * <p>Rebuilding the union here instead was tried and was wrong: with the list's metric filter
   * absent, a RULE_HIT source row re-entered the merge and outranked the JOURNEY row the list had
   * shown, so the panel reported a different record key, amount precision and bucket/attempt than
   * the row it was opened from. The panel is an expansion of a specific row, so it has to be
   * derived the same way that row was.
   *
   * <p>This is the distinction the rule-hit starvation bug turned on, applied here: the union
   * branch <em>may</em> be filtered, because it decides which row you are looking at; the rule-hit
   * enrichment may <em>not</em>, because it describes the transaction. Going through
   * {@code findEvidenceRecords} preserves both -- it already page-bounds the enrichment bridge to
   * the rows it returns, independent of the filters.
   */
  public Optional<TransactionEvidenceProjection> findBatchEvidenceDetail(int reportGroupId, String batchId, String identifier, String metric,
      String source, String stage, String outcome, String status, String recordKey) {
    return exactMatch(batchEvidenceQueries
          .findEvidenceRecords(reportGroupId, batchId, metric, "", source, stage, outcome, status, "DESC", 1, 0, null,
              EvidenceProjection.detail(batchId, identifier, recordKey))
          .records(), batchId, identifier);
  }

  /**
   * The period-scoped counterpart, routed exactly as {@link #findPeriodEvidenceRecords} routes.
   */
  public Optional<TransactionEvidenceProjection> findPeriodEvidenceDetail(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive,
      boolean filterByCountry, List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String evidenceBatchId,
      String identifier, String outcome, String status, String reason, boolean batchScopedExcluded, String batchIdFilter,
      String recordKey) {
    return exactMatch(findPeriodEvidenceRecords(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds, filterByReportGroup,
            reportGroupId, batchIdFilter, "", outcome, status, reason, batchScopedExcluded, "DESC", 1, 0, null,
            EvidenceProjection.detail(evidenceBatchId, identifier, recordKey))
          .records(), evidenceBatchId, identifier);
  }

  /**
   * Defensive identity check after the pipeline applies exact identity predicates in SQL.
   */
  private static Optional<TransactionEvidenceProjection> exactMatch(List<TransactionEvidenceProjection> records, String evidenceBatchId,
      String identifier) {
    return records
      .stream()
      .filter(record -> identifier.equals(record.identifier()) && evidenceBatchId.equals(record.batchId()))
      .findFirst();
  }

  @SqlQueryPurpose("Load paginated transaction evidence across the selected reporting period")
  public EvidencePage findPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId, String search, String outcome,
      String status, String reason, boolean batchScopedExcluded, String sortDirection, int size, long offset, EvidenceCursor cursor,
      EvidenceProjection projection) {
    SqlFragment scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
    if (VALUE_EXCLUDED.equals(status) && batchScopedExcluded) {
      return periodEvidenceQueries.findExcludedEvidenceRecordsForBatchTotal(scope, search, sortDirection, size, offset, cursor, projection);
    }
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.findOverviewEvidenceRecords(scope, status, reason, search, outcome, sortDirection, size, offset, cursor,
          projection);
    }
    return periodEvidenceQueries.findEvidenceRecords(scope, search, outcome, status, sortDirection, size, offset, cursor, projection);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records across the selected reporting period")
  public long countPeriodEvidenceRecords(LocalDateTime fromTimestamp, LocalDateTime toTimestampExclusive, boolean filterByCountry,
      List<Integer> reportGroupIds, boolean filterByReportGroup, int reportGroupId, String batchId, String search, String outcome,
      String status, String reason, boolean batchScopedExcluded) {
    SqlFragment scope = periodEvidenceQueries.batchScope(fromTimestamp, toTimestampExclusive, filterByCountry, reportGroupIds,
        filterByReportGroup, reportGroupId, batchId);
    if (VALUE_EXCLUDED.equals(status) && batchScopedExcluded) {
      return periodEvidenceQueries.countExcludedEvidenceRecordsForBatchTotal(scope, search);
    }
    if (VALUE_EXCLUDED.equals(status) || VALUE_NOT_REPORTED.equals(status)) {
      return overviewEvidenceQueries.countOverviewEvidenceRecords(scope, status, reason, search, outcome);
    }
    return periodEvidenceQueries.countEvidenceRecords(scope, search, outcome, status);
  }
}
