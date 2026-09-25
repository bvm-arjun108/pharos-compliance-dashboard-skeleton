select
  count(*) as "selected",
  count(*) filter (where ever_reported or not ever_excluded) as "expected",
  count(*) filter (where ever_excluded and not ever_reported) as "excluded",
  count(*) filter (where not ever_reported and not ever_excluded) as "notReported"
from roll
