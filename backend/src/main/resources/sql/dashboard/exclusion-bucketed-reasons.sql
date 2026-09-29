select (case when rn <= 3 then reason else 'Other' end) as reason, cnt
from exclusion_ranked_reasons
