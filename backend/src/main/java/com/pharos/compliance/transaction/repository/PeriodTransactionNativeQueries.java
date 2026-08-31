package com.pharos.compliance.transaction.repository;

/**
 * Backs the "transaction evidence across a whole filtered period" view — used when a dashboard KPI
 * (or a user just browsing a report group's activity over a date range) spans many batches and
 * there is no single batch to show evidence for. Mirrors TransactionReportNativeQueries' three
 * evidence sources (JOURNEY / EXCLUSION_AUDIT / RULE_HIT) exactly, just joined against the whole
 * set of batches matching the date range / country / report-group filter (batch_scope) instead of
 * one :batchId.
 */
final class PeriodTransactionNativeQueries {

  private static final String BATCH_SCOPE_CTE =
      """
      batch_scope AS (
          SELECT rpt_grp_id, batch_id, rpt_grp_name, excluded_txn
          FROM pharos.report_transformation_reconciliation
          WHERE created_timestamp >= :fromTimestamp
            AND created_timestamp < :toTimestampExclusive
            AND (:filterByCountry = FALSE OR rpt_grp_id IN (:reportGroupIds))
            AND (:filterByReportGroup = FALSE OR rpt_grp_id = :reportGroupId)
            -- Same substring match the dashboard KPI applies, so a Batch ID typed into the
            -- dashboard filter narrows this drilldown to exactly the batches it counted.
            AND (:batchId = '' OR LOWER(batch_id) LIKE LOWER(CONCAT('%', :batchId, '%')))
      )
      """;

  static final String AGGREGATE =
      "WITH "
          + BATCH_SCOPE_CTE
          + """
      SELECT
          COUNT(DISTINCT (rpt_grp_id, batch_id)) AS "batchCount",
          COALESCE(SUM(excluded_txn), 0) AS "totalExcluded",
          MAX(rpt_grp_name) AS "reportGroupName"
      FROM batch_scope
      """;

  /**
   * Resolves each rule_hit row to its journey identifier, across every batch in batch_scope
   * (rather than a single :batchId) — same identifier -> external_txn_key/mtcn bridge as
   * TransactionReportNativeQueries.RULE_HIT_MATCHES_CTE.
   */
  private static final String RULE_HIT_MATCHES_CTE =
      """
      , rule_hit_matches AS (
          SELECT
              rh.*,
              COALESCE(
                  (SELECT j.identifier
                   FROM pharos.record_transformation_journey j
                   JOIN batch_scope bsj ON bsj.rpt_grp_id = j.rpt_grp_id AND bsj.batch_id = j.batch_id
                   WHERE j.rpt_grp_id = rh.rpt_grp_id
                     AND j.identifier ~ '^[0-9]+$'
                     AND j.identifier::bigint = rh.external_txn_key
                   LIMIT 1),
                  (SELECT j2.identifier
                   FROM pharos.record_transformation_journey j2
                   JOIN batch_scope bsj2 ON bsj2.rpt_grp_id = j2.rpt_grp_id AND bsj2.batch_id = j2.batch_id
                   WHERE j2.rpt_grp_id = rh.rpt_grp_id
                     AND j2.mtcn = rh.mtcn
                   LIMIT 1)
              ) AS matched_identifier
          FROM pharos.rule_hit rh
          WHERE rh.rpt_grp_id IN (SELECT DISTINCT rpt_grp_id FROM batch_scope)
            -- Pure performance short-circuit, not a filter: see the identical guard and its full
            -- rationale on TransactionReportNativeQueries.RULE_HIT_MATCHES_CTE. The rule hit
            -- evidence status column can only ever be REPORTED or NOT_REPORTED, so this never
            -- changes the result; it just lets Postgres skip the two correlated identifier lookup
            -- subqueries above, the most expensive part of this query, when the requested status
            -- filter cannot match this source at all.
            AND (:status = 'ALL' OR :status IN ('REPORTED', 'NOT_REPORTED'))
      )
      """;

