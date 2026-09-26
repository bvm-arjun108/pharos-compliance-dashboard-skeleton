select
  'OVERVIEW' as "rowType",
  0 as "sectionOrder",
  selected as "selected",
  expected as "expected",
  excluded as "excluded",
  "notReported" as "notReported",
  null::text as "reason",
  null::bigint as "count",
  false as "otherReason"
from transaction_overview
union all
select
  'EXCLUSION_REASON' as "rowType",
  1 as "sectionOrder",
  null::bigint as "selected",
  null::bigint as "expected",
  null::bigint as "excluded",
  null::bigint as "notReported",
  reason as "reason",
  sum(cnt)::bigint as "count",
  reason = 'Other' as "otherReason"
from exclusion_bucketed_reasons
group by reason
union all
select
  'NOT_REPORTED_REASON' as "rowType",
  2 as "sectionOrder",
  null::bigint as "selected",
  null::bigint as "expected",
  null::bigint as "excluded",
  null::bigint as "notReported",
  reason as "reason",
  sum(cnt)::bigint as "count",
  reason = 'Other' as "otherReason"
from not_reported_bucketed_reasons
group by reason
order by "sectionOrder", "otherReason", "count" desc
