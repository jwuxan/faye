---
status: proposed
date: 2026-11-01
decision-makers: Josh Wu
---

# Retire the roadmap: the founding decisions it held and the gaps it leaves open

## Context and Problem Statement

`docs/roadmap/` was the plan for building the prototype: a goal statement, eleven design documents, milestones M0 to M7, and a decision log D1 to D13 with open questions and risks. `docs/refine.md` was the work plan for the Core IR refinement. Both are now history.

* The refinement plan is done: [ADR 0024](0024-core-ir-as-a-narrow-waist.md) records every one of its decisions, and none of its gaps is still open.
* Milestones M0 to M6 are closed. Their exit criteria pass in `Milestone{Two..Six}Test`, `SqliteRestartTest`, `ConformanceTest` and `deploy/smoke.sh` steps 1 to 11, and ADRs 0004 to 0011 record them.
* The design documents describe what was built, and later ADRs rewrote much of it. The test suites and the ADRs now carry those facts, and the design documents go stale on every change.

Three things in the roadmap were not plans:

* the decision log, which ADRs cite as D2, D5, D7, D10, D12 and D13;
* `ir-spec.md`, the normative semantics that ADR 0024 decided to have. `IrAgreementTest` reads it, and `AGENTS.md` rule 1 names it;
* the hook result contract in `hooks.md`, whose limits `HookContractTest` checks against `HookOutput`.

A few planned items were never built. What should happen to the roadmap so that no decision, normative fact or open gap is lost?

## Decision Drivers

* ADRs are the permanent record; planning documents are disposable.
* One source per fact (ADR 0024): the semantics must stay in exactly one document, and tests that pin documents to code must keep passing.
* Nothing planned and unbuilt may disappear silently.
* The charter changes only by a human, through an ADR.

## Considered Options

1. Keep `docs/roadmap/` and mark it historical.
2. Delete `docs/roadmap/` and `docs/refine.md` entirely.
3. Delete them, but first move `ir-spec.md` to `docs/ir-spec.md`, put the hook result limits into its `hook` section, and keep the decision log and the open gaps in this record.

## Decision Outcome

Chosen option: 3.

* `docs/roadmap/ir-spec.md` becomes `docs/ir-spec.md` with the same content and sections. Its `hook` section quotes the limits on a hook's result, and `IrAgreementTest` and `HookContractTest` read that file.
* Every other file under `docs/roadmap/`, and `docs/refine.md`, is deleted, and every link to them is removed.
* The founding decisions below keep their numbers, so existing citations of D1 to D13 resolve here.
* The open gaps below are the remaining work the roadmap had planned.
* The charter's sentence `The [roadmap](docs/roadmap/README.md) says how and when` needs a human edit. The proposed wording is: `[ADRs](docs/adr/README.md) record individual decisions and the work still open`. Until a human makes it, that link is dead.

### Consequences

* Good, because the semantics, the founding decisions and the unbuilt work each have one place.
* Good, because no document claims a plan that the code no longer follows.
* Bad, because the narrative overview (architecture diagram, request lifecycle, design system) is gone. `AGENTS.md`'s layout table, the code and the ADRs are what a reader has instead.
* Bad, because the charter keeps a dead link until a human edits it.

### Confirmation

* `rg -n 'docs/roadmap|roadmap/' --glob '!**/node_modules/**' .` finds only `CHARTER.md` until a human edits it, and this record.
* `cd src && ./gradlew build` passes, with `IrAgreementTest` and `HookContractTest` reading `docs/ir-spec.md`.

## Founding decisions

The decision log of the retired roadmap, as it stood. A later ADR that changes one of these says so.

| # | Decision | Alternatives considered | Reason |
|---|---|---|---|
| D1 | Delegate the protocol to Ory Hydra | Build an AS; Keycloak as AS | Hydra is headless by design and delegates all UI, which is exactly the seam Faye occupies |
| D2 | YAML surface, S expression IR as JSON | Code first flows; BPMN; string expressions | Data is diffable, checkable and editable by a UI; JSON arrays need no Lisp runtime. The IR is versioned, typed by entry and host neutral ([ADR 0024](0024-core-ir-as-a-narrow-waist.md)) |
| D3 | Closed language, always terminates | Embedded general purpose language | Enables path enumeration, static safety checks and generated tests |
| D4 | Suspendable small step interpreter with explicit continuations | Recursive interpreter with server side sessions | Continuations persist as data across requests and restarts |
| D5 | Pin flow version per flow instance | Always run latest | Publishing must never break users mid login |
| D6 | Typed screens, not free form UI | Server driven arbitrary forms | One renderer for all tenants; accessibility and security reviewed once |
| D7 | Identity first login by default; the identifier block may ask for the password on its own screen ([ADR 0030](0030-blocks-with-fixed-screens-and-handlers.md)) | Combined identifier and password form only | Per user adaptation, passkey friendliness, future home realm discovery; the combined form is an explicit setting, as enumeration resistant as identity first |
| D8 | Hooks with closed output | Hooks with full control | Keeps static checks sound without analyzing JavaScript |
| D9 | Java 21 server, TypeScript web | All TypeScript | Team production language; sealed types give exhaustive checking |
| D10 | Safety checks only in Java; UI calls `/validate` | Duplicate checker in TS | One implementation of security rules |
| D11 | Tree block editor, graph view read only | Free node canvas | The language is a tree; canvases admit invalid shapes |
| D12 | shadcn/ui, system UI and mono fonts | Component library dependency | Owned components, consistent look across three apps |
| D13 | `amr` / `acr` computed by the engine only | Configured per flow | Token assurance claims must reflect what actually happened |

