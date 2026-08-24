package com.pharos.compliance.transaction.model;

/** The pipeline stage a piece of evidence belongs to, independent of its evidence source. */
public enum TransactionStage {
  ALL,
  SELECTION,
  TRANSACTION_JOIN,
  TRANSFORMATION,
  EXCLUSION,
  RULE_HIT
}
