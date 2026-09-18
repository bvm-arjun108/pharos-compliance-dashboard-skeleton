package com.pharos.compliance.transaction.model;

/**
 * Which single field {@link com.pharos.compliance.transaction.repository.TransactionSearchRepository#search}
 * matches the query against. Previously the search matched identifier, MTCN, and external
 * transaction key simultaneously with no way for the caller to say which one they meant -- a value
 * that happened to collide across two different real records (e.g. one row's MTCN equal to another
 * row's external transaction key) resolved arbitrarily. Requiring the caller to pick removes that
 * ambiguity and lets each branch's query scope to exactly one column.
 */
public enum TransactionSearchField {
  MTCN,
  EXTERNAL_TXN_ID
}
