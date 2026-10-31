---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Versioned schema migrations with Flyway

## Context and Problem Statement

Faye's tables ([ADR 0017](0017-one-sqlite-database.md)) are created by `server/faye-app/src/main/resources/db/schema.sql`: idempotent `CREATE TABLE IF NOT EXISTS` statements that Spring's `spring.sql.init` runs at every start, and that `deploy/directory/seed.mjs` runs too before it writes accounts. Nothing records which version of the schema a database has, so the first change to an existing column (in `faye_user`, `faye_flow`, ...) would need hand written upgrade code, run by two different programs. The user decided (2026-10-31) to delegate this to a migration tool.

Facts checked while deciding:

* Spring Boot 3.5.16 manages Flyway 11.7.2 and configures it automatically when `flyway-core` is on the classpath with a `DataSource`.
* SQLite support is in `flyway-core` itself (`org/flywaydb/core/internal/database/sqlite/` in the 11.7.2 jar); no extra `flyway-database-*` module is needed.
* The database is shared with Hydra, so when Faye starts it usually finds a non empty schema (Hydra's tables) with no Flyway history. Flyway refuses that unless `baselineOnMigrate` is set.

## Decision Drivers

* Every schema change is a forward only, numbered migration that runs once per database.
* One program changes the schema: Faye. The demo seed only writes rows.
* Hydra's tables in the same file are never touched, and nothing can drop them (`clean`).
* Existing databases, made by `schema.sql` before this change, upgrade without manual steps.
* Prefer an existing dependency or what Spring Boot already manages.

## Considered Options

* Flyway (`flyway-core`, Boot managed)
* Liquibase (`liquibase-core`, Boot managed)
* Keep `schema.sql` and write upgrade statements by hand

## Decision Outcome

Chosen option: **Flyway**.

* `db/schema.sql` becomes `db/migration/V1__faye_schema.sql`, unchanged, so it stays idempotent: a database made by the old `schema.sql` migrates cleanly.
* `spring.sql.init` is removed from both `application.yaml` files. Flyway settings, in both:
  * `spring.flyway.table: faye_schema_history`, so Faye's history table carries Faye's prefix beside Hydra's tables.
  * `spring.flyway.baseline-on-migrate: true` and `spring.flyway.baseline-version: 0`: a schema that already has tables (Hydra's, or Faye's from before this change) gets a baseline at version 0, then V1 and later run.
  * `spring.flyway.clean-disabled: true`, explicitly, because `clean` on this database would drop Hydra's tables too.
* `seed.mjs` stops running the schema. It waits (polling once a second, up to 3 minutes) until `faye_schema_history` exists and its latest row succeeded and `faye_user` exists, then writes accounts as before. If Faye never migrates, it exits non zero with a message saying so. Compose's `demo` service and `deploy/dev.sh` stop passing `SCHEMA`; `dev.sh` starts the seed in the background after Faye.

### Consequences

* Good, because a schema change is a new `V{n}__*.sql` file, recorded in `faye_schema_history`, applied once by Faye at startup before it serves.
* Good, because the seed no longer needs the schema file mounted, and two programs no longer race to create the same tables.
* Bad, because the demo client starts only after Faye has migrated (the seed runs before it in `demo`), a few seconds later on a fresh stack.
* Bad, because Flyway on SQLite takes no lock across processes, so two Faye processes migrating one file at once is not supported. SQLite is the demo's database ([ADR 0017](0017-one-sqlite-database.md)), which runs one Faye; a product deployment on a server database migrates under that database's own locking.
* Neutral: Flyway 11.7.2 could log that the bundled SQLite (3.53) is newer than it was tested with. Checked 2026-10-31: the test logs show only `Database: jdbc:sqlite:... (SQLite 3.53)` at INFO and no such warning.

### Confirmation

* `cd server && ./gradlew build` passes, including:
  * `SqliteRestartTest` (two boots on one file: the second migrates nothing).
  * A test that boots on a file which already has the pre Flyway Faye tables, a row in `faye_user`, and an unrelated table standing in for Hydra's: startup succeeds, the row and the unrelated table survive, and `faye_schema_history` holds the baseline (0) and V1.
* On a fresh compose stack, `deploy/smoke.sh` passes and its single database check also finds `faye_schema_history`.

Run on 2026-10-31: `cd server && ./gradlew build` passes (`SqliteRestartTest` 3, `FlywayMigrationTest` 1); `deploy/smoke.sh` passes on a fresh compose stack, `single database ok` included.

## Pros and Cons of the Options

### Flyway

* Good, because Boot manages and configures it, and SQLite support ships in the core jar.
* Good, because migrations are plain SQL files, as `schema.sql` already is.
* Bad, because it is a new runtime dependency of `faye-app` (about 1 MB).

### Liquibase

* Good, because Boot manages it too, and it supports rollbacks and many formats.
* Bad, because it is larger, and its changelog format adds a layer over the plain SQL we have. Rollbacks are not needed: migrations are forward only.

### Keep `schema.sql`

* Good, because there is no new dependency.
* Bad, because version tracking, ordering and run once semantics would be written by hand, which is what this record exists to avoid.
