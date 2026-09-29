select reason, cnt, row_number() over (order by cnt desc, reason) as rn
from exclusion_reason_counts
