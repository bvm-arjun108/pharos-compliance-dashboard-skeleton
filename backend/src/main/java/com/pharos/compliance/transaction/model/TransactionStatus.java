package com.pharos.compliance.transaction.model;

/**
 * The literal status value on a piece of evidence, independent of its normalized outcome.
 */
public enum TransactionStatus {
  ALL,
  SUCCESS,
  FAILED,
  ERROR,
  EXCLUDED,
  NOT_YET_REPORTED,
  REPORTED,
  NOT_REPORTED
}
