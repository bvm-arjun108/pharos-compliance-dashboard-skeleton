package com.pharos.compliance.transaction.repository.evidence.jdbc;

import com.pharos.compliance.common.jdbc.sql.SqlFragment;
import com.pharos.compliance.common.jdbc.sql.SqlResourceLoader;
import com.pharos.compliance.common.jdbc.logging.SqlQueryPurpose;
import com.pharos.compliance.transaction.model.EvidenceCursor;
import com.pharos.compliance.transaction.repository.evidence.EvidenceProjection;
import com.pharos.compliance.transaction.repository.projection.EvidencePage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-scoped transaction evidence: one reconciliation batch's Data Selection drilldowns. Builds
 * the three-source evidence UNION for exactly that batch, scopes it to the requested {@code
 * metric}, and hands off to {@link EvidencePaginator} for pagination and enrichment.
 *
 * <p>Every stage ({@code rule_hit_matches}, {@code evidence}, {@code metric_scoped}, {@code
 * filtered_evidence}) is built as its own named CTE, appended to a single flat list rather than
 * nested inside one another -- each stage's SQL text references the previous stage by name in its
 * own {@code FROM} clause.
 */
public class BatchEvidenceQueries {
  private static final String EVIDENCE_FOR_BATCH_SQL = "sql/evidence/batch/evidence-for-batch.sql";
  private static final String VALUE_REPORTED = "REPORTED";
  private static final String VALUE_NOT_REPORTED = "NOT_REPORTED";
  private final SqlResourceLoader sql;
  private final RuleHitMatcher ruleHitMatcher;
  private final EvidencePaginator paginator;

  public BatchEvidenceQueries(SqlResourceLoader sql, RuleHitMatcher ruleHitMatcher, EvidencePaginator paginator) {
    this.sql = sql;
    this.ruleHitMatcher = ruleHitMatcher;
    this.paginator = paginator;
  }

  /**
   * Deliberately independent of {@code metric}: an earlier version keyed this short-circuit on the
   * requested metric, and {@link #ruleHitMatchesForBatchEnrichment} shared the same result -- so
   * whenever a metric's own semantics happened to skip the rule_hit lookup, the "Rule Hit Details"
   * panel silently got the same zero-row table and rendered "no rule hits matched" for transactions
   * that had real matches under a different metric. {@code status} still short-circuits: {@code
   * rule_hit} evidence's status can only ever be REPORTED/NOT_REPORTED, so any other requested
   * status matches zero rule_hit rows -- union branch only, never the enrichment (see {@link
   * #ruleHitMatchesForBatchEnrichment}).
   */
  private SqlFragment ruleHitMatchesForBatch(int reportGroupId, String batchId, String status) {
    if (!("ALL".equals(status) || VALUE_REPORTED.equals(status) || VALUE_NOT_REPORTED.equals(status))) {
      return SqlFragment.of("select rh.*, cast(null as text) as matched_identifier from pharos.rule_hit rh where false");
    }
    return ruleHitBridge(reportGroupId, batchId, "true", Map.of());
  }

  /**
   * The enrichment counterpart to {@link #ruleHitMatchesForBatch}, with no status short-circuit --
   * bounded to the page's own transactions instead.
   */
  private SqlFragment ruleHitMatchesForBatchEnrichment(int reportGroupId, String batchId, Collection<String> pageIdentifiers) {
    return ruleHitBridge(reportGroupId, batchId, "identifier in (:pageIdentifiers)", Map.of("pageIdentifiers", pageIdentifiers));
  }

  private SqlFragment ruleHitBridge(int reportGroupId, String batchId, String journeyRestrictionSql, Map<String, Object> restrictionParams) {
    String journeyScopedSql = "select identifier as identifier, mtcn as mtcn,\n  (case when "
        + EvidenceSqlSupport.matchesDigitsOnly("identifier") + " then identifier::bigint else null end) as identifier_bigint\n"
        + "from pharos.record_transformation_journey\n"
        + "where rpt_grp_id = :ruleHitBridgeGroupId and batch_id = :ruleHitBridgeBatchId and " + journeyRestrictionSql;
    Map<String, Object> journeyParams = new HashMap<>(restrictionParams);
    journeyParams.put("ruleHitBridgeGroupId", reportGroupId);
    journeyParams.put("ruleHitBridgeBatchId", batchId);
    SqlFragment journeyScoped = SqlFragment.of(journeyScopedSql, journeyParams);
    // efile_batch_id, not rule_hit's own unrelated integer batch_id column -- the same field the
    // merge's own RULE_HIT branch already uses as that row's evidence_batch_id. Matching an
    // identifier/mtcn alone could surface a rule_hit belonging to a *different* batch that happens
    // to share it (e.g. a resubmitted transaction) as if it were this batch's own evidence.
    SqlFragment ruleHitScope = SqlFragment.of("rh.rpt_grp_id = :ruleHitScopeGroupId and rh.efile_batch_id = :ruleHitScopeBatchId",
        Map.of("ruleHitScopeGroupId", reportGroupId, "ruleHitScopeBatchId", batchId));
    return ruleHitMatcher.ruleHitMatches(ruleHitScope, journeyScoped);
  }

