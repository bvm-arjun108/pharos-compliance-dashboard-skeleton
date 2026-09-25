package com.pharos.compliance.transaction.repository.evidence.jdbc;

/** Fully-qualified table names shared by the transaction-evidence SQL builders. */
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
