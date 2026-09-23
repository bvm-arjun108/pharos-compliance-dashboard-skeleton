package com.pharos.compliance.transaction.repository.evidence;

/**
 * How much of a transaction to project.
 *
 * <p>{@link #LIST} is what a page of rows needs: the dozen columns the table actually renders.
 * {@link #DETAIL} adds everything only the expanded panel shows -- the {@code
 * reg_reportable_activity} party join and the rule-hit rollup, which together were the two most
 * expensive parts of this query and the reason a list response carried personal data for every row
 * whether or not anyone opened one.
 *
 * <p>LIST also skips building the rule-hit bridge at all, since nothing in that projection consumes
 * it.
 */
public record EvidenceProjection(boolean details, String batchId, String identifier, String recordKey) {
  public static final EvidenceProjection LIST = new EvidenceProjection(false, null, null, null);
  public static final EvidenceProjection DETAIL = new EvidenceProjection(true, null, null, null);

  public static EvidenceProjection detail(String batchId, String identifier, String recordKey) {
    return new EvidenceProjection(true, batchId, identifier, recordKey);
  }
}