  /**
   * {@code rule_hit_matches} + {@code evidence}, as a flat, ordered CTE list.
   */
  private List<SqlFragment> evidenceCtes(int reportGroupId, String batchId, String status) {
    SqlFragment ruleHitMatchesCte = ruleHitMatchesForBatch(reportGroupId, batchId, status).asCte("rule_hit_matches");

    String journeyOutcome = EvidenceSqlSupport.journeyOutcome("status");
    String rraKeyGuard = EvidenceSqlSupport.matchesDigitsOnly("identifier");
    String evidenceSql =
        sql.load(EVIDENCE_FOR_BATCH_SQL).replace("%%JOURNEY_OUTCOME%%", journeyOutcome).replace("%%RRA_KEY_GUARD%%", rraKeyGuard);
    Map<String, Object> evidenceParams = new HashMap<>();
    evidenceParams.put("reportGroupId", reportGroupId);
    evidenceParams.put("batchId", batchId);
    SqlFragment evidenceCte = SqlFragment.of(evidenceSql, evidenceParams).asCte("evidence");

    List<SqlFragment> ctes = new ArrayList<>();
    ctes.add(ruleHitMatchesCte);
    ctes.add(evidenceCte);
    return ctes;
  }

  /**
   * {@code evidence_source = 'JOURNEY'} scoped to a fixed stage, factored out since several
   * metrics below narrow one of these two stages by a further outcome/comment condition.
   */
  private static String journeyAt(String stage) {
    return "evidence_source = 'JOURNEY' and upper(coalesce(stage, '')) = '" + stage + "'";
  }

  /**
   * Every metric's evidence condition, built from the same descriptively-named sub-conditions
   * below ({@link #journeyAt}, missing-attempt, activity-missing, failed, report-generation-success,
   * and so on) so each branch's business rule stays legible at the call site rather than needing a
   * separate explanation. Every literal is fixed Java-selected text, never a request-derived string
   * reaching the SQL.
   */
  private static String metricCondition(String metric) {
    String journeyAtFiltration = journeyAt("FILTRATION");
    String journeyAtTransformation = journeyAt("TRANSFORMATION");
    String missingAttemptCondition =
        "evidence_source = 'JOURNEY' and (" + "(upper(coalesce(stage, '')) = 'SELECTION' and "
        + "upper(coalesce(status, '')) = 'ATTEMPT_MISSING') or " + "(upper(coalesce(stage, '')) = 'TRANSACTION_JOIN' and "
        + "upper(coalesce(status, '')) = 'ERROR' and upper(coalesce(comments, '')) = 'ATTEMPT_NOT_RECEIVED'))";
    String activityMissingCondition =
        "evidence_source = 'JOURNEY' and upper(coalesce(stage, '')) = 'TRANSACTION_JOIN' and "
        + "upper(coalesce(status, '')) = 'ERROR' and upper(coalesce(comments, '')) = 'TXN_DATA_MISSING'";
    String failedCondition = "(" + journeyAtTransformation + ") and outcome = 'ERROR'";
    String reportGenerationSuccess =
        "evidence_source = 'JOURNEY' and upper(coalesce(stage, '')) = 'REPORT_GENERATION' and upper(coalesce(status, '')) = 'GENERATED'";
    String eligibleForTransformationCondition = "(" + journeyAtTransformation + ") or (" + reportGenerationSuccess + ")";
    String transformedSuccessfullyCondition =
        "((" + journeyAtTransformation + ") and outcome = 'SUCCESS') or (" + reportGenerationSuccess + ")";

    return switch (metric) {
      case "ALL" -> "true";
      case "SELECTED", "ATTEMPTS_FOUND" -> "evidence_source = 'JOURNEY'";
      case "EXPECTED_ELIGIBLE", "ACTUAL_ELIGIBLE" -> eligibleForTransformationCondition;
      case "TRANSFORMED", "EXPECTED_REPORTABLE", "ACTUAL_REPORTABLE", "TRANSFORMER_OUTPUT" -> transformedSuccessfullyCondition;
      case "FAILED" -> failedCondition;
      case "EXCLUDED" -> "(" + journeyAtFiltration
          + ") and outcome = 'EXCLUDED' and upper(coalesce(comments, '')) = 'EXCLUDED_BECAUSE_EXCLUSION_EXISTS'";
      case "SIMULATED" -> "(" + journeyAtFiltration + ") and upper(coalesce(comments, '')) = 'EXCLUDED_BECAUSE_SML'";
      case "ALREADY_REPORTED" -> "(" + journeyAtFiltration + ") and upper(coalesce(comments, '')) like 'EXCLUDED_BECAUSE_ALREADY_REPORTED%'";
      case "SOFT_DEDUP" -> "(" + journeyAtFiltration + ") and (upper(coalesce(comments, '')) = 'EXCLUDED_SOFT_DEDUP' or "
          + "upper(coalesce(comments, '')) like 'EXCLUDED_REAPPEARING_%')";
      case "FILTERED" -> "(" + journeyAtFiltration + ") or (" + missingAttemptCondition + ") or (" + activityMissingCondition + ")";
      case "SKIPPED" -> "(" + missingAttemptCondition + ") or (" + activityMissingCondition + ") or (" + failedCondition + ")";
      case "MISSING" -> missingAttemptCondition;
      case "ACTIVITY_MISSING" -> activityMissingCondition;
      default -> "true";
    };
  }

