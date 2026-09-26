select (case when rn <= 3 then reason else 'Other' end) as reason, cnt
from %%RANKED_REASONS_CTE%%
