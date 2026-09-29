  ), '[]') as "ruleHitsJson"
from merged m
left join pharos.reg_reportable_activity rra
  on rra.txn_sur_key = m.rra_key
