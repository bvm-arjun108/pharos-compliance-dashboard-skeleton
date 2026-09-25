-- Attaches the EXCLUSION_AUDIT > RULE_HIT > JOURNEY priority rank used everywhere a transaction's
-- evidence needs to be collapsed to one row -- shared foundation for both the cheap Pass 1
-- identifier-sort-keys pass and the full Pass 2 per-column merge, so both agree on exactly the
-- same "which row wins" priority.
select
  *,
  (case
    when evidence_source = 'EXCLUSION_AUDIT' then 1
    when evidence_source = 'RULE_HIT' then 2
    else 3
  end) as merge_source_rank
from filtered_evidence
