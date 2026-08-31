package com.pharos.compliance.transaction.repository;

/**
 * The column projections shared by the batch-scoped ({@link TransactionReportNativeQueries}) and
 * period-scoped ({@link PeriodTransactionNativeQueries}) evidence queries.
 *
 * <p>Both queries union the same three evidence sources into the same row shape; they differ only
 * in how each source is scoped (a single :batchId versus every batch in batch_scope) and, for the
 * journey branch, in the record_key expression. Everything else -- the column list, the
 * status/outcome derivation, the enrichment key -- was duplicated line for line across the two
 * files, so any change to the row shape had to be made twice and silently broke the pair if it was
 * not. Keeping the projections here makes that impossible: both queries compose these constants
 * and supply only their own scoping.
 *
 * <p>Every text block below closes its delimiter at six spaces, matching the queries that embed
 * them. Java strips incidental indentation using the minimum across the content lines AND the
 * closing delimiter, so that column is what keeps the composed SQL byte-identical to the inline
 * form these were extracted from.
 */
final class TransactionEvidenceColumns {

  /** Journey branch, minus its record_key -- the two queries build that differently. */
  static final String JOURNEY_COLUMNS =
      """
              journey.identifier,
              journey.rpt_grp_id AS report_group_id,
              journey.mtcn,
              journey.batch_id AS evidence_batch_id,
              'JOURNEY' AS evidence_source,
              journey.stage,
              journey.status,
              CASE
                  WHEN UPPER(COALESCE(journey.status, '')) IN ('ERROR', 'FAILED', 'FAILURE')
                      THEN 'ERROR'
                  WHEN UPPER(COALESCE(journey.status, '')) IN
                      ('SUCCESS', 'COMPLETED', 'TRANSFORMED', 'REPORTED') THEN 'SUCCESS'
                  WHEN UPPER(COALESCE(journey.status, '')) = 'EXCLUDED' THEN 'EXCLUDED'
                  ELSE 'PENDING'
              END AS outcome,
              journey.comments,
              journey.skip_reason,
              NULL::text AS rule_id,
              NULL::text AS exclusion_reason,
              NULL::text AS exclusion_strategy,
              NULL::text AS reported_batch_id,
              journey.reporting_timestamp_latest::text AS reporting_timestamp,
              journey.modified_timestamp::text AS modified_at,
              -- Sort key. The displayed value (modified_at) stays a ::text cast so the payload is
              -- unchanged, but ordering must NOT use it: the three sources store
              -- timestamptz/timestamptz/timestamp respectively, so their ::text renderings differ
              -- in shape (offset suffix vs none) and, for timestamptz, vary with the session
              -- TimeZone. Comparing those as strings is neither chronological nor stable. A real
              -- timestamptz compares as an absolute instant and can be index-backed.
              journey.modified_timestamp AS sort_ts,
              journey.processing_complete,
              -- Journey rows take these four from reg_reportable_activity; they are re-applied in
              -- EVIDENCE_RECORDS after pagination. float8 here matches the type this UNION
              -- resolved to when the journey branch selected s_local_principal directly.
              NULL::float8 AS currency_amount,
              NULL::text AS currency_code,
              NULL::text AS transaction_date,
              NULL::text AS transaction_side,
              NULL::text AS txn_source,
              NULL::text AS activity_type,
              NULL::text AS send_date,
              NULL::text AS galactic_id,
              NULL::int4 AS bucket_id,
              NULL::int8 AS attempt_id,
              CASE WHEN journey.identifier ~ '^[0-9]+$' THEN journey.identifier::bigint END
                  AS rra_key
      """;

  /** Exclusion-audit branch projection, record_key included. */
  static final String EXCLUSION_COLUMNS =
      """
              CONCAT('EXCLUSION:', exclusion_audit.bucket_id, ':', exclusion_audit.rule_id,
                  ':', exclusion_audit.attempt_id) AS record_key,
              COALESCE(exclusion_audit.external_txn_key::text, exclusion_audit.attempt_id::text)
                  AS identifier,
              exclusion_audit.rpt_grp_id AS report_group_id,
              exclusion_audit.mtcn,
              exclusion_audit.processing_batch_id AS evidence_batch_id,
              'EXCLUSION_AUDIT' AS evidence_source,
              'EXCLUSION' AS stage,
              'EXCLUDED' AS status,
              'EXCLUDED' AS outcome,
              NULL::text AS comments,
              NULL::text AS skip_reason,
              exclusion_audit.rule_id,
              exclusion_audit.exclusion_reason_id AS exclusion_reason,
              exclusion_audit.exclusion_strategy,
              exclusion_audit.reported_batch_id,
              exclusion_audit.reporting_timestamp::text AS reporting_timestamp,
              exclusion_audit.modified_timestamp::text AS modified_at,
              exclusion_audit.modified_timestamp AT TIME ZONE 'UTC' AS sort_ts,
              TRUE AS processing_complete,
              NULL::float8 AS currency_amount,
              NULL::text AS currency_code,
              NULL::text AS transaction_date,
              NULL::text AS transaction_side,
              NULL::text AS txn_source,
              NULL::text AS activity_type,
              NULL::text AS send_date,
              NULL::text AS galactic_id,
              exclusion_audit.bucket_id,
              exclusion_audit.attempt_id,
              exclusion_audit.external_txn_key AS rra_key
      """;