  /** Union of every evidence source into one common row shape — the period-wide equivalent of
   *  TransactionReportNativeQueries.EVIDENCE_CTE, joined against batch_scope instead of :batchId. */
  private static final String EVIDENCE_CTE =
      """
      , evidence AS (
          SELECT
              CONCAT('JOURNEY:', journey.rpt_grp_id, ':', journey.batch_id, ':', journey.identifier)
                  AS record_key,
      """
          + TransactionEvidenceColumns.JOURNEY_COLUMNS
          + """
          FROM pharos.record_transformation_journey journey
          JOIN batch_scope bs_journey
              ON bs_journey.rpt_grp_id = journey.rpt_grp_id AND bs_journey.batch_id = journey.batch_id

          UNION ALL

          SELECT
      """
          + TransactionEvidenceColumns.EXCLUSION_COLUMNS
          + """
          FROM pharos.rule_hit_exclusion_audit exclusion_audit
          JOIN batch_scope bs_exclusion
              ON bs_exclusion.rpt_grp_id = exclusion_audit.rpt_grp_id
             AND bs_exclusion.batch_id = exclusion_audit.processing_batch_id
          -- Pure performance short-circuit: see RULE_HIT_MATCHES_CTE above. The status column on
          -- this branch is always the literal EXCLUDED, so it can never satisfy any other filter.
          WHERE (:status = 'ALL' OR :status = 'EXCLUDED')

          UNION ALL

          SELECT
      """
          + TransactionEvidenceColumns.RULE_HIT_BRANCH
          + """
      )
      """;

  private static final String FILTERED_CTE =
      """
      , filtered_evidence AS (
          SELECT *
          FROM evidence
          WHERE (:search = ''
                 OR LOWER(identifier) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(mtcn, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:outcome = 'ALL' OR outcome = :outcome)
            AND (:status = 'ALL' OR UPPER(COALESCE(status, '')) = :status)
      )
      """;

  static final String EVIDENCE_RECORDS =
      "WITH "
          + BATCH_SCOPE_CTE
          + RULE_HIT_MATCHES_CTE
          + EVIDENCE_CTE
          + FILTERED_CTE
          + TransactionEvidenceColumns.MERGED_CTE
          + """
      , page AS (
          SELECT *
          FROM merged
          ORDER BY
              CASE WHEN :sortDirection = 'ASC' THEN sort_ts END ASC NULLS LAST,
              CASE WHEN :sortDirection = 'DESC' THEN sort_ts END DESC NULLS LAST,
              record_key
          LIMIT :size OFFSET :offset
      )
      SELECT
          record_key AS "recordKey",
          report_group_id AS "reportGroupId",
          identifier AS "identifier",
          mtcn AS "mtcn",
          evidence_batch_id AS "batchId",
          evidence_source AS "evidenceSource",
          status AS "status",
          comments AS "comments",
          skip_reason AS "skipReason",
          exclusion_reason AS "exclusionReason",
          reported_batch_id AS "reportedBatchId",
          modified_at AS "modifiedAt",
          processing_complete AS "processingComplete"
      FROM page
      ORDER BY
          CASE WHEN :sortDirection = 'ASC' THEN sort_ts END ASC NULLS LAST,
          CASE WHEN :sortDirection = 'DESC' THEN sort_ts END DESC NULLS LAST,
          record_key
      """;

  static final String EVIDENCE_COUNT =
      "WITH "
          + BATCH_SCOPE_CTE
          + RULE_HIT_MATCHES_CTE
          + EVIDENCE_CTE
          + FILTERED_CTE
          + """
      SELECT COUNT(*) AS "count"
      FROM (
          SELECT 1
          FROM filtered_evidence
          GROUP BY evidence_batch_id, identifier
      ) transactions
      """;

  private PeriodTransactionNativeQueries() {}
}
