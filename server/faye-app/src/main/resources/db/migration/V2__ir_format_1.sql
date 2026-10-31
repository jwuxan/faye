-- IR format 1 and state format 1 (ADR 0024). Nothing stored in the unversioned format before them
-- is readable any more, so it is dropped (pre release): flows in progress, stored versions and
-- their sources, every tenant's history and current pointer, and preview traces. At startup each
-- tenant's configured flow is published again; drafts are YAML and survive.
DELETE FROM faye_flow;
DELETE FROM faye_trace;
DELETE FROM faye_history;
DELETE FROM faye_version_source;
DELETE FROM faye_version;
UPDATE faye_tenant SET current = NULL;

-- When a version was stored, so a previewed draft nobody published or pins can be deleted once it
-- is older than the flow TTL.
ALTER TABLE faye_version ADD COLUMN stored_at INTEGER NOT NULL DEFAULT 0;

-- The flow that used the last TOTP step: a TOTP code is good once, except to a double submit of
-- the flow that used it.
ALTER TABLE faye_user ADD COLUMN totp_last_flow TEXT;
