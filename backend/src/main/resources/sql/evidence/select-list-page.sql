-- The columns the table renders, and nothing else -- no reg_reportable_activity join and no
-- rule-hit rollup, so a page load neither pays for them nor returns personal data for rows nobody
-- opened. The detail request (select-detail-page.sql) fetches those per transaction instead.
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
  m.exclusion_reason as "exclusionReason",
  m.reported_batch_id as "reportedBatchId",
  m.modified_at as "modifiedAt",
  m.processing_complete as "processingComplete"
from merged m
order by %%ORDER_BY%%
