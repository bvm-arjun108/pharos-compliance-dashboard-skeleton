package com.pharos.compliance.batch.model;

public enum BatchIssueType {
  ALL,
  ACTIVITY_MISSING,
  MISSING_ATTEMPTS,
  TRANSFORMATION,
  DUPLICATE_TRANSFORMATION,
  EXCLUSION,
  SIMULATED,
  SOFT_DEDUP
}
