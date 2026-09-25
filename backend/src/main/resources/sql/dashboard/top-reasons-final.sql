select reason as "reason", sum(cnt)::bigint as "count"
from bucketed_reasons
group by reason
order by (reason = 'Other') asc, sum(cnt) desc
