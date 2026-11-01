---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Flow settings: a time limit and an idle timeout per entry kind, set per tenant

## Context and Problem Statement

How long a person has to finish a sign in is set once for the whole deployment: `faye.flow-ttl` (30 minutes) is when the SQLite store drops a suspended flow, counted from its start. A tenant cannot shorten it (a bank wants a sign in finished in 10 minutes), and nothing ends a flow that sat on one screen for 25 minutes while the person was away from the computer. The only stores that enforce any limit are the app's own: an embedder on `InMemoryFlowStore` gets flows that never expire.

The owner wants flow settings, starting with the time limit ("the person must finish within X minutes"), set in the Admin UI. They must not be specific to the sign in. A sign up flow comes next ([ADR 0024](0024-core-ir-as-a-narrow-waist.md) reserves the entry kind `register` for it), with its own YAML, so where the settings live and what they mean cannot depend on the login DSL (`login:`, `steps:`, blocks).

What should a flow setting be attached to, which settings should exist now, and who enforces them?

## Decision Drivers

* Not login specific: the same settings must apply to `authenticate`, `consent` and later `register` entries, whatever YAML each is written in.
* Charter principle 3 (host neutral) and ADR 0025: the engine enforces the rule, so every embedder and every store gets it, not only the SQLite one.
* Charter guarantee 3 and AGENTS.md rule 4: a setting can only end a flow sooner. It can never let a flow succeed, so no hook or flow may lengthen it.
* ADR 0033's rule for tenant choices: a setting an admin saves applies without republishing the flow.
* The host bounds the flow. Hydra's login and consent challenges expire on their own (`ttl.login_consent_request`, which `faye.flow-ttl` is set to match), so a longer Faye limit would only fail later, at the host.
* AGENTS.md rule 2: engine behavior needs conformance vectors.

### What other products do

A survey of the documented behavior (2026-11-01; each link is the primary source, and claims not verified there are marked):

