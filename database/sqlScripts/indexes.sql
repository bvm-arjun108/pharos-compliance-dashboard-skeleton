-- 1. Primary dashboard and Batch Explorer date-range access
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_rtr_created_timestamp
    ON pharos.report_transformation_reconciliation (created_timestamp);

-- 2. Not-yet-reported and journey-based date-range access
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_journey_created_timestamp
    ON pharos.record_transformation_journey (created_timestamp);

-- Required by the next two indexes
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 3. Partial batch-ID search against reconciled batches
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_rtr_batch_id_trgm
    ON pharos.report_transformation_reconciliation
    USING gin (lower(batch_id) gin_trgm_ops);

-- 4. Partial batch-ID search against not-yet-reported journey batches
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_journey_batch_id_trgm
    ON pharos.record_transformation_journey
    USING gin (lower(batch_id) gin_trgm_ops);

-- 5. Latest report-group configuration lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_report_group_config_latest
    ON pharos.report_group_config (
    rpt_grp_id,
    modified_timestamp DESC NULLS LAST,
    created_timestamp DESC NULLS LAST,
    rpt_selection_version_id DESC,
    transformer_version_id DESC
    );