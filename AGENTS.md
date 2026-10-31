# AGENTS.md

Faye is login flow middleware in front of an authorization server; Ory Hydra is the first host. Hydra speaks OAuth2/OIDC; Faye owns every screen and every authentication decision. Flows are declared as YAML, compiled to a JSON S expression IR, and run by a suspendable engine whose continuations persist in SQLite: one database file holds all of Faye's state and, in the compose stack, Hydra's too ([ADR 0017](docs/adr/0017-one-sqlite-database.md)). The plan, milestones (M0 to M7) and decision log are in [docs/roadmap/](docs/roadmap/README.md); read the relevant doc before changing a boundary.

[CHARTER.md](CHARTER.md) sets Faye's mission, non goals and principles. Every change, doc and ADR must respect it. If a task, the roadmap or an ADR conflicts with the charter, the charter wins; stop and raise the conflict rather than working around it. Only a human changes the charter, through an ADR.

## Layout

| Path | What |
|---|---|
| `schema/` | `ir.schema.json` (IR grammar) and `screen.schema.json` (typed screen protocol). Source of truth for both server and web. |
| `conformance/` | Language independent engine vectors (`flows/*.json`, `vectors/*.yaml`, `generated/` from path enumeration, `handlers.yaml`). Format in `conformance/README.md`. |
| `server/` | Gradle multi module, Java 21, Spring Boot 3. |
| `server/faye-ir` | Sealed IR node types, JSON codec. |
| `server/faye-dsl` | YAML to IR compiler. |
| `server/faye-check` | Static checks and path enumeration; `Publisher`, the only way a flow version becomes current. Generates `conformance/generated/`. |
| `server/faye-engine` | Interpreter, continuations, `FlowHost`, `FlowStore`, the handler and screen catalogs (`HandlerRegistry`, `ScreenRegistry`), the verifier, `Previews` (what preview clients run, and their trace events). Runs the conformance suite. |
| `server/faye-hooks` | `HookRuntime` (QuickJS on WebAssembly via quickjs4j: deadline, 16 MiB memory cap, no host access), `SandboxedHooks` (the engine's `HookRunner`; egress grants), `HttpEgress` (named targets). |
| `server/faye-identity` | Identity handlers (`identity.resolve`, `password.verify`, `totp.*`, `webauthn.*`, `consent.record`) over a `UserStore`; the app's store is each tenant's user pool in SQLite, written by `deploy/directory/seed.mjs`. An account with a `tenant` is unknown to other tenants' clients. Preview only accounts exist only for preview clients. |
| `server/faye-host-hydra` | `FlowHost` and the read only `ClientDirectory` for Hydra's admin API. |
| `server/faye-host-mock` | In memory `FlowHost`; used when `FAYE_HYDRA_ADMIN_URL` is unset, so any `login_challenge` works (as client `demo` of the first configured tenant, or of `default` when configured). |
| `server/faye-app` | Spring Boot wiring, screen API, admin API (validate, publish, roll back; on when `FAYE_ADMIN_TOKEN` is set), preview API (draft previews, scenarios, seeded accounts, trace feed over SSE; on when `FAYE_PREVIEW_ENABLED=true`), SQLite flow, version, hook, settings, trace and user stores (`src/main/resources/db/migration/`, Flyway, [ADR 0020](docs/adr/0020-versioned-schema-migrations-with-flyway.md), `FAYE_DATABASE`). Two tenants in `application.yaml` (`faye.tenants`: `acme`, `bank`), their flows in `src/main/resources/flows/*.yaml`; no accounts or passwords in it ([ADR 0018](docs/adr/0018-two-tenants-and-an-open-local-console.md)). Tests use `src/test/resources/application.yaml` instead, with their own tenants and fixed test accounts. |
| `web/` | pnpm workspace, Next.js App Router, TypeScript. `Dockerfile` builds one image holding the three apps' standalone output. |
| `web/packages/contract` | TS types generated from `schema/screen.schema.json`. Never edit `src/` by hand. |
| `web/packages/ui` | Shared shadcn/ui components and theme (system UI and mono fonts, Geist Serif wordmark). |
| `web/apps/login` | Renders typed screens (`:3000`). |
| `web/apps/demo-client` | OIDC relying party (`:3001`) of every client bootstrap registers, reading the applications' secrets from `CLIENT_REGISTRATIONS` (the secrets volume): launch panel, and a result view with the preview trace and the `amr` / `acr` expectation check. |
| `web/apps/admin` | Admin UI (`:3002`): a console with a collapsible sidebar. Login flow (blocks or YAML beside the live graph; block inspector, paths and Preview in a side panel), Hooks, Versions, read only Applications (from the authorization server, each with Try sign in through the demo client) and Users (the tenant's user pool), General and Branding settings ([ADR 0013](docs/adr/0013-admin-console-shell-and-flow-workspace.md)). Has no sign in: it proxies the admin API with the server token, so it must only be reachable locally (compose binds it to 127.0.0.1). `lib/` holds the editor's document model and its tests. |
| `Dockerfile` | The one image: every process under s6-overlay (hydra, faye, login, admin, and with `FAYE_DEMO=true` the demo's four; `FAYE_SERVICES` selects a subset), one volume at `/var/lib/faye` ([ADR 0023](docs/adr/0023-one-container-many-processes.md)). `deploy/image/` holds the s6 service tree (`s6-rc.d/`), the service scripts (`scripts/`), `stage2-hook` (service selection) and `healthcheck`. |
| `deploy/` | `compose.yaml` (one service, `faye`, on the root image), `image/` (see `Dockerfile`), `directory/seed.mjs` (writes each tenant's user pool into the SQLite database once Faye has migrated the schema: hashes in the database, passwords only under `/var/lib/faye/demo`), `bootstrap.mjs` (registers a preview client per tenant, and ten applications, five per tenant, whose ids and secrets Hydra generates), `ip-reputation.mjs` (stub egress target for the risk hook), `dev.sh` (the stack without Docker), `fuzz.sh` (coverage guided fuzzing of every harness, ADR 0007), `smoke.sh` (scripted sign ins, admin publishes, hooks, the Admin UI and a draft preview, a sign in through each of the ten applications, the single database: the M2 to M6 checks), `hydra-local.sh` (Hydra without Docker). |

## Commands

Server (needs JDK 21+; `java` on PATH may be older):

```sh
cd server
./gradlew build                 # compile with -Xlint:all -Werror, spotlessCheck, checkstyle, unit + conformance tests
./gradlew spotlessApply         # format Java (palantir-java-format) and *.gradle.kts (ktlint)
FAYE_REGENERATE=1 ./gradlew :faye-check:test   # rewrite conformance/generated after changing a conformance flow or the checker
../deploy/fuzz.sh 2m [Harness]  # coverage guided fuzzing, one harness per JVM (ADR 0007); `build` only replays stored inputs
./gradlew :faye-app:bootRun     # :8080
```

Each test context gets a fresh SQLite database under `faye-app/build/`; `SqliteRestartTest` boots twice on one file.

Web:

```sh
cd web
pnpm install
pnpm generate                   # regenerate contract types after editing schema/screen.schema.json
pnpm typecheck && pnpm lint && pnpm test && pnpm build   # test: node --test over apps/admin/lib
```

End to end:

```sh
docker compose -f deploy/compose.yaml up --build   # or: docker build -t faye . && docker run -d --name faye -v faye-data:/var/lib/faye -e FAYE_DEMO=true -p 4444:4444 -p 8080:8080 -p 3000:3000 -p 3001:3001 -p 127.0.0.1:3002:3002 faye
deploy/smoke.sh                 # seeded accounts, read via `docker exec faye cat /var/lib/faye/demo/`; run against a fresh stack
```

CI (`.github/workflows/ci.yml`) runs the server build, the web checks plus a diff check that generated contract types match the schema, and the compose stack with `smoke.sh`. All three must pass. `.github/workflows/fuzz.yml` fuzzes every harness for 2 minutes on pull requests that touch the server and 30 minutes nightly; a finding is a security bug until triaged ([ADR 0007](docs/adr/0007-fuzz-and-adversarial-testing.md)).

## Rules

1. Changing the IR or screen protocol means changing `schema/` first, then the Java types, the generated TS (`pnpm generate`), [ir-spec.md](docs/roadmap/ir-spec.md) and conformance vectors together. A change to what the IR means bumps the IR format ([ADR 0024](docs/adr/0024-core-ir-as-a-narrow-waist.md)).
2. Engine behavior changes need a conformance vector, not just a Java unit test. Every scripted handler outcome in a vector must be consumed. Never edit `conformance/generated/` by hand; regenerate it. A new parser, handler, screen type, IR form, host or store also needs a fuzz harness, or an existing one extended (ADR 0007); a fuzz finding lands as a readable regression (a vector, a named test or a text seed), never as a raw crash file.
3. The engine never imports Hydra. Authorization server specifics stay behind `FlowHost`.
4. Hooks (later milestones) may only `set`, `fail`, or `pick` among declared options; they can never complete authentication. `amr`/`acr` are computed by the engine from credentials that actually verified, never configured.
5. Safety checks live only in Java; the web apps call the server rather than duplicating rules.
6. Screens are typed templates. Add a screen type to the protocol rather than rendering free form UI.
7. No binaries in the repo. Third party tools are downloaded on demand into git ignored dirs (e.g. `deploy/.bin/`) with versions pinned by checksum or image digest.
8. Java compiles with `-Werror`; web lint runs with `--max-warnings 0`. Fix warnings, don't suppress them. Java layout is whatever `./gradlew spotlessApply` produces, and Checkstyle (`server/config/checkstyle/checkstyle.xml`) fails the build on any violation ([ADR 0003](docs/adr/0003-enforce-java-format-and-lint-in-the-build.md)).
9. Every Java package has a `package-info.java` marked `@NullMarked`. Types are non-null unless annotated `@Nullable` (JSpecify); NullAway enforces this at compile time ([ADR 0002](docs/adr/0002-enforce-null-safety-with-jspecify-and-nullaway.md)).

## Decisions

Record breaking changes as ADRs in [docs/adr/](docs/adr/README.md), MADR 4 format, one file per decision (`NNNN-title.md`, next free number, never renumbered).

1. Write an ADR only for a breaking change: one that changes a boundary, the charter, the IR or screen protocol, persisted state that existing data or clients must still read, or an API or configuration contract that callers depend on. Everything else (dependency bumps, UI and styling, refactors, fixes) needs no ADR; update the docs it makes stale instead.
2. List the options actually considered, with pros and cons, and a `Confirmation` section naming the command or test that proves the decision holds.
3. Mark unverified claims as such, with how to verify them. Back claims with evidence (a benchmark, a test, a link to the source) where you can.
4. A record's status is one of four:
   - `proposed`: planned, not implemented yet. New records start here.
   - `in progress`: implementation has started but is incomplete, or known gaps remain. List the open gaps in the record.
   - `accepted`: fully implemented, every gap closed, and the `Confirmation` passes. Only a human marks one `accepted`.
   - `superseded by NNNN`: replaced by a later record. To change a decision, add a record that supersedes the old one and set the old one's status to this.

   Keep the status current: update it in the record and in the `docs/adr/README.md` table in the same change that moves the work forward.
5. Add each record to the table in `docs/adr/README.md`. If it changes a D1 to D13 entry, update [decisions-and-risks.md](docs/roadmap/decisions-and-risks.md) to link to it.

## Subagents

Spawn subagents freely when they help; you don't need to ask. Run independent ones in parallel. Good uses:

1. Explore: sweep the codebase for usages, call sites, or conventions before changing them.
2. Search: read upstream docs, specs (OAuth2, OIDC, Hydra, MADR), release notes and issue trackers.
3. Best practice: check how mature projects solve the same problem, and the current recommended approach for a library or standard, before settling on one.
4. Benchmark: measure competing options with a small throwaway harness in a scratch or git ignored dir, and report numbers with the setup used.
5. Review: get an independent read of a diff or an ADR draft. Give the reviewer the code, not your conclusion.

Give each subagent a narrow goal, the files and docs to read, and what is already ruled out. Treat what it returns as evidence to check, not as fact; cite it in the ADR where it backs a decision. Subagents follow this file too: they never commit, and their scratch code does not land in the repo.
