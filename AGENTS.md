# AGENTS.md

Faye is login flow middleware in front of an authorization server; Ory Hydra is the first host. Hydra speaks OAuth2/OIDC; Faye owns every screen and every authentication decision. Flows are declared as YAML, compiled to a JSON S expression IR, and run by a suspendable engine whose continuations persist in Redis. The plan, milestones (M0 to M7) and decision log are in [docs/roadmap/](docs/roadmap/README.md); read the relevant doc before changing a boundary.

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
| `server/faye-engine` | Interpreter, continuations, `FlowHost`, `FlowStore`, handler registry, `Previews` (what preview clients run, and their trace events). Runs the conformance suite. |
| `server/faye-hooks` | `HookRuntime` (QuickJS on WebAssembly via quickjs4j: deadline, 16 MiB memory cap, no host access), `SandboxedHooks` (the engine's `HookRunner`; egress grants), `HttpEgress` (named targets). |
| `server/faye-identity` | Identity handlers (`identity.resolve`, `password.verify`, `totp.*`, `webauthn.*`, `consent.record`) over a `UserStore`; in memory store for the prototype. Preview only accounts exist only for preview clients. |
| `server/faye-host-hydra` | `FlowHost` for Hydra's admin API. |
| `server/faye-host-mock` | In memory `FlowHost`; used when `FAYE_HYDRA_ADMIN_URL` is unset, so any `login_challenge` works. |
| `server/faye-app` | Spring Boot wiring, screen API, admin API (validate, publish, roll back; on when `FAYE_ADMIN_TOKEN` is set), preview API (draft previews, scenarios, seeded accounts, trace feed over SSE; on when `FAYE_PREVIEW_ENABLED=true`), Redis flow, version, hook and trace stores. Tenant settings in `application.yaml` (`faye.tenants`), their flows in `src/main/resources/flows/*.yaml`. |
| `web/` | pnpm workspace, Next.js App Router, TypeScript. |
| `web/packages/contract` | TS types generated from `schema/screen.schema.json`. Never edit `src/` by hand. |
| `web/packages/ui` | Shared shadcn/ui components and Geist theme. |
| `web/apps/login` | Renders typed screens (`:3000`). |
| `web/apps/demo-client` | OIDC relying party (`:3001`): launch panel, and a result view with the preview trace and the `amr` / `acr` expectation check. |
| `web/apps/admin` | Admin UI (`:3002`): block editor, YAML, graph, paths, hooks, versions, settings, and Preview (presets, launch, live trace on the tree). Signs operators in through Hydra (tenant `admin`) and proxies the admin API with the server token. `lib/` holds the editor's document model and its tests. |
| `deploy/` | `compose.yaml`, `bootstrap.sh` (registers the demo and preview clients), `smoke.sh` (scripted sign ins, admin publishes, hooks, the Admin UI and a draft preview, the M2 to M6 checks), `hydra-local.sh` (Hydra without Docker), `ip-reputation/` (stub egress target for the risk hook). |

## Commands

Server (needs JDK 21+; `java` on PATH may be older):

```sh
cd server
./gradlew build                 # compile with -Xlint:all -Werror, spotlessCheck, checkstyle, unit + conformance tests
./gradlew spotlessApply         # format Java (palantir-java-format) and *.gradle.kts (ktlint)
FAYE_REGENERATE=1 ./gradlew :faye-check:test   # rewrite conformance/generated after changing a conformance flow or the checker
./gradlew :faye-app:bootRun     # :8080
```

The Redis restart test skips without a local Redis; CI sets `FAYE_REQUIRE_REDIS=1` so it must pass there.

Web:

```sh
cd web
pnpm install
pnpm generate                   # regenerate contract types after editing schema/screen.schema.json
pnpm typecheck && pnpm lint && pnpm test && pnpm build   # test: node --test over apps/admin/lib
```

End to end:

```sh
docker compose -f deploy/compose.yaml up --build
deploy/smoke.sh                 # alice@example.com / correct horse battery staple; run against a fresh stack
```

CI (`.github/workflows/ci.yml`) runs the server build, the web checks plus a diff check that generated contract types match the schema, and the compose stack with `smoke.sh`. All three must pass.

## Rules

1. Changing the IR or screen protocol means changing `schema/` first, then the Java types, the generated TS (`pnpm generate`), and conformance vectors together.
2. Engine behavior changes need a conformance vector, not just a Java unit test. Every scripted handler outcome in a vector must be consumed. Never edit `conformance/generated/` by hand; regenerate it.
3. The engine never imports Hydra. Authorization server specifics stay behind `FlowHost`.
4. Hooks (later milestones) may only `set`, `fail`, or `pick` among declared options; they can never complete authentication. `amr`/`acr` are computed by the engine from credentials that actually verified, never configured.
5. Safety checks live only in Java; the web apps call the server rather than duplicating rules.
6. Screens are typed templates. Add a screen type to the protocol rather than rendering free form UI.
7. No binaries in the repo. Third party tools are downloaded on demand into git ignored dirs (e.g. `deploy/.bin/`) with versions pinned by checksum or image digest.
8. Java compiles with `-Werror`; web lint runs with `--max-warnings 0`. Fix warnings, don't suppress them. Java layout is whatever `./gradlew spotlessApply` produces, and Checkstyle (`server/config/checkstyle/checkstyle.xml`) fails the build on any violation ([ADR 0003](docs/adr/0003-enforce-java-format-and-lint-in-the-build.md)).
9. Every Java package has a `package-info.java` marked `@NullMarked`. Types are non-null unless annotated `@Nullable` (JSpecify); NullAway enforces this at compile time ([ADR 0002](docs/adr/0002-enforce-null-safety-with-jspecify-and-nullaway.md)).

## Decisions

Record significant decisions as ADRs in [docs/adr/](docs/adr/README.md), MADR 4 format, one file per decision (`NNNN-title.md`, next free number, never renumbered).

1. Write an ADR before changing a boundary, changing the charter, adding or swapping a dependency, changing the IR or screen protocol, or choosing between approaches with real tradeoffs. Skip it for changes that follow an existing decision.
2. List the options actually considered, with pros and cons, and a `Confirmation` section naming the command or test that proves the decision holds.
3. Mark unverified claims as such, with how to verify them. Back claims with evidence (a benchmark, a test, a link to the source) where you can.
4. New records start as `proposed`. Only a human marks one `accepted`. To change a decision, add a record that supersedes the old one and set the old one's status to `superseded by NNNN`.
5. Add each record to the table in `docs/adr/README.md`. If it changes a D1 to D13 entry, update [decisions-and-risks.md](docs/roadmap/decisions-and-risks.md) to link to it.

## Subagents

Spawn subagents freely when they help; you don't need to ask. Run independent ones in parallel. Good uses:

1. Explore: sweep the codebase for usages, call sites, or conventions before changing them.
2. Search: read upstream docs, specs (OAuth2, OIDC, Hydra, MADR), release notes and issue trackers.
3. Best practice: check how mature projects solve the same problem, and the current recommended approach for a library or standard, before settling on one.
4. Benchmark: measure competing options with a small throwaway harness in a scratch or git ignored dir, and report numbers with the setup used.
5. Review: get an independent read of a diff or an ADR draft. Give the reviewer the code, not your conclusion.

Give each subagent a narrow goal, the files and docs to read, and what is already ruled out. Treat what it returns as evidence to check, not as fact; cite it in the ADR where it backs a decision. Subagents follow this file too: they never commit, and their scratch code does not land in the repo.