  /**
   * {@code rule_hit_matches} + {@code evidence} + {@code metric_scoped}.
   */
  private List<SqlFragment> metricScopedCtes(int reportGroupId, String batchId, String status, String metric, String source) {
    List<SqlFragment> ctes = evidenceCtes(reportGroupId, batchId, status);

    Map<String, Object> params = new HashMap<>();
    String sourceCondition;
    if ("ALL".equals(source)) {
      sourceCondition = "true";
    } else {
      sourceCondition = "evidence_source = :metricSourceFilter";
      params.put("metricSourceFilter", source);
    }
    String body = "select * from evidence\nwhere (" + sourceCondition + ")\n  and (" + metricCondition(metric) + ")";
    ctes.add(SqlFragment.of(body, params).asCte("metric_scoped"));
    return ctes;
  }

  /**
   * {@code rule_hit_matches} + {@code evidence} + {@code metric_scoped} + {@code filtered_evidence}.
   */
  private List<SqlFragment> filteredEvidenceCtes(int reportGroupId, String batchId, String metric, String search, String source,
      String stage, String outcome, String status) {
    List<SqlFragment> ctes = metricScopedCtes(reportGroupId, batchId, status, metric, source);
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder("where 1 = 1");

    if (!"ALL".equals(stage)) {
      where.append("\n  and upper(coalesce(stage, '')) = :stageFilter");
      params.put("stageFilter", stage);
    }
    SqlFragment searchScope = EvidenceSqlSupport.searchScope(search, "identifier", "mtcn");
    if (!"true".equals(searchScope.sql())) {
      where.append("\n  and ").append(searchScope.sql());
      params.putAll(searchScope.params());
    }
    if (!"ALL".equals(outcome)) {
      where.append("\n  and outcome = :outcomeFilter");
      params.put("outcomeFilter", outcome);
    }
    // status=REPORTED must also match a JOURNEY row recorded as REPORT_GENERATION/GENERATED, not
    // only a literal "REPORTED" status (which only ever appears on a RULE_HIT-sourced row,
    // synthesized from rule_hit.is_reported) -- otherwise a reported transaction with no matching
    // rule_hit row would have no way to pass this filter at all. A bare TRANSFORMATION/SUCCESS row
    // also counts, but only when this batch's own report was actually generated (the EXISTS below):
    // without that check, a transaction merely transformed in a batch whose report generation later
    // failed would count as reported when it is really just sitting there, still waiting.
    if (!"ALL".equals(status)) {
      if (VALUE_REPORTED.equals(status)) {
        where
          .append("\n  and (upper(coalesce(status, '')) = :statusFilter\n")
          .append(
              "    or (evidence_source = 'JOURNEY' and upper(coalesce(stage, '')) = 'REPORT_GENERATION' and "
              + "upper(coalesce(status, '')) = 'GENERATED')\n")
          .append(
              "    or (evidence_source = 'JOURNEY' and upper(coalesce(stage, '')) = 'TRANSFORMATION' and "
              + "upper(coalesce(status, '')) = 'SUCCESS' and exists (\n")
          .append("      select 1 from pharos.report_batch_info\n")
          .append("      where rpt_grp_id = :batchGeneratedGroupId and batch_id = :batchGeneratedBatchId\n")
          .append("        and (compiler_status = 'Report Generation Completed' or report_status in ('ALL', 'PARTIAL'))\n")
          .append("    )))");
        params.put("statusFilter", status);
        params.put("batchGeneratedGroupId", reportGroupId);
        params.put("batchGeneratedBatchId", batchId);
      } else {
        where.append("\n  and upper(coalesce(status, '')) = :statusFilter");
        params.put("statusFilter", status);
      }
    }

    String body = "select * from metric_scoped\n" + where;
    ctes.add(SqlFragment.of(body, params).asCte("filtered_evidence"));
    return ctes;
  }

  @SqlQueryPurpose("Load paginated transaction evidence for one batch")
  public EvidencePage findEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status, String sortDirection, int size, long offset, EvidenceCursor cursor, EvidenceProjection projection) {
    List<SqlFragment> filteredCtes = filteredEvidenceCtes(reportGroupId, batchId, metric, search, source, stage, outcome, status);
    return paginator.pageEvidence(filteredCtes, false, ids -> ruleHitMatchesForBatchEnrichment(reportGroupId, batchId, ids), sortDirection,
        size, offset, cursor, projection);
  }

  @SqlQueryPurpose("Count filtered transaction evidence records for one batch")
  public long countEvidenceRecords(int reportGroupId, String batchId, String metric, String search, String source, String stage,
      String outcome, String status) {
    List<SqlFragment> filteredCtes = filteredEvidenceCtes(reportGroupId, batchId, metric, search, source, stage, outcome, status);
    return paginator.countDistinctIdentifiers(filteredCtes);
  }
}
