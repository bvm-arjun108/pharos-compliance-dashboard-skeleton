-- One journey row per identifier in `reporting_target` -- its single most-recently-modified row,
-- regardless of which of that identifier's (possibly several) batches in `batch_scope` it came
-- from. This is the deliberate fix for the bug the per-batch evidence pipeline has for the
-- EXCLUDED/NOT_REPORTED statuses: ever_excluded/ever_reported is rolled up across a transaction's
-- entire batch history, but the per-batch pipeline groups by (evidence_batch_id, identifier) -- so
-- a transaction reprocessed across N batches would surface as N separate rows there, none of them
-- collapsing. Ranking by identifier alone and taking the top row sidesteps that grain entirely.
--
-- Assumes `batch_scope` and `reporting_target` are already CTEs earlier in the same WITH clause.
-- The marked spots are substituted in Java: the fixed journeyOutcome CASE expression and the fixed
-- digits-only guard before casting identifier to bigint for the RRA join key -- both built from
-- EvidenceSqlSupport, not request-derived text. `source_rank` is deliberately left in the output
-- (rather than projected away) -- it passes through as harmless leftover baggage into
-- filtered_evidence/ranked_evidence, distinct from ranked-evidence.sql's own unrelated
-- merge_source_rank column, exactly as the jOOQ version's own Table<?> did.
select * from (
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
    (case when %%RRA_KEY_GUARD%% then j.identifier::bigint else null end) as rra_key,
    row_number() over (partition by j.rpt_grp_id, j.identifier order by j.modified_timestamp desc nulls last) as source_rank
  from pharos.record_transformation_journey j
  join batch_scope bs on bs.rpt_grp_id = j.rpt_grp_id and bs.batch_id = j.batch_id
  join reporting_target tgt on tgt.rpt_grp_id = j.rpt_grp_id and tgt.identifier = j.identifier
) ranked_journey
where source_rank = 1
