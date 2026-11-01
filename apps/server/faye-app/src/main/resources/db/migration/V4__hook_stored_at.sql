-- When each hook was last stored, in epoch milliseconds, so the version sweep can retire hooks
-- no kept version names. Hooks stored before this migration get the time of the migration.
ALTER TABLE faye_hook ADD COLUMN stored_at INTEGER NOT NULL DEFAULT 0;
UPDATE faye_hook SET stored_at = CAST(strftime('%s','now') AS INTEGER) * 1000;
