select coalesce(not_reported_reason, 'Unspecified') as reason, count(*) as cnt
from transaction_roll
where not ever_reported and not ever_excluded
group by coalesce(not_reported_reason, 'Unspecified')
