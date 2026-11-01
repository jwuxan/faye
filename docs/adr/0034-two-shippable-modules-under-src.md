---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Two shippable modules under `src/`, the prototype under `apps/`

## Context and Problem Statement

On `main` at 7d275ab the server is one Gradle build, `server/`, with eleven modules: `faye-ir`, `faye-dsl`, `faye-check`, `faye-engine`, `faye-screens`, `faye-hooks`, `faye-clients`, `faye-identity`, `faye-host-mock`, `faye-host-hydra` and `faye-app`. Nothing in the layout says which of them are the product and which are the local prototype around it (the Spring Boot app, the Hydra adapter, Faye's own user pool, the admin client list). [ADR 0025](0025-host-neutral-boundaries-for-embedding.md) made the engine embeddable from its SPIs alone, but an embedder still has to pick five or six modules out of eleven, and the only thing keeping the product from depending on the prototype is `BoundaryTest` reading build files.

The language independent contracts (`schema/`, `conformance/`) sit at the repo root, beside the web workspace (`web/`), the deploy scripts and the docs, so the product's own tests reach outside its build for its spec.

How should the code be laid out so that the shippable part is small, builds and verifies on its own, and cannot depend on the prototype?

## Decision Drivers

* Charter principle 7 (small and modular): each part has one job and a written contract.
* Charter principle 3 (host neutral) and [ADR 0024](0024-core-ir-as-a-narrow-waist.md) invariant 4: the engine and the checker depend only on contracts.
* The product must build, test and publish without the prototype, and its quality gates can be stricter than the prototype's.
* No behavior change, no test lost, no dependency version drift.

## Considered Options

1. Module granularity
   * A. Keep the eleven modules (status quo).
   * B. Two product modules: `faye-core` (IR, DSL, the hook sandbox) and `faye-engine` (interpreter, continuations, SPIs, checker, screen protocol v1 catalog); the rest is the prototype.
2. Build layout
   * A. One Gradle build at the repo root including `src/*` and `apps/server/*`; direction enforced by a test.
   * B. `src/` a standalone Gradle build; `apps/server` a second build that includes `src/` as a composite (`includeBuild`).
3. Contracts (`schema/`, `conformance/`)
   * A. Keep them at the repo root.
   * B. Move them into `src/`.

## Decision Outcome

1B, 2B, 3B.

| Before (`server/`, repo root) | After |
|---|---|
| `faye-ir`, `faye-dsl`, `faye-hooks` (`HookRuntime`, `QuickJsRuntime`, `Capabilities`, `Egress`, `HttpEgress`) | `src/faye-core`: packages `faye.ir`, `faye.dsl`, `faye.hooks` |
| `faye-engine`, `faye-check`, `faye-screens`, `SandboxedHooks` | `src/faye-engine`: packages `faye.engine`, `faye.check`, `faye.screens` |
| `faye-host-mock`, `faye-screens` test fixtures (`FixedIssuers`) | `src/faye-engine` test fixtures: `faye.engine.testing.MockFlowHost`, `faye.engine.testing.FixedIssuers` |
| `faye.engine.HookException` | `faye.hooks.HookException` (the sandbox throws it; the engine catches it) |
| `faye.hooks.SandboxedHooks` | `faye.engine.SandboxedHooks` (it implements the engine's `HookRunner` over its `HookStore` and `TenantPolicies`) |
| `schema/`, `conformance/` | `src/schema/`, `src/conformance/` |
| `server/build.gradle.kts` `subprojects {}` | `src/build-logic`, convention plugin `faye.java-conventions`, applied by both builds |
| `faye-identity`, `faye-clients`, `faye-host-hydra`, `faye-app`, `server/Dockerfile` | `apps/server/` |
| `web/` | `apps/web/` |

`deploy/`, `brand/`, the root `Dockerfile` and `docs/` stay where they are.

* **`src/` is the product.** `cd src && ./gradlew build` compiles, checks and tests `faye-core` and `faye-engine` with nothing from `apps/`. Its version catalog holds only what the two modules and the build logic need, with every version explicit: the product does not inherit the Spring Boot BOM, so a consumer gets no Boot constraints from it. Package and class names are unchanged except the two moves in the table.
* **`apps/server` is the prototype.** Its build includes `src/` (`includeBuild("../../src")`) and depends on `faye:faye-core` and `faye:faye-engine` by coordinates, so it uses the product exactly as an outside embedder would. It reads `src/`'s catalog as `libs` (one version for every shared library) and its own as `appLibs`, and applies the Spring Boot BOM to its own modules only. `faye-app` uses `MockFlowHost` from the engine's test fixtures for its no-Hydra mode, as before.
* **The screen protocol v1 catalog ships with the engine.** The conformance vectors run built in screens, and the protocol (`src/schema/screen.schema.json`) is part of what the product promises. It stays in its own package: `faye.engine` and `faye.check` import nothing from `faye.screens`, so the engine still knows no screen but `method_picker` (ADR 0025).
* **The hook sandbox is core, its wiring is engine.** `faye-core` holds the QuickJS runtime with its deadline, memory cap and egress; the adapter that finds a pinned hook and enforces a tenant's grants is engine code, because it needs the engine's stores and policies.
* **Stricter gates apply to `src/` only** (convention plugin `faye.library-conventions`): Error Prone's default checks on top of NullAway, Javadoc that must build without errors, sources and Javadoc jars, a Maven publication per module (with the engine's test fixtures), reproducible archives, and a JaCoCo coverage floor on `check`. `apps/server` keeps the rules it had.

Earlier records name the old paths (`server/faye-engine`, `schema/`, `web/apps/login`); they are left as written, and the table above maps them.

### Consequences

* Good, because an embedder takes two artifacts, and the product cannot depend on the prototype: `src/` cannot see `apps/` at all.
* Good, because the product carries its own spec and verification (schema, conformance vectors, fuzz harnesses) inside its build.
* Good, because the product's gates can tighten without the prototype paying for it.
* Bad, because there are two Gradle builds, two wrapper scripts and two catalogs to keep in step; the shared build logic and the shared `libs` catalog limit that to the app-only libraries.
* Bad, because `faye-core` now carries QuickJS (`quickjs4j`) even for a consumer that only compiles YAML. Splitting it out again is a module, not a boundary change.
* Bad, because a production class in the prototype (`faye-app`'s no-Hydra mode) depends on a test fixture. Acceptable for the prototype; an embedder writes its own `FlowHost`.
* Neutral: every path in the docs, CI, the Dockerfiles, the Makefile and the deploy scripts changes.

### Confirmation

* `cd src && ./gradlew build`: compile with `-Werror`, Error Prone and NullAway, Spotless, Checkstyle, Javadoc, the conformance suite, the fuzz harness replays, the coverage floor, and `BoundaryTest`, which fails if:
  * the main sources of `faye.ir`, `faye.dsl`, `faye.hooks`, `faye.engine` or `faye.check` name an authorization server, a built in screen, option or handler, a protocol parameter outside `HostParams`, a token claim or the client registry (the rules of ADR 0025);
  * `faye.screens` names an authorization server, `otpauth`, a token claim or the client registry;
  * `faye.engine` or `faye.check` imports `faye.screens`;
  * any build file under `src/` names `apps/` or a host, credential or app library.
* `cd apps/server && ./gradlew build`, and `cd apps/web && pnpm typecheck && pnpm lint && pnpm test && pnpm build`.
* The restructure lost no test: the test count over both builds equals the count on 7d275ab, and `faye-app`'s resolved runtime classpath is unchanged.
* `deploy/smoke.sh` passes on a fresh stack built from the root `Dockerfile`.

## Pros and Cons of the Options

### 1A Eleven modules

* Good, because each module has one job.
* Bad, because nothing says which modules ship, and the product's boundary is a convention.

### 2A One root build

* Good, because one wrapper, one catalog, one command.
* Bad, because the product can depend on the prototype until a test notices, and the product cannot be built or published alone.

### 3A Contracts at the root

* Good, because no paths change.
* Bad, because the product's tests read outside its build, and a consumer of `src/` has to know to look elsewhere for its spec.
