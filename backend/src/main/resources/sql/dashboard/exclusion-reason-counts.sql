select coalesce(exclusion_reason, 'Unspecified') as reason, count(*) as cnt
from transaction_roll
where ever_excluded and not ever_reported
group by coalesce(exclusion_reason, 'Unspecified')
