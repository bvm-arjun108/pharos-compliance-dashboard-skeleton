package com.pharos.compliance.transaction.repository.projection;

/**
 * One raw evidence row matching a transaction search, from journey, exclusion-audit, or rule_hit
 *  -- unmerged and unscoped, since {@link TransactionSearchResultProjection}'s whole purpose is to
 *  surface every place a single MTCN or external transaction key was evaluated, including
 *  contradictory outcomes across report groups or rule sides.
 */
public record TransactionSearchResultProjection(int reportGroupId, String reportGroupName, String countryCode, String countryName,
    String batchId, String evidenceSource, String stage, String status, String comments, String matchedOn, String occurredAt, String mtcn) {}
