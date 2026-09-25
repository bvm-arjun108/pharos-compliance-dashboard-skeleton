-- A reporting period's evidence, unioned from its three possible sources, scoped to every batch
-- in `batch_scope` (a CTE this file assumes is already defined earlier in the same WITH clause --
-- unlike evidence-for-batch.sql's single (reportGroupId, batchId) pair, here the scope is a set of
-- batches, so record_key additionally carries rpt_grp_id to stay unique across them, and every
-- branch also projects rpt_grp_id itself (the batch-scoped pipeline never needs it -- its rule-hit
-- enrichment is already pinned to one batch -- but the period/overview pipelines' enrichment can
-- span every report group in view). The marked spots are substituted in Java: one is the fixed
-- journeyOutcome CASE expression, the other the fixed digits-only guard before casting identifier
-- to bigint for the RRA join key -- both built from EvidenceSqlSupport, not request-derived text.
select
  ('JOURNEY:' || j.rpt_grp_id::text || ':' || j.batch_id || ':' || j.identifier) as record_key,
  j.rpt_grp_id as rpt_grp_id,
  j.identifier as identifier,
  j.mtcn as mtcn,
  j.batch_id as evidence_batch_id,
  'JOURNEY' as evidence_source,
  j.stage as stage,
  j.status as status,
  %%JOURNEY_OUTCOME%% as outcome,
  j.comments as comments,
  j.skip_reason as skip_reason,
  cast(null as text) as rule_id,
  cast(null as text) as exclusion_reason,
  cast(null as text) as exclusion_strategy,
  cast(null as text) as reported_batch_id,
  j.reporting_timestamp_latest::text as reporting_timestamp,
  j.modified_timestamp::text as modified_at,
  j.modified_timestamp as sort_ts,
  j.processing_complete as processing_complete,
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
  (case when %%RRA_KEY_GUARD%% then j.identifier::bigint else null end) as rra_key
from pharos.record_transformation_journey j
join batch_scope bs on bs.rpt_grp_id = j.rpt_grp_id and bs.batch_id = j.batch_id

union all

select
  ('EXCLUSION:' || ea.bucket_id::text || ':' || ea.rule_id || ':' || ea.attempt_id::text) as record_key,
  ea.rpt_grp_id as rpt_grp_id,
  coalesce(ea.external_txn_key::text, ea.attempt_id::text) as identifier,
  ea.mtcn as mtcn,
  ea.processing_batch_id as evidence_batch_id,
  'EXCLUSION_AUDIT' as evidence_source,
  'EXCLUSION' as stage,
  'EXCLUDED' as status,
  'EXCLUDED' as outcome,
  cast(null as text) as comments,
  cast(null as text) as skip_reason,
  ea.rule_id as rule_id,
  ea.exclusion_reason_id as exclusion_reason,
  ea.exclusion_strategy as exclusion_strategy,
  ea.reported_batch_id as reported_batch_id,
  ea.reporting_timestamp::text as reporting_timestamp,
  ea.modified_timestamp::text as modified_at,
  (ea.modified_timestamp at time zone 'UTC') as sort_ts,
  true as processing_complete,
  cast(null as double precision) as currency_amount,
  cast(null as text) as currency_code,
  cast(null as text) as transaction_date,
  cast(null as text) as transaction_side,
  cast(null as text) as txn_source,
  cast(null as text) as activity_type,
  cast(null as text) as send_date,
  cast(null as text) as galactic_id,
  ea.bucket_id as bucket_id,
  ea.attempt_id as attempt_id,
  ea.external_txn_key as rra_key
from pharos.rule_hit_exclusion_audit ea
join batch_scope bs on bs.rpt_grp_id = ea.rpt_grp_id and bs.batch_id = ea.processing_batch_id

union all

select
  ('RULE_HIT:' || bucket_id::text || ':' || rule_id || ':' || attempt_id::text) as record_key,
  rpt_grp_id as rpt_grp_id,
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
