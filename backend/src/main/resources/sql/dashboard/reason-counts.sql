select coalesce(reason, 'Unspecified') as reason, count(*) as cnt
from roll
where %%FILTER%%
group by coalesce(reason, 'Unspecified')