  /** Rule-hit branch in full: projection, FROM and WHERE are identical in both queries. */
  static final String RULE_HIT_BRANCH =
      """
              CONCAT('RULE_HIT:', rule_hit_matches.bucket_id, ':', rule_hit_matches.rule_id,
                  ':', rule_hit_matches.attempt_id) AS record_key,
              rule_hit_matches.matched_identifier AS identifier,
              rule_hit_matches.rpt_grp_id AS report_group_id,
              rule_hit_matches.mtcn,
              rule_hit_matches.efile_batch_id AS evidence_batch_id,
              'RULE_HIT' AS evidence_source,
              'RULE_HIT' AS stage,
              CASE WHEN rule_hit_matches.is_reported THEN 'REPORTED' ELSE 'NOT_REPORTED' END
                  AS status,
              CASE WHEN rule_hit_matches.is_reported THEN 'SUCCESS' ELSE 'PENDING' END AS outcome,
              NULL::text AS comments,
              NULL::text AS skip_reason,
              rule_hit_matches.rule_id,
              rule_hit_matches.exclusion_reason_id AS exclusion_reason,
              NULL::text AS exclusion_strategy,
              rule_hit_matches.reported_batch_id,
              rule_hit_matches.reporting_timestamp::text AS reporting_timestamp,
              rule_hit_matches.modified_timestamp::text AS modified_at,
              rule_hit_matches.modified_timestamp AS sort_ts,
              TRUE AS processing_complete,
              rule_hit_matches.rule_currency_amount::float8 AS currency_amount,
              rule_hit_matches.rule_iso_currency_code AS currency_code,
              rule_hit_matches.transaction_date::text AS transaction_date,
              rule_hit_matches.transaction_side AS transaction_side,
              rule_hit_matches.source AS txn_source,
              rule_hit_matches.activity_type AS activity_type,
              rule_hit_matches.send_date::text AS send_date,
              rule_hit_matches.galactic_id AS galactic_id,
              rule_hit_matches.bucket_id AS bucket_id,
              rule_hit_matches.attempt_id AS attempt_id,
              rule_hit_matches.external_txn_key AS rra_key
          FROM rule_hit_matches
          WHERE rule_hit_matches.matched_identifier IS NOT NULL
      """;

