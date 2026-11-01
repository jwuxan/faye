---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# M3: static checks, durable versions and tenant policy

## Context and Problem Statement

M3 adds `faye-check` (the DSL's checks, path enumeration, `acr` reachability), immutable versions with a current pointer, pinning and rollback, tenant allowlists and MFA policy, and conformance vectors generated from path enumeration. The charter requires that Faye "checks every flow before it runs anywhere" and refuses any flow with a path to success that does not verify a credential (principle 1). Before M3, `FlowVersions` was an in memory map filled from YAML at startup with no checks, so a version published at run time would be lost on restart while flows pinned to it were still in Redis.

Exit: a flow missing a credential on one branch is rejected with a pointer to the YAML line; publishing a new version while a user is mid login does not affect that user.

## Decision Drivers

* Soundness first: a check that passes must hold on every run. False alarms are acceptable; missed unsafe paths are not.
* Errors point at the YAML a human wrote.
* A publish or a restart never breaks a flow in progress (D5, M1 exit).
* Small: no new dependency, no new boundary the plan does not already name.

## Considered Options

1. Path enumeration
   * A. Ignore predicates: every `when` both ways.
   * B. Three valued evaluation: decide a predicate when it reads only what is known at check time, else take both ways.
   * C. A constraint solver (SMT) to prune paths no input takes.
2. Where tenant MFA policy is enforced
   * A. Checker only.
   * B. Checker at publish, and the engine again at `succeed` and on Hydra `skip`.
3. Version store
   * A. In memory, rebuilt from YAML at startup (status quo).
   * B. Redis, next to flow state.
   * C. Postgres, the planned config store.
4. Configured YAML at startup
   * A. Always publish it.
   * B. Publish only when the tenant has no current version.
   * C. Publish it unless the tenant published that exact version before.
5. How a version is published at run time
   * A. Java API only (tests).
   * B. A minimal admin API behind a bearer token, ahead of the M5 Admin API.
6. Generated vectors
   * A. A second interpreter in the generator emits the expected events from the checker's model.
   * B. Find inputs per path, drive the Java engine along the path, assert it ends as the checker predicted, and record the run.

## Decision Outcome

1B, 2B, 3B, 4C, 5B, 6B.

**Checker** (`faye-check`, `Checker.check(flow, handlers, policy)`):

* Known at check time: `tenant.*` (the tenant's settings), `vars.*` written by `set`, `auth.amr`/`auth.acr` from credentials verified earlier on the path, and an empty `user.*` in a login until a handler succeeds. Unknown: `request`, `client`, `hydra`, `auth.subject`, `user.*` after any handler succeeds, and `user`/`auth` in consent. Kleene logic over `and`/`or`/`not`.
* A `call` either succeeds, proving any nonempty subset of the handler's declared `amr` (the engine contract in [ADR 0006](0006-assurance-amr-acr.md)), or fails with a retryable error. In a `retry`, any attempt but the last may fail and the next one runs from the state it left, because the engine keeps `vars`, `user` and `auth` across attempts (a first model that only took "succeeds first time" or "fails every time" missed a 1fa login through a condition that turns true on attempt 2; found in review, covered by `CheckerTest.retry*`). A `choose` takes each option that may be enabled, plus `no_method` when every option has a condition that may be false.
* Errors: unknown handler or screen; handler or screen outside the tenant's allowlist; duplicate step id anywhere in the flow; on a login success path, a credential handler (nonempty `amr`) before a successful `identity.resolve`, no successful credential that binds the subject, or, if the tenant requires MFA, an `acr` below `faye:2fa`. A path error points at the last place the path went one way rather than another (the option or `if`), else at the section.
* Warnings: `alternative` with one option, `retry` without a screen, an option never offered or an `if` that never holds on any path that reaches it, a login that never succeeds, and no path reaching `faye:2fa` (the `acr` reachability check: clients asking for it would be refused).
* More than 10,000 paths in a section, or a path longer than 10,000 events, is an error rather than a partial check.

**Tenant policy**: `faye.tenants.<id>` holds `flow`, `mfa-required`, and optional `handlers`/`screens` allowlists (unset means all). Flows read `tenant.mfa_required`; the value is captured in the flow state at start, like the version. The engine refuses a login that reaches `succeed` below `faye:2fa` for such a tenant (`mfa_required`), and does not accept a remembered session (which counts as `faye:1fa`) for it; it runs the flow as a step up instead.

**Versions**: `FlowVersions` becomes an interface with an in memory and a Redis implementation. `version:{hash}` holds canonical IR and never expires; `tenant:{id}:history` lists hashes in first publish order; `tenant:{id}:current` is the pointer. `Publisher` is the only path to the pointer: publish runs the checker; rollback requires a hash from the tenant's history and runs the checker again under the current policy. At startup, each configured flow is checked and published unless already in the tenant's history, and every tenant's current version is checked again; a failure stops the server.

**Admin API** (on only when `FAYE_ADMIN_TOKEN` is set; configured tenants only): `POST /admin/tenants/{t}/validate` and `/publish` take YAML and return errors with `source`, `line` and `column`, warnings, the path summary and the paths; `GET /admin/tenants/{t}/versions`; `POST /admin/tenants/{t}/current {versionHash}` rolls back. M5 replaces the token with the planned admin sign in and adds drafts.

**Generated vectors**: `GeneratedVectorsTest` finds values for `hydra.*`, `client.*` and the `user.*` fields each succeeding call merges in, by backtracking over literals taken from the predicates, then drives the engine with handlers that answer as the path says. The result must equal the checker's prediction (including `acr_not_satisfied` when the inputs carry `acr_values`). Output goes to `conformance/generated/<flow>/<path index>.yaml`; the build fails if it is stale. The vector format gains `tenant:` and a `publish` event.

### Consequences

* Good, because the exit criteria hold and are tested (below), and because the checker and the engine are cross checked on every enumerated path (196 generated vectors today).
* Good, because the engine's runtime checks (`unauthenticated`, `mfa_required`, `acr_not_satisfied`) back the static ones, so a checker bug cannot by itself produce an unsafe sign in.
* Bad, because the checker over-approximates. A flow whose second factor depends on `user.*` cannot pass under an MFA tenant; it must branch on `tenant.mfa_required` (as `conformance/flows/mfa-policy.json` does). Some failure paths no input can take get no vector (two in `passkey-totp`).
* Bad, because stored versions keep canonical IR, which has no source map. Errors about a version read back from Redis (a rollback after a restart) have no YAML line. Storing the YAML source alongside the IR would fix it.
* Bad, because versions are never deleted. Retention bounded by flow state TTL needs a reference count or a sweep; not needed at prototype scale.
* Bad, because a configured YAML that matches an older version in the tenant's history does not become current on restart; roll back through the admin API instead.
* Bad, because the admin API trusts one static token, and compose ships a local default. Fine for local and CI; not for anything shared.
* Bad, because some guarantees are static only. Nothing at run time checks that `identity.resolve` ran before a credential handler (the shipped credential handlers fail closed without it), and allowlists are not re-applied to an already published version when they change, only on rollback and restart. `user.*` merges across retry attempts, so a retry around `identify` can carry one account's fields into another's (M2 engine behavior; the shipped flows keep `identify` outside any retry). Each is a follow up that changes engine behavior and needs a conformance vector.
* Neutral: the screen allowlist covers `screen` nodes, not the `method_picker` a `choose` shows on its own; the "hooks pinned" check waits for hooks (M4).
* Unverified: checker cost on large flows. The shipped flows check in well under a second within the test suite; the 10,000 path cap is a guess, not a measurement. Verify with a generated flow of N sequential `alternative` steps before raising it.

### Confirmation

* `cd server && FAYE_REQUIRE_REDIS=1 ./gradlew build`: `CheckerTest` (each check, exit pointer `tenant.yaml:14:15`), `GeneratedVectorsTest` (generated vectors current and agreeing with the engine), `ConformanceTest` (hand written vectors `tenant-mfa-*`, `publish-mid-flow`, and every generated vector), `MilestoneThreeTest` (publish mid login, rollback, refused publish with line, MFA tenant, startup refusal), `RedisRestartTest.publishedVersionSurvivesARestart`.
* `deploy/smoke.sh` steps 6 and 7 against the compose stack (CI `e2e` job).