Resolved open questions of the log: hook sandboxing needs no GraalVM edition ([ADR 0009](0009-m4-javascript-hooks-on-quickjs-wasm.md)); Faye never sets or changes a password ([ADR 0017](0017-one-sqlite-database.md)); draft previews run only when enabled, only for preview clients and only for drafts that pass the checker ([ADR 0011](0011-m6-preview-loop.md)); an existing user directory plugs in through the credential SPIs (`UserDirectory`, `PasswordAuthenticator`, [ADR 0025](0025-host-neutral-boundaries-for-embedding.md)).

## Open gaps

Each was planned in the roadmap and is not built. Each was checked against the code on 2026-11-01. Gaps already tracked by an `in progress` ADR stay there and are not repeated here.

| # | Gap | State of the code | Remaining work |
|---|---|---|---|
| R1 | Reuse proof (milestone M7) | Hydra (`apps/server/faye-host-hydra`) is the only real host. `EmbeddingTest`, `HandoffApiTest` and `MockFlowHost` drive flows through other `FlowHost`s, and `BoundaryTest` keeps Hydra out of the engine | A second real host (Keycloak, or a generic redirect based host) that runs a tenant's published flow end to end with no change under `src/faye-engine`. Then a write up against the prototype's six goals (flows declared as data, proven safe before they run, extended by hooks that cannot break the checks, composed by a non engineer, tried at once in a preview, reused across authorization servers by swapping one adapter), ending in a go / no go decision on production hardening (HA, multi region, key rotation, rate limiting at scale) |
| R2 | Logout | `HostKind.LOGOUT` exists, and `HydraFlowHost` loads and accepts logout requests (`HydraFlowHostTest.loadsALogoutRequest`). `faye-app` serves no `/logout`, nothing in `deploy/` points Hydra's `urls.logout` at Faye, and the Login UI has no logout screen. The demo client's Sign out forgets its ID token, and for a preview client also revokes that Hydra login session through the admin API (a stopgap; other clients' sessions stay) | A `/logout?logout_challenge=` route with a fixed confirmation screen (a new screen type in the protocol), Hydra's logout URL set in the image, and the demo client signing out through Hydra's end session endpoint |
| R3 | Demo client result view | It shows the identity, the ID token, and for a preview client the trace and the expectation check. It shows no access token introspection and no userinfo response | Add both to the signed in view |
| R4 | Tenant `acr` mapping | `acr` is fixed: `faye:1fa`, or `faye:2fa` for two factor classes or a user verified passkey. [ADR 0010](0010-m5-admin-ui.md) deferred a mapping per tenant | Decide whether tenants may map amr sets to their own acr values. If they may, add it to tenant policy, the checker's reachability and the console |
| R5 | `acr` reachability per client | The checker warns per flow when no path reaches `faye:2fa` (`Checker`), whether or not any client of the tenant asks for it | Warn when the flow cannot reach an `acr` that one of the tenant's clients requests, which needs the client registry at check time |
| R6 | Relaxing enumeration safety | Enumeration safety is unconditional: an unknown identifier resolves like a known account with only a password (`BuiltinHandlers`, `PasswordAuthenticator`). The roadmap allowed a tenant to relax it explicitly, with the checker labeling such flows, which charter principle 1 permits | Decide whether to offer the relaxation (for example to workforce tenants). If so, add a tenant setting and a checker label |
| R7 | Login UI localization | `ui_locales` reaches flows as `host.ui_locales`, but the Login UI's copy is English strings with no message keys | Message keys for every screen's copy and error code, and a locale chosen from `ui_locales` |
| R8 | Accessibility baseline | The target was WCAG 2.1 AA, keyboard complete, with correct `autocomplete` attributes and errors announced in a live region. No automated accessibility check runs: there is no axe in `apps/web/apps/login/e2e`, and no audit is recorded. Unverified | An automated check in `pnpm e2e` for every screen type, plus one manual audit |
| R9 | Tenant model in production | The prototype shares one Hydra and reads the tenant from the client's `metadata.tenant`. One Hydra per tenant was never weighed | Decide the production tenant model before production hardening |
| R10 | Account recovery and sign up | Neither exists, and the Sign up button is a placeholder ([ADR 0033](0033-login-page-customization-and-keep-me-signed-in.md)). ADR 0024 reserves a `register` entry kind for sign up | A sign up ADR (entry kind, checker rules, blocks), and a recovery design that fits the same DSL |
| R11 | Console for a shared deployment | The console has no sign in and listens on localhost only. One admin token serves every tenant, and there are no per tenant roles ([ADR 0018](0018-two-tenants-and-an-open-local-console.md), [ADR 0028](0028-admin-api-on-a-unix-domain-socket.md)) | Operator sign in and per tenant roles before the console is reachable beyond one machine |
| R12 | Rebuild by a non author (milestone M5) | The `apps/admin` tests rebuild the four shipped flows from palette blocks and inspector edits alone. No session where someone who did not write the flows rebuilds them in the console is recorded | Run and record one such session |

Risks carried forward from the log: the hook runtime dependency is young (quickjs4j 0.1.1, pinned and swappable behind `HookRuntime`, with the adversarial suite gating upgrades), and scope creep toward a full IdP, which the charter's non goals bound.
