-- One reconciliation batch's evidence, unioned from its three possible sources. The marked spots
-- are substituted in Java: one is the fixed journeyOutcome CASE expression, the other the fixed
-- digits-only guard before casting identifier to bigint for the RRA join key -- both built from
-- EvidenceSqlSupport, not request-derived text.
select
  ('JOURNEY:' || identifier) as record_key,
  identifier as identifier,
  mtcn as mtcn,
  batch_id as evidence_batch_id,
  'JOURNEY' as evidence_source,
  stage as stage,
  status as status,
  %%JOURNEY_OUTCOME%% as outcome,
  comments as comments,
  skip_reason as skip_reason,
  cast(null as text) as rule_id,
  cast(null as text) as exclusion_reason,
  cast(null as text) as exclusion_strategy,
  cast(null as text) as reported_batch_id,
  reporting_timestamp_latest::text as reporting_timestamp,
  modified_timestamp::text as modified_at,
  modified_timestamp as sort_ts,
  processing_complete as processing_complete,
  cast(null as double precision) as currency_amount,
  cast(null as text) as currency_code,
  cast(null as text) as transaction_date,
  cast(null as text) as transaction_side,
  cast(null as text) as txn_source,
  cast(null as text) as activity_type,
  cast(null as text) as send_date,
  cast(null as text) as galactic_id,
  cast(null as integer) as bucket_id,
  cast(null as bigint) as attempt_id,
  (case when %%RRA_KEY_GUARD%% then identifier::bigint else null end) as rra_key
from pharos.record_transformation_journey
where rpt_grp_id = :reportGroupId and batch_id = :batchId

union all

select
  ('EXCLUSION:' || bucket_id::text || ':' || rule_id || ':' || attempt_id::text) as record_key,
  coalesce(external_txn_key::text, attempt_id::text) as identifier,
  mtcn as mtcn,
  processing_batch_id as evidence_batch_id,
  'EXCLUSION_AUDIT' as evidence_source,
  'EXCLUSION' as stage,
  'EXCLUDED' as status,
  'EXCLUDED' as outcome,
  cast(null as text) as comments,
  cast(null as text) as skip_reason,
  rule_id as rule_id,
  exclusion_reason_id as exclusion_reason,
  exclusion_strategy as exclusion_strategy,
  reported_batch_id as reported_batch_id,
  reporting_timestamp::text as reporting_timestamp,
  modified_timestamp::text as modified_at,
  (modified_timestamp at time zone 'UTC') as sort_ts,
  true as processing_complete,
  cast(null as double precision) as currency_amount,
  cast(null as text) as currency_code,
  cast(null as text) as transaction_date,
  cast(null as text) as transaction_side,
  cast(null as text) as txn_source,
  cast(null as text) as activity_type,
  cast(null as text) as send_date,
  cast(null as text) as galactic_id,
  bucket_id as bucket_id,
  attempt_id as attempt_id,
  external_txn_key as rra_key
from pharos.rule_hit_exclusion_audit
where rpt_grp_id = :reportGroupId and processing_batch_id = :batchId

union all

select
  ('RULE_HIT:' || bucket_id::text || ':' || rule_id || ':' || attempt_id::text) as record_key,
  matched_identifier as identifier,
  mtcn as mtcn,
  efile_batch_id as evidence_batch_id,
  'RULE_HIT' as evidence_source,
  'RULE_HIT' as stage,
  (case when is_reported then 'REPORTED' else 'NOT_REPORTED' end) as status,
  (case when is_reported then 'SUCCESS' else 'PENDING' end) as outcome,
  cast(null as text) as comments,
  cast(null as text) as skip_reason,
  rule_id as rule_id,
  exclusion_reason_id as exclusion_reason,
  cast(null as text) as exclusion_strategy,
  reported_batch_id as reported_batch_id,
  reporting_timestamp::text as reporting_timestamp,
  modified_timestamp::text as modified_at,
  modified_timestamp as sort_ts,
  true as processing_complete,
  rule_currency_amount::double precision as currency_amount,
  rule_iso_currency_code as currency_code,
  transaction_date::text as transaction_date,
  transaction_side as transaction_side,
  source as txn_source,
  activity_type as activity_type,
  send_date::text as send_date,
  galactic_id as galactic_id,
  bucket_id as bucket_id,
  attempt_id as attempt_id,
  external_txn_key as rra_key
from rule_hit_matches
where matched_identifier is not null
