package com.pharos.compliance.transaction.repository.evidence.jdbc;

/**
 * Fully-qualified table names replacing {@code
 * com.pharos.compliance.transaction.repository.evidence.EvidenceColumns}'s jOOQ generated-table
 * object constants (e.g. {@code EvidenceColumns.RECONCILIATION}), for the hand-written SQL
 * evidence-pipeline classes as they migrate off jOOQ (Phase 6 of the jOOQ-to-JDBC migration).
 * {@code EvidenceColumns}'s own column-name String constants (e.g. {@code
 * EvidenceColumns.IDENTIFIER}) and {@code MERGE_COLUMNS} list have no jOOQ dependency already and
 * are reused directly by both jOOQ-based and JDBC-based code without needing a copy here.
 */
public final class EvidenceTables {
  public static final String RECONCILIATION = "pharos.report_transformation_reconciliation";
  public static final String JOURNEY = "pharos.record_transformation_journey";
  public static final String RULE_HIT = "pharos.rule_hit";
  public static final String EXCLUSION_AUDIT = "pharos.rule_hit_exclusion_audit";
  public static final String RRA = "pharos.reg_reportable_activity";
  public static final String BATCH_INFO = "pharos.report_batch_info";

  private EvidenceTables() {
  }
}
