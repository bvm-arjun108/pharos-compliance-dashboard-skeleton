-- Phase 0 smoke query: proves the .sql resource-loading + NamedParameterJdbcTemplate tracing
-- infrastructure end-to-end before any real repository is migrated. Not wired into any endpoint.
SELECT 1 AS one