| Product | Where the flow's time limit is set | Total | Idle or per step | On expiry |
|---|---|---|---|---|
| Ory Kratos ([config schema](https://github.com/ory/kratos/blob/master/embedx/config.schema.json)) | deployment config, per flow kind: `selfservice.flows.{login,registration,recovery,verification,settings}.lifespan` | 1h default for each kind | none | 410 `self_service_flow_expired`, and the browser gets a new flow |
| Ory Hydra ([config](https://www.ory.com/docs/hydra/reference/configuration)) | deployment config: `ttl.login_consent_request` | caps login plus consent (default unverified: 30m or 1h) | none | the challenge is unknown |
| Keycloak ([`Constants`](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/models/Constants.html)) | realm settings, not per flow | Login timeout, 30 min | Login action timeout, 5 min on any one page | the login restarts (wording unverified) |
| Auth0 ([1 hour transactions](https://auth0.com/docs/troubleshoot/product-lifecycle/past-migrations/migrate-to-1-hour-expiration)) | tenant wide, can only be shortened | 1h | MFA challenge screen, fixed (10 min; unverified) | the application's callback gets an error |
| PingOne DaVinci ([flow settings](https://docs.pingidentity.com/davinci/flows/davinci_editing_flow_settings.html)) | per flow, saved with the flow | Flow Timeout, 300 s default, up to 2 days | none | a custom timeout screen |
| PingAM trees ([authTree](https://docs.pingidentity.com/pingam/8.1/entity-reference/sec-amster-entity-authtree.md)) | per tree | `treeTimeout`, from the authentication session's creation | none | the tree restarts |
| Azure AD B2C ([RelyingParty](https://learn.microsoft.com/en-us/azure/active-directory-b2c/relyingparty)) | per policy, in its XML | none in flight; its settings are for the session that results | none | n/a |

Most products ship only a total limit, and Keycloak ships both: 30 minutes in total and 5 on any one page. The limit is keyed by what the flow does (Kratos per flow kind) and set in config, or saved with the flow definition (DaVinci, PingAM, B2C). No product resumes an expired flow: the person starts again. No product sets attempt limits on the flow; they are credential or tenant policy. Log levels per flow exist (DaVinci, B2C's developer mode) with warnings that they log credentials and personal data.

## Considered Options

1. Where flow settings live
   * A. Tenant settings, keyed by entry kind (`authenticate`, `consent`, later `register`).
   * B. In the flow document, per entry: a `settings:` section in the YAML, lowered into the IR's `entry` node.
   * C. Deployment config, keyed by entry kind (`faye.flows.<kind>.lifetime`), as Kratos does.
2. Which settings now
   * A. A total time limit only.
   * B. A total time limit and an idle timeout.
   * C. Those two, plus a log level, attempt limits and an on/off switch per kind.
3. Who enforces them
   * A. Each `FlowStore`, as `SqliteFlowStore` does now.
   * B. The engine, with a clock it is given; stores keep their own retention as an upper bound.

## Decision Outcome

1A, 2B, 3B.

* **Model.** `faye.engine.FlowSettings(lifetime, idleTimeout)`, both optional `Duration`s. `TenantPolicy.flows` maps an `Entry.Kind` to its settings. A kind without settings, and a tenant without any, gets `FlowSettings.NONE`: the engine sets no limit, and the store's retention is the only one, exactly as before.
* **Meaning.** `lifetime` is how long after its start an entry can still be answered. `idleTimeout` is how long after the last answer the engine took. An answer the engine refuses (`invalid_submission`, `stale_nonce`) does not count; an answer that brings back the same screen with an error does. Whichever limit passes first ends the flow.
* **Applied at start.** The engine reads the settings when an entry starts and records the deadline on the flow state (`FlowState.expiry`: the deadline, the idle timeout, and when the last answer came). A flow in progress keeps the limits it started with, as it keeps the rest of its tenant's policy (`TenantPolicies`). States saved before this change have no `expiry` and expire only with the store, so the state format stays 1.
* **Enforced by the engine.** `Engine.Builder.clock(Clock)` (default `Clock.systemUTC()`). `view` and `submit` refuse a flow past its limit with the new code `flow_expired` (HTTP 410), after the binder check, so only the browser that started the flow learns it existed. The state is not changed and the host is not told: its challenge expires on its own, and nothing about a GET changes anything.
* **The deployment's maximum.** `faye.flow-ttl` stays as the store's retention and becomes the maximum of both settings. The admin API refuses a `lifetime` over it, and either setting under one minute or an `idleTimeout` over the `lifetime`. Unset, a tenant gets exactly today's behavior.
* **Screen protocol.** `ErrorResponse.error` gains `flow_expired`. The Login UI says "This sign in timed out. Go back to the app and start again."
* **Admin API.** `TenantSettings.flows`: `{ "<kind>": { "lifetimeSeconds": 600, "idleTimeoutSeconds": 300 } }`, saved with the tenant's other settings (absent in older rows: none). The settings view adds `flowLimits`: the entry kinds the engine runs and the deployment's maximum, so a client renders one row per kind without knowing them.
* **Admin UI.** The Login flow's Settings tab, "Time limits" (beside the flow's name and description, but saved as tenant settings with its own Save, not with the draft): one row group per entry kind ("Sign in" for `authenticate`, "Consent" for `consent`, and a kind it has no label for under its own name), each with "Time limit" and "Idle timeout" in minutes, the deployment's maximum shown, and blank meaning the default.
* **Not in the YAML or the IR.** No DSL and no IR format changes. A sign up flow's entries are kind `register`; once that kind exists, the API, the engine and the Admin UI give it settings without any further change here.

### Why not the others

* 1B ties a limit to a version, so shortening it means a publish and a rollback restores the old one; every DSL would need to lower it, and the IR would change format for a value no flow logic reads. DaVinci and B2C do this, but their flow definition is also their deployment unit; Faye's is not.
* 1C needs a restart and an operator for every change, and is the same for every tenant. The deployment's maximum stays in config for that reason: it is what the host allows.
* 2C: attempt limits are already in the flow (`retry`, a block's `attempts`, ADR 0030). A log level is a way to log what people type; preview traces (ADR 0011) already show what a flow did, without its inputs. An on/off switch only means something for `register`, so the sign up ADR adds it.
* 3A leaves embedders on other stores without a limit, and a store cannot tell `flow_expired` from `flow_not_found`.

### Consequences

* Good, because one rule holds for every entry kind and every store, and sign up gets it by adding a kind.
* Good, because a tenant can shorten the limit without a publish or a restart, and an absent person's flow ends after the idle timeout.
* Good, because the person is told the sign in timed out, not that it never existed.
* Bad, because the engine now reads a clock: tests and vectors must control it (`ConformanceTest` gains `wait`).
* Bad, because two limits exist: the tenant's, which the engine enforces, and the store's, which deletes the row. The admin API keeps the first within the second.
* Bad, because an expired flow's host challenge is left to expire on its own, not rejected: the application learns nothing until the person starts again. Rejecting on a GET would make a read change state.
* Neutral: `FlowState` gains a component; older states read without it.

### Confirmation

* `cd src && ./gradlew build`: the vectors `flow-lifetime-expires` (refused at exactly the lifetime, the host told nothing), `flow-idle-timeout` (a retried answer counts as activity), `flow-idle-timeout-expires` (a refused answer does not), `flow-settings-unset`, `flow-settings-per-kind` and `flow-consent-lifetime-expires` (vector format: `tenant.flows` and the `wait` event, `src/conformance/README.md`); `StoredStateFuzzTest` fuzzes states with and without `expiry`, and `expiredStatesAreExpiredAndBadExpiriesNotFound` pins a past deadline, a passed idle timeout, and an unreadable `expiry`.
* `cd apps/server && ./gradlew build`: `FlowSettingsTest` saves settings over the admin API, refuses a lifetime over `faye.flow-ttl`, under one minute, an idle timeout over the lifetime, and an unknown kind, and answers a flow past its limit with 410 `flow_expired`.
* `cd apps/web && pnpm typecheck && pnpm lint && pnpm test && pnpm build`, and the generated contract types match the schema; `apps/admin/lib/flow-settings.test.ts` pins the Time limits section's conversions and the checks it shares with the admin API.

Open gaps:

* The restructure this was built on (ADR 0034) was mid way: `cd src && ./gradlew build` passes except Checkstyle's package check on the `faye.engine.spi` files that restructure had moved but not yet renamed. This change adds no Checkstyle finding.

* `deploy/smoke.sh` step 15 saves limits through the Admin UI, sees one over `faye.flow-ttl` refused, and signs in under them; it does not wait out a limit (the shortest is a minute). A flow left past a one minute idle timeout was checked once by hand on the compose image (2026-11-01): Faye answered 410 `flow_expired` and the Login UI's `/error` said the sign in timed out. The Admin UI's Time limits section was checked in a browser against a local server (a limit over the maximum is flagged and blocks Save; saved limits survive a reload; clearing them saves none).
* Hydra's own `ttl.login_consent_request` is not read: if an operator sets it below `faye.flow-ttl`, a tenant limit between the two expires at Hydra first. Unverified whether to read it from Hydra; documenting that `faye.flow-ttl` must not exceed it is the current answer.
