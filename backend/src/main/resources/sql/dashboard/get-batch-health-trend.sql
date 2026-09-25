select
  p.period_start as "periodStart",
  coalesce(pm.batches_ran, 0) as "batchesRan",
  coalesce(pm.batches_ran, 0) - coalesce(pm.batches_needing_attention, 0) as "successfulBatches",
  coalesce(pm.batches_needing_attention, 0) as "batchesNeedingAttention"
from periods p
left join period_metrics pm using (period_start)
order by p.period_start
