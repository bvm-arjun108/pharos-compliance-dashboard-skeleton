select coalesce(%%REASON_COLUMN%%, 'Unspecified') as reason, count(*) as cnt
from transaction_roll
where %%FILTER%%
group by coalesce(%%REASON_COLUMN%%, 'Unspecified')
