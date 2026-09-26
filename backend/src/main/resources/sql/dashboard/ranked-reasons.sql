select reason, cnt, row_number() over (order by cnt desc, reason) as rn
from %%REASON_COUNTS_CTE%%
