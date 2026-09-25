select
  batches_ran as "batchesRan",
  batches_needing_attention as "batchesNeedingAttention",
  transformation_failure_batches as "transformationFailureBatches",
  missing_attempt_batches as "missingAttemptBatches",
  activity_missing_batches as "activityMissingBatches",
  duplicate_transaction_batches as "duplicateTransactionBatches",
  exclusion_batches as "exclusionBatches",
  simulated_transaction_batches as "simulatedTransactionBatches",
  soft_dedup_batches as "softDedupBatches"
from rtr_aggregates
