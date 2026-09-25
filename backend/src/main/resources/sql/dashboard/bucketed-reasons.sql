select (case when rn <= 3 then reason else 'Other' end) as reason, cnt
from ranked_reasons
