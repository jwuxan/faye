-- Delivered one time codes (ADR 0032).

-- The account's phone: the number, whether the person proved they hold it, and their switch for
-- texted codes (off by default). Flows read only user.has_phone: verified and switched on.
ALTER TABLE faye_user ADD COLUMN phone TEXT;
ALTER TABLE faye_user ADD COLUMN phone_verified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE faye_user ADD COLUMN phone_codes INTEGER NOT NULL DEFAULT 0;

-- Codes waiting to be delivered, and the recent sends the limits count. A row without a destination
-- (an unknown account) is never delivered. The code is cleared once the row is delivered or given
-- up; rows are deleted a day after they were made.
CREATE TABLE IF NOT EXISTS faye_outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant TEXT NOT NULL,
  channel TEXT NOT NULL,
  destination TEXT,
  limit_key TEXT NOT NULL,
  code TEXT,
  created_at INTEGER NOT NULL,
  state TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS faye_outbox_state ON faye_outbox (state, id);
CREATE INDEX IF NOT EXISTS faye_outbox_limit ON faye_outbox (limit_key, created_at);
CREATE INDEX IF NOT EXISTS faye_outbox_tenant ON faye_outbox (tenant, channel, created_at);

-- Server keys Faye generates once, when the operator sets none (the code MAC key).
CREATE TABLE IF NOT EXISTS faye_key (
  name TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
