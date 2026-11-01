-- When a tenant published each version, in epoch milliseconds, for the Admin UI's version history.
-- Versions published before this migration have no time recorded and keep NULL.
ALTER TABLE faye_history ADD COLUMN published_at INTEGER;
