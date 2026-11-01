# AGENTS.md

Faye is an embeddable, configurable flow runtime built only for sign in and sign up, run as login middleware in front of an authorization server; Ory Hydra is the first host. Hydra speaks OAuth2/OIDC; Faye owns every screen and every authentication decision. Flows are declared as YAML, compiled to a JSON S expression IR, and run by a suspendable engine whose continuations persist in SQLite: one database file holds all of Faye's state and, in the compose stack, Hydra's too ([ADR 0017](docs/adr/0017-one-sqlite-database.md)). Decisions are in [docs/adr/](docs/adr/README.md); the founding decision log (D1 to D13) and the work still open are in [ADR 0036](docs/adr/0036-retire-the-roadmap.md). Read the relevant record before changing a boundary.

[CHARTER.md](CHARTER.md) sets Faye's mission, non goals and principles. Every change, doc and ADR must respect it. If a task or an ADR conflicts with the charter, the charter wins; stop and raise the conflict rather than working around it. Only a human changes the charter, through an ADR.

## Layout

| Path | What |
|---|---|
| `brand/` | The Faye textmark (Playwrite Italia Tradizionale as paths): logo and square icon, light and dark, SVG and PNG. `generate.py` writes them, the admin's `icon.svg` and `apple-icon.png`, and `FayeLogo` in `apps/web/packages/ui`; edit the script, never its outputs. |
| `src/` | The product: a standalone Gradle build of the two shippable libraries, Java 21. |
| `src/faye-core` | `faye.ir` (sealed IR node types, JSON codec), `faye.dsl` (YAML to IR compiler; `BlockSpec`, the blocks a step may be, which its caller supplies), `faye.hooks` (`HookRuntime`: QuickJS on WebAssembly via quickjs4j with deadline and memory cap, `HttpEgress`, `HookException`). |
| `src/faye-engine` | `faye.engine` (interpreter, continuations, catalogs, `SandboxedHooks`: the `HookRunner` over the core's sandbox, with tenant egress grants), `faye.engine.spi` (what a host implements: `FlowHost` with the closed host contract `HostParams`/`Completion`, `FlowStore`, `FlowVersions`, `HookStore`, and the credential SPIs `UserDirectory`, `PasswordAuthenticator`, `OtpIssuer`, `OtpVerifier`, `PasskeyAuthenticator`, and for delivered codes and QR code sign in `CodeOutbox`, `PhoneDirectory`, `QrGrants`), `faye.check` (static checks and path enumeration; `Publisher`, the only way a flow version becomes current; generates `src/conformance/generated/`), `faye.screens` (the screens of screen protocol v1, `BuiltinScreen.registry(issuers)`), `faye.handlers` (the built in handlers `BuiltinHandlers` and issuers `BuiltinIssuers` written only against the SPIs, the emailed and texted codes `DeliveredCodes` and QR code sign in `QrSignIn` ([ADR 0032](docs/adr/0032-delivered-one-time-codes-and-qr-sign-in.md)), and their blocks `BuiltinBlock`). `testFixtures` package `faye.engine.testing` holds `MockFlowHost`, `FixedIssuers`, `InMemoryCredentials`, `InMemoryOutbox`, `InMemoryQrGrants` and the SPI contract kits. Knows no host ([ADR 0025](docs/adr/0025-host-neutral-boundaries-for-embedding.md); `BoundaryTest`). Runs the conformance suite. |
| `src/schema` | `ir.schema.json` (IR grammar) and `screen.schema.json` (typed screen protocol). Source of truth for both server and web. |
| `src/conformance` | Language independent engine vectors (`flows/*.json`, `vectors/*.yaml`, `generated/` from path enumeration, `handlers.yaml`, `blocks.yaml`). Format in `src/conformance/README.md`. |
| `src/build-logic` | The Gradle convention plugins (`faye.java-conventions`, `faye.library-conventions`) both builds share. |
| `docs/` | The project docs, plain Markdown that GitHub renders too: `index.md`, `start/`, `understand/`, `build/`, `console/`, `reference/`, `develop/`, `ir-spec.md` and `adr/` (the decision records), ordered by `nav.yaml`. Each page has front matter with a `description`; GitHub alerts (`> [!NOTE]`), numbered `### 1.` step headings and ` ```mermaid ` diagrams render as callouts, steps and diagrams. Style guide: `docs/develop/write-docs.md`. The Admin UI renders them at `/docs` with Fumadocs from `FAYE_DOCS_DIR` (`/opt/faye/docs` in the image), each page also as Markdown at its URL plus `.md`, with `/llms.txt` and `/llms-full.txt`. |
| `apps/server/` | The prototype: a separate Gradle build (Spring Boot 3) that includes `src` as a composite. |
| `apps/server/faye-identity` | The credential SPI adapters (`LocalCredentials`) over a `UserStore`, plus `User`, `InMemoryUserStore`, `Passwords` (Argon2), `Totp`, `Base32`, `WebAuthnSettings`; the app's store is each tenant's user pool in SQLite, written by `deploy/directory/seed.mjs`. An account with a `tenant` is unknown to other tenants' clients. Preview only accounts exist only for preview clients. |
| `apps/server/faye-clients` | `ClientDirectory`, the admin SPI over the authorization server's clients (read only, for the Admin UI). Depends on nothing in Faye; the core never sees it. |
| `apps/server/faye-host-hydra` | `FlowHost` and the read only `ClientDirectory` for Hydra's admin API; `DSL_PATH_ALIASES` (`hydra.` for `host.`), which the app hands the compiler. |
| `apps/server/faye-app` | Spring Boot wiring, screen API, admin API (validate, publish, roll back; served only over HTTP on the Unix domain socket `FAYE_ADMIN_SOCKET`, never on the TCP port, and on when that and `FAYE_ADMIN_TOKEN` are set, [ADR 0028](docs/adr/0028-admin-api-on-a-unix-domain-socket.md)), preview API (draft previews, scenarios, seeded accounts, trace feed over SSE; on when `FAYE_PREVIEW_ENABLED=true`), the code outbox and its worker (`SqliteCodeOutbox`, `OutboxWorker`, `CodeDelivery`: each tenant's webhook per channel, else the outbox directory `FAYE_DEMO_OUTBOX`, else the channel is off; [ADR 0032](docs/adr/0032-delivered-one-time-codes-and-qr-sign-in.md)), QR code sign in's grants and approval host (`SqliteQrGrants`, `QrApprovalHost`, `POST /api/approve/{token}`), SQLite flow, version, hook, settings, trace and user stores (`src/main/resources/db/migration/`, Flyway, [ADR 0020](docs/adr/0020-versioned-schema-migrations-with-flyway.md), `FAYE_DATABASE`). Two tenants in `application.yaml` (`faye.tenants`: `acme`, `bank`), their flows in `src/main/resources/flows/*.yaml`; no accounts or passwords in it ([ADR 0018](docs/adr/0018-two-tenants-and-an-open-local-console.md)). Tests use `src/test/resources/application.yaml` instead, with their own tenants and fixed test accounts. |
| `apps/web/` | pnpm workspace, Next.js App Router, TypeScript. The root `Dockerfile` builds one image holding the three apps' standalone output. |
| `apps/web/packages/contract` | TS types generated from `src/schema/screen.schema.json`. Never edit `src/` by hand. |
| `apps/web/packages/ui` | Shared shadcn/ui components and theme (system UI and mono fonts, the Faye textmark as `FayeLogo`, generated by `brand/generate.py`). `login-theme.css` is the sign in screens' palette, used by the login app and the admin's login page preview; `lib/brand-style.ts` maps a tenant's branding onto it. Components and apps name tokens, never raw colors, lengths, shadows or letter spacing (`src/tokens.test.ts`); type sizes come from each app's scale (`apps/{admin,login}/lib/type-scale.test.ts`). |
| `apps/web/apps/login` | Renders typed screens (`:3000`) at the path each screen response names (`/login`, `/challenge/...`, `/consent`, `/error`), with the flow in `?flow=` ([ADR 0029](docs/adr/0029-screen-paths.md)). `/` goes to `/login`; `/login` with no flow starts a sign in at `DEMO_SIGN_IN_URL` (the demo client, set only when the demo runs), else shows `/error`. A failed sign in shows `/error?reason=failed` instead of following the host's redirect; with `DEMO_SIGN_IN_URL` set, `/error` goes on to `/login` after 5 seconds ([ADR 0031](docs/adr/0031-failed-sign-in-stays-on-faye.md)). `/approve/{token}` is where a QR code sends a phone: it starts the phone's approve flow and goes to its first screen ([ADR 0032](docs/adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). Refuses to be framed. `FAYE_EMBED_ORIGIN` (one origin) is where it posts a host's handoff result when an embedding page opened it in a popup ([ADR 0025](docs/adr/0025-host-neutral-boundaries-for-embedding.md)). |
| `apps/web/apps/demo-client` | OIDC relying party (`:3001`) of every client bootstrap registers, reading the applications' secrets from `CLIENT_REGISTRATIONS` (the secrets volume): launch panel while signed out; once signed in, only the identity, the ID token and Sign out (local only, though a preview client's also ends its Hydra login session), plus the preview trace and the `amr` / `acr` expectation check for a preview client. |
| `apps/web/apps/admin` | Admin UI (`:3002`): opens on a centered tenant picker with no console frame; choosing a tenant enters a console with a collapsible sidebar. Login flow (Blocks, YAML, Paths, Settings and Preview tabs beside the live graph, with the block inspector floating over the graph; Settings also holds the tenant's time limit and idle timeout per entry kind, [ADR 0035](docs/adr/0035-flow-settings-per-entry-kind.md)), Hooks, Versions, read only Applications (from the authorization server, each with Try sign in through the demo client) and Users (the tenant's user pool), General settings and Sign-in › Customization (branding, show password, keep me signed in, sign up button; [ADR 0033](docs/adr/0033-login-page-customization-and-keep-me-signed-in.md)) ([ADR 0013](docs/adr/0013-admin-console-shell-and-flow-workspace.md)). The console's routes are the `app/(console)` root layout; the docs site is a second root layout, `app/(docs)` (Fumadocs, its own CSS and type scale), rendered from `FAYE_DOCS_DIR` (dev default the repo's `docs/`) by `lib/docs.ts` and `lib/docs-site.tsx`, with search at `/api/search`. Has no sign in: it proxies the admin API, over the server's admin socket with the server token, so it must only be reachable locally (compose binds it to 127.0.0.1). `lib/` holds the editor's document model and its tests. Inter for UI text and Paper Mono for code (next/font/local from npm packages; Paper Mono as a git dependency pinned to its v1.000 commit). |
| `Dockerfile` | The one image: every process under s6-overlay (hydra, faye, login, admin, and with `FAYE_DEMO=true` the demo's four; `FAYE_SERVICES` selects a subset), one volume at `/var/lib/faye` ([ADR 0023](docs/adr/0023-one-container-many-processes.md)). `deploy/image/` holds the s6 service tree (`s6-rc.d/`), the service scripts (`scripts/`), `stage2-hook` (service selection) and `healthcheck`. |
| `deploy/` | `compose.yaml` (one service, `faye`, on the root image), `image/` (see `Dockerfile`), `directory/seed.mjs` (writes each tenant's user pool into the SQLite database once Faye has migrated the schema: hashes in the database, passwords only under `/var/lib/faye/demo`), `bootstrap.mjs` (registers a preview client per tenant, and ten applications, five per tenant, whose ids and secrets Hydra generates), `ip-reputation.mjs` (stub egress target for the risk hook), `dev.sh` (the stack without Docker), `fuzz.sh` (coverage guided fuzzing of every harness, ADR 0007), `smoke.sh` (scripted sign ins, admin publishes, hooks, the Admin UI and a draft preview, a sign in through each of the ten applications, the single database, a remembered sign in, a sign in with a code from the demo outbox, a QR code sign in approved by a second browser: the prototype's acceptance checks and ADR 0033's and 0032's), `hydra-local.sh` (Hydra without Docker). |

## Commands

The product libraries (needs JDK 21+; `java` on PATH may be older):

```sh
cd src
./gradlew build                 # compile with -Xlint:all -Werror, Error Prone defaults + NullAway, spotlessCheck, checkstyle, unit + conformance tests, jacoco coverage floors
./gradlew spotlessApply         # format Java (palantir-java-format) and *.gradle.kts (ktlint)
./gradlew publishToMavenLocal   # jars + sources + javadoc (+ test fixtures for faye-engine), reproducible
FAYE_REGENERATE=1 ./gradlew :faye-engine:test   # rewrite src/conformance/generated after changing a conformance flow or the checker
../deploy/fuzz.sh 2m [Harness]  # coverage guided fuzzing, one harness per JVM (ADR 0007); `build` only replays stored inputs
```

The prototype (the same JDK):

```sh
cd apps/server
./gradlew build                 # the modules below, resolving faye:* from the src composite
./gradlew :faye-app:bootRun     # :8080
```

Each test context gets a fresh SQLite database under `apps/server/faye-app/build/`; `SqliteRestartTest` boots twice on one file.

Web:

```sh
cd apps/web
pnpm install
pnpm generate                   # regenerate contract types after editing src/schema/screen.schema.json
pnpm typecheck && pnpm lint && pnpm test && pnpm build   # test: node --test over apps/admin/lib, apps/login/lib and packages/ui/src
pnpm e2e:install                # once: the Chromium this Playwright version pins, into git ignored apps/login/.playwright/
pnpm e2e                        # after build: the Login UI in Chromium against a stub screen API (apps/login/e2e/)
```

Product vs prototype: `src/` is the product — the two shippable libraries a host embeds, with the stricter gates (all Error Prone defaults plus NullAway, published sources/javadoc, reproducible jars, JaCoCo coverage floors). `apps/` is the prototype that exercises them; a host integrates by implementing `faye.engine.spi`.

End to end:

```sh
docker compose -f deploy/compose.yaml up --build   # or: docker build -t faye . && docker run -d --name faye -v faye-data:/var/lib/faye -e FAYE_DEMO=true -p 4444:4444 -p 8080:8080 -p 3000:3000 -p 3001:3001 -p 127.0.0.1:3002:3002 faye
deploy/smoke.sh                 # seeded accounts, read via `docker exec faye cat /var/lib/faye/demo/`; run against a fresh stack
```

CI (`.github/workflows/ci.yml`) runs the product build (`src`: `build publishToMavenLocal`, with the JaCoCo report as an artifact), the prototype server build (`apps/server`), the web checks (with the Login UI's browser tests) plus a diff check that generated contract types match the schema, and the compose stack with `smoke.sh`. All four must pass. `.github/workflows/fuzz.yml` fuzzes every harness for 2 minutes on pull requests that touch the server and 30 minutes nightly; a finding is a security bug until triaged ([ADR 0007](docs/adr/0007-fuzz-and-adversarial-testing.md)).

## Rules

1. Changing the IR or screen protocol means changing `src/schema/` first, then the Java types, the generated TS (`pnpm generate`), [ir-spec.md](docs/ir-spec.md) and conformance vectors together. A change to what the IR means bumps the IR format ([ADR 0024](docs/adr/0024-core-ir-as-a-narrow-waist.md)).
2. Engine behavior changes need a conformance vector, not just a Java unit test. Every scripted handler outcome in a vector must be consumed. Never edit `src/conformance/generated/` by hand; regenerate it. A new parser, handler, screen type, IR form, host or store also needs a fuzz harness, or an existing one extended (ADR 0007); a fuzz finding lands as a readable regression (a vector, a named test or a text seed), never as a raw crash file.
3. The engine never imports Hydra. Authorization server specifics stay behind `FlowHost`.
4. Hooks (later milestones) may only `set`, `fail`, or `pick` among declared options; they can never complete authentication. `amr`/`acr` are computed by the engine from credentials that actually verified, never configured.
5. Safety checks live only in Java; the web apps call the server rather than duplicating rules.
6. Screens are typed templates. Add a screen type to the protocol rather than rendering free form UI.
7. No binaries in the repo. Third party tools are downloaded on demand into git ignored dirs (e.g. `deploy/.bin/`) with versions pinned by checksum or image digest.
8. Java compiles with `-Werror`; web lint runs with `--max-warnings 0`. Fix warnings, don't suppress them. Java layout is whatever `./gradlew spotlessApply` produces, and Checkstyle (`src/config/checkstyle/checkstyle.xml`) fails the build on any violation ([ADR 0003](docs/adr/0003-enforce-java-format-and-lint-in-the-build.md)).
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
5. Add each record to the table in `docs/adr/README.md`. If it changes a founding decision (D1 to D13 in [ADR 0036](docs/adr/0036-retire-the-roadmap.md)), say so in the new record.

## Subagents

Spawn subagents freely when they help; you don't need to ask. Run independent ones in parallel. Good uses:

1. Explore: sweep the codebase for usages, call sites, or conventions before changing them.
2. Search: read upstream docs, specs (OAuth2, OIDC, Hydra, MADR), release notes and issue trackers.
3. Best practice: check how mature projects solve the same problem, and the current recommended approach for a library or standard, before settling on one.
4. Benchmark: measure competing options with a small throwaway harness in a scratch or git ignored dir, and report numbers with the setup used.
5. Review: get an independent read of a diff or an ADR draft. Give the reviewer the code, not your conclusion.

Give each subagent a narrow goal, the files and docs to read, and what is already ruled out. Treat what it returns as evidence to check, not as fact; cite it in the ADR where it backs a decision. Subagents follow this file too: they never commit, and their scratch code does not land in the repo.
