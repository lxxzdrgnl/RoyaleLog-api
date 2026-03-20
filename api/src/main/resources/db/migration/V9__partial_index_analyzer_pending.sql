-- V9: Replace (created_at, analyzer_version) composite index with partial index
--
-- Problem with old index (created_at, analyzer_version):
--   - Leading column is created_at, not analyzer_version
--   - Query: WHERE analyzer_version < N → cannot use index efficiently (leading column mismatch)
--   - Workaround of adding created_at range filter = app-level patch, not a real fix
--
-- Partial index design:
--   - Index only contains rows WHERE analyzer_version < 2 (= pending rows)
--   - When a row is processed (analyzer_version updated to 2), it automatically falls out of the index
--   - Index IS the "todo list" — always small, always fresh
--   - No checkpoint metadata needed in Java code → eliminates Gap Problem entirely
--
-- Gap Problem (why checkpoint approach is wrong for multi-threaded batch):
--   - Thread A processing 10:00~10:05, Thread B processing 10:05~10:10
--   - Thread B finishes first → saves last_processed = 10:10
--   - Crash → restart with WHERE created_at > 10:10 → 10:00~10:05 permanently lost
--   - Partial index avoids this: each row's own analyzer_version IS the checkpoint
--
-- Version bump procedure (when current_version increments to 3):
--   Add new migration: DROP INDEX + CREATE with WHERE analyzer_version < 3
--   All existing rows (version=2) re-enter the index automatically on recreate

-- Drop composite index from parent (cascades to all partition child indexes)
DROP INDEX IF EXISTS idx_battle_log_unprocessed;
DROP INDEX IF EXISTS battle_log_raw_2026_01_created_at_analyzer_version_idx;
DROP INDEX IF EXISTS battle_log_raw_2026_02_created_at_analyzer_version_idx;
DROP INDEX IF EXISTS battle_log_raw_2026_03_created_at_analyzer_version_idx;
DROP INDEX IF EXISTS battle_log_raw_2026_04_created_at_analyzer_version_idx;

-- Create partial index on parent partitioned table
-- PostgreSQL automatically creates matching partial indexes on all current and future partitions
CREATE INDEX idx_battle_log_pending
    ON battle_log_raw (created_at ASC, battle_id ASC)
    WHERE analyzer_version < 2;
