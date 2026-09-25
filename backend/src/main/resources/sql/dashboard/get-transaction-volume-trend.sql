select
  p.period_start as "periodStart",
  coalesce(pm.total_reported_transactions, 0) as "totalReportedTransactions",
  coalesce(pm.total_excluded_transactions, 0) as "totalExcludedTransactions"
from periods p
left join period_metrics pm using (period_start)
order by p.period_start