  /**
   * Collapses the evidence rows for one transaction into a single row.
   *
   * <p>The three sources are complementary views of the same transaction, not separate
   * transactions: a transaction excluded in a batch typically has BOTH a journey row (carrying
   * comments/skip_reason) and an exclusion-audit row (carrying the rule, reason and prior-batch
   * link). Listing both made a count of 8 excluded transactions render as 16 rows, each shown
   * twice with different halves of the story. This merges them field by field -- for every column,
   * the first non-null value in source-priority order -- so one transaction is one row carrying
   * everything known about it.
   *
   * <p>Grouped by (evidence_batch_id, identifier), so a transaction excluded in two different
   * batches still yields two rows: the dashboard KPIs sum per batch, and the drilldown has to
   * stay consistent with the number that was clicked.
   *
   * <p>Applied AFTER the status/outcome filter, never before. A transaction can be SUCCESS in its
   * journey row and REPORTED in its rule-hit row; merging first would collapse it to one status
   * and make it vanish from the other filter. Filtering first means every row in a group already
   * shares the filtered status, so the merge cannot change which transactions match.
   */
  static final String MERGED_CTE =
      """
      , merged AS (
          SELECT
              evidence_batch_id,
              identifier,
              (ARRAY_AGG(report_group_id ORDER BY source_rank, record_key)
                  FILTER (WHERE report_group_id IS NOT NULL))[1] AS report_group_id,
              (ARRAY_AGG(record_key ORDER BY source_rank, record_key)
                  FILTER (WHERE record_key IS NOT NULL))[1] AS record_key,
              (ARRAY_AGG(mtcn ORDER BY source_rank, record_key)
                  FILTER (WHERE mtcn IS NOT NULL))[1] AS mtcn,
              (ARRAY_AGG(evidence_source ORDER BY source_rank, record_key)
                  FILTER (WHERE evidence_source IS NOT NULL))[1] AS evidence_source,
              (ARRAY_AGG(stage ORDER BY source_rank, record_key)
                  FILTER (WHERE stage IS NOT NULL))[1] AS stage,
              (ARRAY_AGG(status ORDER BY source_rank, record_key)
                  FILTER (WHERE status IS NOT NULL))[1] AS status,
              (ARRAY_AGG(outcome ORDER BY source_rank, record_key)
                  FILTER (WHERE outcome IS NOT NULL))[1] AS outcome,
              (ARRAY_AGG(comments ORDER BY source_rank, record_key)
                  FILTER (WHERE comments IS NOT NULL))[1] AS comments,
              (ARRAY_AGG(skip_reason ORDER BY source_rank, record_key)
                  FILTER (WHERE skip_reason IS NOT NULL))[1] AS skip_reason,
              (ARRAY_AGG(rule_id ORDER BY source_rank, record_key)
                  FILTER (WHERE rule_id IS NOT NULL))[1] AS rule_id,
              (ARRAY_AGG(exclusion_reason ORDER BY source_rank, record_key)
                  FILTER (WHERE exclusion_reason IS NOT NULL))[1] AS exclusion_reason,
              (ARRAY_AGG(exclusion_strategy ORDER BY source_rank, record_key)
                  FILTER (WHERE exclusion_strategy IS NOT NULL))[1] AS exclusion_strategy,
              (ARRAY_AGG(reported_batch_id ORDER BY source_rank, record_key)
                  FILTER (WHERE reported_batch_id IS NOT NULL))[1] AS reported_batch_id,
              (ARRAY_AGG(reporting_timestamp ORDER BY source_rank, record_key)
                  FILTER (WHERE reporting_timestamp IS NOT NULL))[1] AS reporting_timestamp,
              (ARRAY_AGG(modified_at ORDER BY source_rank, record_key)
                  FILTER (WHERE modified_at IS NOT NULL))[1] AS modified_at,
              (ARRAY_AGG(sort_ts ORDER BY source_rank, record_key)
                  FILTER (WHERE sort_ts IS NOT NULL))[1] AS sort_ts,
              (ARRAY_AGG(processing_complete ORDER BY source_rank, record_key)
                  FILTER (WHERE processing_complete IS NOT NULL))[1] AS processing_complete,
              (ARRAY_AGG(currency_amount ORDER BY source_rank, record_key)
                  FILTER (WHERE currency_amount IS NOT NULL))[1] AS currency_amount,
              (ARRAY_AGG(currency_code ORDER BY source_rank, record_key)
                  FILTER (WHERE currency_code IS NOT NULL))[1] AS currency_code,
              (ARRAY_AGG(transaction_date ORDER BY source_rank, record_key)
                  FILTER (WHERE transaction_date IS NOT NULL))[1] AS transaction_date,
              (ARRAY_AGG(transaction_side ORDER BY source_rank, record_key)
                  FILTER (WHERE transaction_side IS NOT NULL))[1] AS transaction_side,
              (ARRAY_AGG(txn_source ORDER BY source_rank, record_key)
                  FILTER (WHERE txn_source IS NOT NULL))[1] AS txn_source,
              (ARRAY_AGG(activity_type ORDER BY source_rank, record_key)
                  FILTER (WHERE activity_type IS NOT NULL))[1] AS activity_type,
              (ARRAY_AGG(send_date ORDER BY source_rank, record_key)
                  FILTER (WHERE send_date IS NOT NULL))[1] AS send_date,
              (ARRAY_AGG(galactic_id ORDER BY source_rank, record_key)
                  FILTER (WHERE galactic_id IS NOT NULL))[1] AS galactic_id,
              (ARRAY_AGG(bucket_id ORDER BY source_rank, record_key)
                  FILTER (WHERE bucket_id IS NOT NULL))[1] AS bucket_id,
              (ARRAY_AGG(attempt_id ORDER BY source_rank, record_key)
                  FILTER (WHERE attempt_id IS NOT NULL))[1] AS attempt_id,
              (ARRAY_AGG(rra_key ORDER BY source_rank, record_key)
                  FILTER (WHERE rra_key IS NOT NULL))[1] AS rra_key,
              MIN(source_rank) AS source_rank
          FROM (
              SELECT
                  filtered_evidence.*,
                  CASE evidence_source
                      WHEN 'EXCLUSION_AUDIT' THEN 1
                      WHEN 'RULE_HIT' THEN 2
                      ELSE 3
                  END AS source_rank
              FROM filtered_evidence
          ) ranked
          GROUP BY evidence_batch_id, identifier
      )
      """;

  private TransactionEvidenceColumns() {}
}
