---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# One SQLite database for Faye's stores and Hydra

## Context and Problem Statement

Faye kept its state in four places: Redis (suspended flows, versions, hooks, tenant settings, drafts, preview traces; [ADR 0008](0008-m3-checks-versions-and-tenant-policy.md), [ADR 0010](0010-m5-admin-ui.md), [ADR 0011](0011-m6-preview-loop.md)), an in memory `UserStore` that lost every enrollment on restart, a JSON user directory file read at startup ([ADR 0014](0014-tenant-user-directory.md)), and Hydra's Postgres. The user decided (2026-10-31): **migrate every data store to a single SQLite database.**

Facts checked while deciding:

* The pinned Hydra image (`oryd/hydra:v2.2.0`, digest in `deploy/compose.yaml`) runs on SQLite: `DSN=sqlite:///data/faye.db?_fk=true&_journal_mode=WAL&_busy_timeout=10000` migrates and serves (tried on 2026-10-31 with `migrate sql -e --yes`). `deploy/hydra-local.sh` already used the `sqlite` release builds.
* The image runs as `ory` (uid 100, gid 101).
* Node 24 (the seed's pinned image) has `node:sqlite` built in, so the seed can write accounts without a dependency.

## Decision Drivers

* One file to back up, inspect and delete; no Redis or Postgres to run.
* Enrollments (TOTP, passkeys) and the user pools survive restarts.
* Charter principle 3: the engine's interfaces (`FlowStore`, `FlowVersions`, `HookStore`, `UserStore`) stay; only `faye-app` changes.
* AGENTS.md rule 7, no binaries in the repo: no database file is committed (`*.db` is git ignored).

## Considered Options

1. Where the stores live
   * A. One SQLite file for Faye and Hydra.
   * B. One SQLite file for Faye; Hydra keeps Postgres.
   * C. Keep Redis.
2. Driver
   * A. `org.xerial:sqlite-jdbc` 3.53.4.0 (published 2026-08-26) with Spring's `JdbcClient` and Hikari, from `spring-boot-starter-jdbc` (in the Boot BOM).
   * B. An ORM (JPA, jOOQ).
3. Schema
   * A. Proper tables per store (`server/faye-app/src/main/resources/db/schema.sql`), run by Spring at startup and by the seed, every statement idempotent.
   * B. One key value table mirroring the Redis keys.

## Decision Outcome

1A, 2A, 3A. 1B keeps two databases where the user asked for one. 2B adds a mapping layer for eight small tables. 3B would keep Redis's shapes (lists, TTLs by key) and make the data hard to read.

* Tables: `faye_flow` (state, revision, `expires_at`; expired rows read as absent and are deleted when the next flow starts), `faye_version`, `faye_version_source`, `faye_history`, `faye_tenant` (current version, draft, settings, scenario), `faye_hook`, `faye_trace` (numbered per tenant; the latest 200 are the feed), `faye_user` (each tenant's pool; passkeys as JSON). Hydra's tables sit beside them; Faye never reads them.
* Accounts in `faye_user` are provisioned outside Faye (in the demo, by `deploy/directory/seed.mjs`); Faye reads them, no admin route writes one, and Faye never sets or changes a password: `password.verify` only reads the hash. The only writes to an account are passkey enrollments (`webauthn.register`) and TOTP state (`totp.enroll`, and the last accepted step `totp.verify` records), plus the preview only accounts of [ADR 0011](0011-m6-preview-loop.md). Those writes belong to the identity handlers of `faye-identity`, a module plugged into the engine through `HandlerRegistry`, and go through its `UserStore` interface; the engine has no account store and no notion of enrollment state. The handlers stay built in and reviewed (charter principle 2); what is pluggable is the module and the store behind it.
* Connections: `jdbc:sqlite:${FAYE_DATABASE:faye.db}?journal_mode=WAL&busy_timeout=10000&transaction_mode=IMMEDIATE`. WAL lets Hydra and Faye read while the other writes; `IMMEDIATE` takes the write lock at `BEGIN`, so a read then write transaction (publish, roll back, a TOTP step update, a trace append) cannot deadlock with another writer, it waits.
* Compose: the `seed` runs first, writes Faye's schema and the accounts, and hands `/data` to uid 100; `hydra-migrate`, `hydra` and `faye` run as `100:101` on the `data` volume, so each can write the journal files the others create. Postgres and Redis are gone.
* Without Docker, `deploy/dev.sh` and `deploy/hydra-local.sh` share `deploy/.data/faye.db` (git ignored).
* Tests: each Spring context gets a fresh file under `faye-app/build/`.

### Consequences

* Good, because the stack is three fewer moving parts (Postgres, Redis, the JSON directory) and all state is one file.
* Good, because enrollments now persist, and the restart test no longer needs a Redis server, so it always runs.
* Neutral: SQLite is the database of the demo and local stacks, not a product requirement. The product is the runtime (the engine, its handlers and the screens) behind the store interfaces; a production deployment puts its own database behind `FlowStore`, `FlowVersions`, `HookStore` and `UserStore`. SQLite's single writer is therefore not benchmarked; no load test is planned for it.
* Bad, because Faye and Hydra now share a file and a uid. Faye cannot be pointed at Hydra's tables by any code path today, but a bug in either can corrupt the other's data. Back up the one file.
* Neutral: the native SQLite library ships inside the `sqlite-jdbc` jar from Maven Central, as other dependencies do; nothing is committed. Java 25 warns about its `System.load`; Java 21 does not.

### Confirmation

* `cd server && ./gradlew build`: `SqliteRestartTest` (a flow, a published version, and an account with TOTP and a passkey survive a restart on one file), and every Spring test running on SQLite.
* `deploy/smoke.sh` step 13, on a fresh compose stack: one database file holds `hydra_client` and Faye's `faye_flow`, `faye_user`, `faye_version` and `faye_schema_history`.

### Changes since this decision

* 3A's `db/schema.sql`, run by Spring and by the seed, was replaced by versioned Flyway migrations that only Faye runs (`db/migration/V1__faye_schema.sql`, history in `faye_schema_history`; [ADR 0020](0020-versioned-schema-migrations-with-flyway.md)). The seed now waits for Faye's migration and only writes rows.
* The compose arrangement changed with [ADR 0019](0019-a-five-service-compose-stack.md): no `seed` service first, no `hydra-migrate` service and no root `chown`; the `hydra` service migrates then serves, the `demo` service runs `bootstrap.mjs` then `seed.mjs`, and every service that opens the database runs as 100:101. The decision to have one database is unchanged.
* Clarified by the user (2026-10-31): SQLite is for the demo; the runtime is the product, so SQLite throughput is not benchmarked. Faye never changes a password; saving passkey enrollments and TOTP state is a function of the pluggable identity module (`faye-identity` over `UserStore`), not of the engine core.

Run on 2026-10-31: `cd server && ./gradlew build` passes (`SqliteRestartTest` 3, `FlywayMigrationTest` 1, every Spring test on SQLite); `deploy/smoke.sh` passes all 13 steps on a fresh compose stack (`docker compose -f deploy/compose.yaml down -v && docker compose -f deploy/compose.yaml up --build -d`), step 13 included.
