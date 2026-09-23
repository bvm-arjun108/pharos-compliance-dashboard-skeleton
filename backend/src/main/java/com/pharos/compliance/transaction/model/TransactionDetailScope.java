package com.pharos.compliance.transaction.model;

/**
 * Which list a detail request was opened from. This is not cosmetic: the two pipelines resolve
 * rule hits over genuinely different scopes -- BATCH matches {@code rule_hit} within one
 * {@code efile_batch_id}, PERIOD matches across every batch of the report group in the window --
 * so a detail response must reproduce the scope its row was listed under or it will disagree with
 * what the list itself would have shown.
 *
 * <p>Carrying the scope explicitly is also what keeps the rule-hit enrichment independent of the
 * list's status/metric filter. Re-deriving it from those filters is what previously emptied the
 * Rule Hit Details panel on every EXCLUDED drilldown.
 */
public enum TransactionDetailScope {
  BATCH,
  PERIOD
}
