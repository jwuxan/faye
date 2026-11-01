-- Faye's tables, in the one SQLite database it shares with the authorization server (ADR 0017).
-- Flyway runs this as V1 at startup; it is idempotent, so a database the pre Flyway schema.sql made
-- migrates cleanly (ADR 0020). Later schema changes go in new V{n}__*.sql files; never edit V1.
-- Hydra's own tables live beside these and Faye never reads them.

-- Suspended flows. A flow expires with its challenge however often it is resumed.
CREATE TABLE IF NOT EXISTS faye_flow (
  id TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  state TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS faye_flow_expires ON faye_flow (expires_at);

-- Published flow versions by hash, as canonical IR. Never deleted, so a flow pinned to one can finish.
CREATE TABLE IF NOT EXISTS faye_version (
  hash TEXT PRIMARY KEY,
  ir TEXT NOT NULL
);

-- The YAML a version was first published from.
CREATE TABLE IF NOT EXISTS faye_version_source (
  hash TEXT PRIMARY KEY,
  yaml TEXT NOT NULL
);

-- The hashes each tenant published, in order.
CREATE TABLE IF NOT EXISTS faye_history (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant TEXT NOT NULL,
  hash TEXT NOT NULL,
  UNIQUE (tenant, hash)
);

-- Per tenant: its current version, draft (YAML), and the settings and preview scenario saved
-- through the admin API (JSON). Tenants themselves are configured, not stored.
CREATE TABLE IF NOT EXISTS faye_tenant (
  id TEXT PRIMARY KEY,
  current TEXT,
  draft TEXT,
  settings TEXT,
  scenario TEXT
);

-- Hook definitions by hash, as canonical JSON. Never deleted, like versions.
CREATE TABLE IF NOT EXISTS faye_hook (
  hash TEXT PRIMARY KEY,
  def TEXT NOT NULL
);

-- Preview trace events, numbered per tenant. A tenant's latest 200 make its feed; a flow's events
-- are kept until expires_at, which each new event of the flow pushes back.
CREATE TABLE IF NOT EXISTS faye_trace (
  tenant TEXT NOT NULL,
  seq INTEGER NOT NULL,
  flow_id TEXT NOT NULL,
  entry TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (tenant, seq)
);
CREATE INDEX IF NOT EXISTS faye_trace_flow ON faye_trace (flow_id);

-- Each tenant's user pool. An account with a null tenant is shared by every tenant; the seed gives
-- every account a tenant. identifier is the normalized email address. passkeys is a JSON array of
-- {credentialId, attestedCredentialData (base64), signCount}.
CREATE TABLE IF NOT EXISTS faye_user (
  subject TEXT PRIMARY KEY,
  identifier TEXT NOT NULL UNIQUE,
  name TEXT,
  tenant TEXT,
  email_verified INTEGER NOT NULL DEFAULT 0,
  password_hash TEXT,
  totp_secret TEXT,
  totp_last_step INTEGER NOT NULL DEFAULT -1,
  passkeys TEXT NOT NULL DEFAULT '[]',
  mfa_required INTEGER NOT NULL DEFAULT 0,
  preview INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS faye_user_tenant ON faye_user (tenant);
