-- Detail-only projection: the RRA (reg_reportable_activity) party join and the rule-hit JSON
-- rollup, both scoped to just this page's rows. The rule-hit rollup is a plain scalar subquery
-- (not an explicit LATERAL) -- Postgres already correlates a SELECT-list subquery per outer row,
-- and json_agg over zero matching rows still returns exactly one row with a NULL value (the same
-- "aggregate over an empty set produces one null row" behavior the original LATERAL + json_agg
-- form relied on), so coalescing straight to '[]' behaves identically either way.
select
  m.record_key as "recordKey",
  m.identifier as "identifier",
  m.mtcn as "mtcn",
  m.evidence_batch_id as "batchId",
  m.evidence_source as "evidenceSource",
  m.stage as "stage",
  m.status as "status",
  m.outcome as "outcome",
  m.comments as "comments",
  m.skip_reason as "skipReason",
  m.rule_id as "ruleId",
  m.exclusion_reason as "exclusionReason",
  m.exclusion_strategy as "exclusionStrategy",
  m.reported_batch_id as "reportedBatchId",
  m.reporting_timestamp as "reportingTimestamp",
  m.modified_at as "modifiedAt",
  m.processing_complete as "processingComplete",
  (case when m.evidence_source = 'JOURNEY' then coalesce(rra.s_local_principal, rra.r_local_principal) else m.currency_amount end)
    as "currencyAmount",
  (case when m.evidence_source = 'JOURNEY' then coalesce(rra.s_currency, rra.r_currency) else m.currency_code end) as "currencyCode",
  (case when m.evidence_source = 'JOURNEY' then coalesce(rra.s_date, rra.r_date) else m.transaction_date end) as "transactionDate",
  m.transaction_side as "transactionSide",
  m.txn_source as "txnSource",
  m.activity_type as "activityType",
  (case when m.evidence_source = 'JOURNEY' then rra.group_send_date else m.send_date end) as "sendDate",
  m.galactic_id as "galacticId",
  m.bucket_id as "bucketId",
  m.attempt_id as "attemptId",
  rra.s_party_name as "senderName",
  rra.r_party_name as "receiverName",
  rra.s_party_city as "senderCity",
  rra.s_party_country_of_residence as "senderCountry",
  rra.s_party_phone_number as "senderPhone",
  rra.s_party_date_of_birth as "senderDateOfBirth",
  rra.s_party_id_type as "senderIdType",
  rra.s_party_id_number as "senderIdNumber",
  rra.r_party_city as "receiverCity",
  rra.r_party_country_of_residence as "receiverCountry",
  rra.r_party_phone_number as "receiverPhone",
  rra.r_party_date_of_birth as "receiverDateOfBirth",
  rra.r_party_id_type as "receiverIdType",
  rra.r_party_id_number as "receiverIdNumber",
  rra.txn_status as "transactionStatus",
  rra.sub_status as "transactionSubStatus",
  coalesce((
    select json_agg(json_build_object(
      'ruleId', rhm.rule_id,
      'isReported', rhm.is_reported,
      'reportingTimestamp', rhm.reporting_timestamp,
      'bucketId', rhm.bucket_id,
      'attemptId', rhm.attempt_id
    ) order by rhm.rule_id)::text
    from rule_hit_matches_enrichment rhm
    where rhm.matched_identifier is not null
      and rhm.matched_identifier = m.identifier
      %%REPORT_GROUP_FILTER%%
  ), '[]') as "ruleHitsJson"
from merged m
left join pharos.reg_reportable_activity rra
  on rra.txn_sur_key = m.rra_key
order by %%ORDER_BY%%
