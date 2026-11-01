-- QR code sign in (ADR 0032): one row per QR code a waiting device shows, by the hash of its token.
-- A row is pending until the phone's approve flow approves it (subject, amr) or refuses it, and is
-- consumed once by the waiting flow. Rows are deleted a day after they expired.
CREATE TABLE IF NOT EXISTS faye_qr_grant (
  token_hash TEXT PRIMARY KEY,
  flow_id TEXT NOT NULL,
  tenant TEXT NOT NULL,
  preview INTEGER NOT NULL DEFAULT 0,
  client TEXT NOT NULL,
  number INTEGER NOT NULL,
  device TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  status TEXT NOT NULL,
  subject TEXT,
  amr TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS faye_qr_grant_expires ON faye_qr_grant (expires_at);
