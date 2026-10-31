---
status: in progress
date: 2026-10-31
decision-makers: Josh Wu
---

# The Core IR as a narrow waist

## Context and Problem Statement

Faye compiles YAML to an S expression IR that the checker proves safe and the engine runs ([D2, D3, D4](../roadmap/decisions-and-risks.md)). The charter says the IR exists "for interop and modularity: it lets the DSL, the checker, the editor and the engine evolve separately, and lets another engine implementation prove it behaves the same". On `main` at 711b3ab it does not yet do that job:

| # | Gap | Where |
|---|---|---|
| G1 | Section names are hard coded and tied one to one to the host's challenge kind | `Flow.SECTIONS`, the schema's `section` enum, `HostKind.section()`, `Engine`, `FlowCompiler`, `Checker` and `Report` (`"login"` literals), 13 files in `web/apps/admin` |
| G2 | No IR format version: `["flow", id, version, ...]` carries the author's version only, so a new engine silently reinterprets stored versions and pinned in flight flows | `ir.schema.json`, `IrCodec`, `FlowVersion`, `FlowStateCodec` |
| G3 | The compiler keeps its own copy of the grammar (`ID`, `NAME`, `KEY` regexes, `Retry.MAX_ATTEMPTS`) and mixes parsing, desugaring and validation over 38 SnakeYAML node types | `FlowCompiler` |
| G4 | The handler contract is two methods, and `conformance/handlers.yaml` is read only by tests, so nothing ties production handlers to it. No effect classification, no argument schema; the checker names `identity.resolve` | `Handler`, `IdentityHandlers`, `Checker.IDENTITY_HANDLER` |
| G5 | Handler writes run before the revision compare and set, so a submit that loses the race keeps them ([ADR 0007](0007-fuzz-and-adversarial-testing.md) seed finding 7) | `Engine.submit`, `IdentityHandlers` |
| G6 | Screens are a closed enum in the engine that the checker imports | `ScreenType`, `Checker.structure` |
| G7 | The checker models `subject_mismatch` as retryable; the engine ends the flow ([ADR 0011](0011-m6-preview-loop.md) known limit) | `Checker`, `Engine` |
| G8 | No normative semantics: they are spread over ADR 0005, `dsl-and-ir.md`, schema descriptions, Javadoc and the vectors, and `dsl-and-ir.md` still says the Admin UI edits the IR, against [ADR 0010](0010-m5-admin-ui.md) | `docs/` |
| G9 | The flow id is a bearer capability: `GET /api/flows/{id}` returns the nonce and an enrollment secret to anyone holding it | `FlowController` |
| G10 | The version cache is unbounded, and every previewed draft is stored forever | `SqliteFlowVersions`, `DraftPreviews` |
| G11 | `server/dump.rdb`, a Redis dump, is tracked though Redis is gone (AGENTS.md rule 7) | `server/` |
| G12 | The IR names the authorization server: predicates read `hydra.*`, hooks get `ctx.hydra`, and `HostRequest.hydra()` is part of the engine contract, against charter principle 3 | `ir.schema.json`, `Env`, `HostRequest`, hooks |
| G13 | `choose` takes an optional *leading* hook, and an option is enabled unless its body *happens to be* a `when` whose test is false: the shape of a body carries meaning | `ir.schema.json`, `Engine`, `Checker` |

G12 and G13 were found while writing this record; the others come from the re-review in `docs/refine.md`.

What must the IR, its versioning and the engine's contracts look like for the IR to be the one stable boundary between frontends and engines?

## Decision Drivers

* Charter principles 1 (secure by design), 2 (closed extension), 3 (host neutral) and 7 (small and modular); charter section "The IR".
* One source per fact: the schema is the grammar, `docs/roadmap/ir-spec.md` the semantics, the conformance vectors the executable pins, `conformance/handlers.yaml` the handler contract (AGENTS.md rules 1 and 2).
* Fail closed: anything a reader does not understand is refused, never reinterpreted.
* A publish, a deploy or a crash never makes a flow in progress do something it was not checked to do (D5).
* Few new concepts and no new dependency.

## Invariants

1. **The Core IR is small, stable and versioned.** It has 11 control forms (`seq`, `step`, `screen`, `call`, `hook`, `choose`, `when`, `retry`, `set`, `succeed`, `fail`) plus the structural `flow`, `entry` and `opt`. New capability arrives as a handler, a screen or a predicate operator plus compiler lowering, never as a form. A form is added only when control flow, suspension or termination semantics change, with an ADR and an IR format bump.
2. **The IR is independent of any frontend and any host, but specific to authentication.** It knows entry kinds, credential binding and amr. It knows nothing about YAML, Hydra or `HostKind`.
3. **Security rules are checked once, on the IR, keyed by entry kind.** No `"login"` literal remains in `faye-ir`, `faye-check` or `faye-engine`.
4. **The engine and the checker depend only on contracts**: the IR, the handler catalog (`Handler`, `HandlerMeta`), the screen catalog (`ScreenSpec`, `ScreenRegistry`) and `FlowHost`. Neither depends on a frontend, and the checker depends on no engine internals (no screen enum, no interpreter state).
5. **The S expression is the abstract syntax; JSON arrays are its only serialization.** No S expression reader is added; the display notation is printed, never parsed.
6. **Arity rule.** Every node is `[head, required positional arguments..., options?]`. A form's positional arity never changes within a format; every optional or future parameter goes in the trailing options object. A form with a variadic tail (`seq`, `flow`) takes no options object.

## Considered Options

1. Top level units
   * A. Sections named after the host challenge (status quo).
   * B. Typed entries `["entry", id, kind, body]`, chosen by an engine side `EntrySelector` from the host challenge kind.
   * C. Entries that each carry a selection predicate.
2. Where the format version lives
   * A. Inside the flow: `["flow", <format>, id, version, entry...]`.
   * B. An envelope `{"format": 1, "ir": [...]}`.
   * C. Out of band, in a column of `faye_version`.
   * D. Nowhere; rely on the content hash.
3. Node shape of `choose` and `opt` (G13)
   * A. Keep the leading hook and the implicit guard.
   * B. JsonML style: an optional attributes object right after the head of every form.
   * C. The arity rule: `["choose", [opt, ...], {pick}?]` and `["opt", id, body, {guard}?]`.
4. Host values in the IR (G12)
   * A. Keep `hydra.*`.
   * B. Rename the namespace to `host.*`; the YAML surface keeps `hydra.` as an alias it lowers.
   * C. Rename to protocol names (`oidc.*`).
5. Handler effects relative to the revision compare and set (G5)
   * A. Two phase: handlers return deferred writes, run after the compare and set.
   * B. Idempotent handlers keyed by flow and revision, writing inline.
   * C. A compensation log (saga).
   * D. Hybrid by effect class: guards inline and owned by the flow, writes through an outbox saved with the flow state.
   * E. Claim the revision before running, so only one run invokes handlers.
6. Handler contract (G4)
   * A. Methods on `Handler` (status quo).
   * B. One `HandlerMeta` value per handler, equal to its entry in `conformance/handlers.yaml`, enforced by a test.
7. Screens (G6)
   * A. Closed enum in the engine (status quo).
   * B. A `ScreenRegistry` of `ScreenSpec` values, mirroring `HandlerRegistry`.
8. Layers of checking
   * A. Schema, then the checker, at publish only.
   * B. Three layers: L0 schema (every read), L1 verifier (rules the schema cannot express and catalog resolution, on every load), L2 checker (paths and policy, at publish and preview).
9. Versioning policy
   * A. Readers support formats N and N-1 for one release, for stored versions and continuations alike.
   * B. Version the IR format, the state format and handler semantics separately; upgrade stored IR on read and never re hash it; keep N and N-1 only for continuations.
   * C. Recompile every stored version from its YAML on each format change.
10. Browser binding (G9)
   * A. Keep the flow id as the capability.
   * B. A per flow HttpOnly cookie whose hash the flow state keeps, required on every read and submit.
   * C. A signed token in the Login UI's URL.
11. Retention of unpublished versions (G10)
   * A. Keep every stored version forever (status quo).
   * B. Delete versions no tenant published and no live flow pins, once they are older than the flow TTL.

## Decision Outcome

1B, 2A, 3C, 4B, 5D, 6B, 7B, 8B, 9B, 10B, 11B.

### Entries (1B)

`["entry", id, kind, body]`, kind `authenticate` or `consent`. `register` is not added here; the sign up ADR adds it with its checker rules. Ids follow the id pattern and are unique per flow (Java, like option ids). `EntrySelector` in `faye-engine` maps `LOGIN` to the flow's single `authenticate` entry and `CONSENT` to its single `consent` entry; `HostKind.section()` is deleted. The checker refuses a flow with no `authenticate` entry, or with more than one entry of a kind, until an ADR defines selection among several. `FlowState` stores the entry id. The YAML surface is unchanged: `login:` lowers to entry `login` of kind `authenticate`, `consent:` to entry `consent` of kind `consent`. Trace `start` events carry the entry id, so `start login` is unchanged.

The kind is a typing context, not only a dispatch key: it decides which rules the checker applies and what `succeed` means (ir-spec.md, Entries). 1C was rejected: selection logic in the IR is control flow the host should not have to evaluate, and nothing needs it yet.

### Format version (2A)

The format is element 1 of the flow array, so it is self describing, inside the canonical bytes and therefore inside the hash. `IrCodec.FORMAT = 1` is written; `IrCodec.FORMATS = {1}` is read; any other format is refused with an `IrException` at `/1` before schema validation, so the message says "unsupported IR format" rather than a grammar error. 2B adds a second envelope to every document for no gain; 2C splits the version from the bytes it describes; 2D cannot tell a new reader that its rules changed.

### Node shape (3C)

Under the arity rule, the optional pick hook moves to `choose`'s trailing options, and the option guard becomes explicit: `["opt", id, body, {"guard": predicate}]`. An option without a guard is always enabled; a `when` in an option's body is an ordinary `when`. The compiler lowers an option's `if:` to its guard, so the YAML meaning is unchanged. 3B (JsonML) is established prior art for JSON S expressions, but would move the options of `screen` and `call`, which already follow the trailing rule. Because format 1 is the first versioned format, this costs no upgrade path.

### Host values (4B)

The namespace of what the host relays (`subject`, `skip`, `acr_values`, `requested_scope`, `login_hint`, `context`) is `host.*`. `HostRequest.hydra()` becomes `params()`, `Env.hydra()` becomes `host()`, and hooks get `ctx.host`. The compiler accepts `hydra.` in YAML predicates and lowers it to `host.`, so existing tenant YAML compiles unchanged, with one exception the new reader adds deliberately: YAML 1.1 scalars that read as another type than they look (`yes`, `on`, `0x1F`, `010`) are refused with a diagnostic rather than retyped, where the old compiler read `yes` as false. 4C was rejected because `skip` and `context` are not protocol parameters.

### Effects (5D)

Research into durable execution engines and identity servers (More Information) found that "defer every write" is wrong for replay protection: RFC 6238 section 5.2 requires that a verifier "MUST NOT accept the second attempt of the OTP after the successful validation", and Keycloak marks a code used inside verification. Deferring the mark until a per flow compare and set wins would let two *different* flows accept the same code, and a crash after the compare and set would lose the mark. So a handler declares one of three effect classes:

* `none`: reads only.
* `guard`: an atomic check and mark that is part of verification (TOTP last step, WebAuthn sign count). It runs inline, is idempotent for the flow that makes it and refuses other flows: `totp_last_step` advances only if the step is newer, or equal and recorded by the same flow (`totp_last_flow`). A losing submit in the same flow leaves only a mark the winner treats as its own. The sign count is a monotonic maximum; a count that does not increase is not an error by itself (WebAuthn Level 3, section 6.1.1).
* `write`: a lasting change the person asked for (TOTP enrollment, passkey registration). The handler returns it as data, an `Effect(handler, data)`, which the entry stages: it rides in the flow state across screens and is applied only if the entry succeeds, so an entry is the transaction. The request whose compare and set ended the entry successfully applies the staged writes in order through each handler's `commit`, then tells the host. No other request applies anything, and loading a flow never does. An entry that fails, ends in `subject_mismatch`, or is abandoned drops its writes. A commit whose precondition fails (the credential id belongs to another account) or that throws (`write_error`) fails the entry, and the host rejects. Writes are applied before the host is told, so a failing write can still make it reject; if the host cannot be told afterwards, the writes stay (the entry succeeded) and the person starts the sign in again.

An independent review of a first version, which applied writes after every winning save and recovered pending writes on any later load, found two faults: a double click during that window let the second request apply the writes while the first got `conflict`, leaving the challenge neither accepted nor rejected; and writes staged before a later failure were applied, so someone holding only a password could register a passkey and abandon the flow. Applying writes only on success, by the finishing request alone, removes both. 5B alone still writes from the losing run. 5C cannot undo a host accept, and a compensating delete can itself fail. 5E removes losing runs but not abandoned ones, and adds a lease to every store. Only `write` handlers may return effects; the engine refuses one that returns effects it did not declare.

### Handler catalog (6B)

```java
record HandlerMeta(Set<String> amr, boolean bindsSubject, boolean identifies, boolean requiresSubject,
                   EffectKind effect, JsonNode args) {}
```

* `amr`, `bindsSubject`: as before.
* `identifies`: fills `user.*` from what the person typed or the user handle of a passkey; a credential handler runs only after one succeeded. It replaces the checker's `identity.resolve` literal.
* `requiresSubject`: runs only once a credential verified the subject in this flow (Keycloak's `requiresUser`). The checker refuses a path that reaches it first. It guards credential *writes*: [CVE-2021-3632](https://db.fluidattacks.com/adv/maven/org.keycloak%3Akeycloak-core/CVE-2021-3632) let anyone register a WebAuthn key for an account without one.
* `effect`: `none`, `guard` or `write`; absent means `write`, the conservative value.
* `args`: a JSON Schema for the `call`'s args object; absent means no arguments.

`conformance/handlers.yaml` is the contract: a `faye-app` test asserts that every production handler's metadata equals its entry and that every entry has a handler, and the conformance runner builds its fakes from it. `createsSubject` (sign up) and `enrolls` are reserved names, not added until something reads them.

### Screen catalog (7B)

`ScreenSpec` (`name`, `prepare`, `render`, `accepts`, `hosted`, `answeredBy`) values in a `ScreenRegistry`, built ins registered by default. The engine emits the `method_picker` screen for a `choose` by name. The checker takes the registry and no longer imports anything screen specific; a test asserts the registry's names equal the screen types in `schema/screen.schema.json`. The screen protocol does not change.

### Layers (8B)

| Layer | What | When | Needs |
|---|---|---|---|
| L0 schema | shape, arity, patterns, the format | every `IrCodec.readFlow` | nothing |
| L1 verifier | unique entry and option ids (in `IrCodec`); every handler and screen exists, `call` args match the handler's `args` schema (`Verifier`) | every read, and in the engine whenever it runs a version | the catalogs |
| L2 checker | path enumeration once per entry, then checks keyed by entry kind | publish, rollback, preview, startup | catalogs, tenant policy |

Every L0 error carries a JSON pointer; the compiler maps the pointer to the YAML that produced it through a pointer to `SourceRef` table it keeps during lowering, looking up the nearest mapped ancestor. L1 findings carry the `SourceRef` of the offending node. The engine runs L1 when it starts a flow on a version and whenever it loads a flow, so a catalog that lost a handler fails the flow closed instead of throwing mid run.

### Versioning (9B)

Three things are versioned separately:

* **IR format** (`IrCodec.FORMAT`): what the forms mean. A stored version is read by upgrading it in memory to the current format, as LLVM's `AutoUpgrade` does for old bitcode; its hash is checked against its stored bytes and never recomputed, so content addresses stay stable. An upgrader must keep every node at the same child index path, because continuations address nodes by path; a change that cannot must also bump the state format. A reader keeps the upgrader of every format it has published versions in; dropping one needs a migration that recompiles the affected versions from their stored YAML and republishes them under new hashes.
* **State format** (`FlowStateCodec.FORMAT`): what the frames mean. Flows live at most one TTL, so the engine reads N and N-1 for one release. A state in any other format reads as absent: the flow fails closed and the person starts the sign in again. Faye runs as one container ([ADR 0023](0023-one-container-many-processes.md)); a deployment with several instances must ship "read N, write N-1" before "write N".
* **Handler semantics**: a handler that changes what it proves or binds gets a new name. `name@major` versioning is left until a handler needs it.

Format 1 is the first format, and nothing before it is readable: migration `V2__ir_format_1.sql` deletes the stored versions, histories, current pointers, flow states and preview traces of the unversioned format (acceptable pre release). At startup every tenant's configured YAML is published again in format 1; drafts are YAML and survive. Fixtures of format 1 flows and states are frozen under `conformance/compat/ir1-state1/` and read by a test from now on, so a later reader that drops them fails the build.

Canonicalization is syntactic only (sorted keys, no insignificant whitespace, empty options objects omitted) and is part of the format. A compiler release never rewrites semantics into the canonical form (no predicate simplification), because that would change hashes and paths.

### Browser binding (10B)

`/login` and `/consent` set a cookie per flow, `faye_flow_<flowId>`, holding 32 random bytes: HttpOnly, SameSite=Lax, Path=/, Secure when the Login UI is served over https, expiring with the flow, and expired by the answer that finishes the flow (the Login UI passes that cookie through). `FlowState` keeps its SHA-256. `GET /api/flows/{id}` and its submit require the matching cookie and answer a missing or wrong one exactly as an unknown flow (404 `flow_not_found`), so they reveal nothing. A cookie per flow lets several tabs sign in at once. The Login UI's Next server forwards that one cookie on both calls. The cookie is host only, so the Login UI and Faye's `/login` must share a host name (cookies ignore ports), as they do in every shipped deployment; a split deployment puts both behind one origin. 10C was rejected: a bearer token in a URL leaks through history and referrers, and is still a bearer token.

### Retention (11B)

Previewing a draft stores its version so the flow can pin it ([ADR 0011](0011-m6-preview-loop.md)). `SqliteFlowVersions` deletes, when a version is stored, any version that is in no tenant's history, is pinned by no live flow and was stored longer ago than the flow TTL. `faye_version` gains a `stored_at` column. The read cache becomes an access ordered LRU of 256 versions.

### Checker (G7 and entry kinds)

* `subject_mismatch` is modeled as the engine runs it: a `call` of a subject binding handler has, besides its ok and failing paths, a path that ends the entry with `subject_mismatch`, never retried, whenever a subject may already be bound (a credential earlier on the path, or a subject the host pre-binds for a step up, which the checker cannot rule out).
* `authenticate`: identity before credential (`identifies`), a credential that binds the subject on every success path, `requiresSubject` handlers only after such a credential, tenant MFA, `acr` reachability warnings.
* `consent`: no handler with nonempty `amr` or that binds a subject.
* Every kind: catalog resolution and tenant allowlists (L1), hooks pinned and egress granted, unique step ids, at most one entry per kind, an `authenticate` entry present.

### Rejected for hooks

Hook output stays `set`, `fail` or `pick` among enabled options. Auth0's `recordMethod` and `setPrimaryUser`, Okta's `credential: VERIFIED` import hook and Cognito's `issueTokens` were considered and are rejected: each lets tenant code mark a person authenticated, which charter principle 2 forbids.

### Consequences

* Good, because the schema, `ir-spec.md`, `handlers.yaml` and the vectors are each the single source of one fact, and an agreement test fails the build when the schema's forms, the Java node types and the spec's sections drift apart.
* Good, because a flow in progress either runs the semantics it was checked under or fails closed; nothing is silently reinterpreted.
* Good, because a lost race leaves no lasting change, and replay protection holds across flows and crashes.
* Good, because a leaked flow id no longer reads the screen, its nonce or an enrollment secret.
* Bad, because format 1 drops every stored version and flow of the old format, and published versions come back only from configured YAML (pre release; said here and in the PR).
* Bad, because staged writes (a TOTP secret, a passkey's public key) rest in the flow state until the entry ends; the flow state already held the TOTP secret as screen data.
* Bad, because the TOTP guard adds `totp_last_flow` to `faye_user`, and the identity module's store must implement owned guards.
* Neutral: tenant hooks reading `ctx.hydra` must read `ctx.host`.
* Unverified: the cost of L1 on every flow start. It is a tree walk with one schema check per `call` that has args; measure if flows grow past a few hundred nodes.

### Confirmation

* `cd server && ./gradlew build` (format, Checkstyle, `-Werror` with NullAway, every test):
  * `faye-ir`: `IrCodecTest` (format refusal, entries, guards, picks, duplicate ids, pointers, display), `IrAgreementTest` (the schema's forms, `Node`'s permitted types and `ir-spec.md`'s form sections agree).
  * `faye-dsl`: `FlowCompilerTest` (each tenant flow lowers to its golden under `src/test/resources/golden/`; each case under `diagnostics/` reports exactly its `# expect:` lines at `line:column`; `hydra.` lowers to `host.`), `HookCompileTest`.
  * `faye-engine`: `ConformanceTest` over every vector, including `subject-mismatch-on-first-attempt`, `effect-lost-race`, `effect-commit-fails`, `effect-dropped-on-failure`, `double-submit-sees-the-winners-screen`, `double-submit-after-the-flow-finished`, `binder-mismatch`, `catalog-unknown-handler` and the regenerated `conformance/generated/` (whose `mfa-policy` paths pin `subject_mismatch` on a non-final attempt); `CompatTest` (`conformance/compat/ir1-state1/` resumes); `ScreenRegistryTest` (built in screens equal `screen.schema.json`; since [ADR 0025](0025-host-neutral-boundaries-for-embedding.md) `BuiltinScreenTest` in `faye-screens`); `HookContractTest` (hooks.md quotes the limits `HookOutput` enforces); `EngineTest`.
  * `faye-check`: `CheckerTest` (rules by entry kind: `aConsentEntryCannotVerifyACredential`, `aCredentialWriteNeedsAVerifiedSubjectFirst`, `entriesAreOnePerKindWithAnAuthenticateEntry`, `theVerifierRefusesUnknownScreenOptionsAndBadArgs`), `GeneratedVectorsTest`.
  * `faye-identity`: `EffectsTest` (a lost race enrolls nothing; one TOTP code is good for one flow; a double submit of one flow is a conflict, not a replay).
  * `faye-app`: `HandlerContractTest` (production handlers equal `handlers.yaml`), `FlowApiTest.aFlowNeedsTheBinderOfTheBrowserThatStartedIt`, `SqliteRestartTest.conditionalAccountWritesHoldInTheDatabase`, `FlywayMigrationTest` (V2 applies).
* `grep -rn '"login"' server/*/src/main` prints only the YAML surface key in `FlowCompiler` and the Hydra adapter's path segment.
* `cd web && pnpm generate && pnpm typecheck && pnpm lint && pnpm test && pnpm build`; `pnpm generate` also writes `packages/contract/src/ir.ts`, the grammar facts (id, key and path patterns, namespaces, operators) the Admin UI checks as you type, from `schema/ir.schema.json`, and CI fails when it is stale.
* `deploy/smoke.sh` on a fresh stack (CI `e2e`).

Run on 2026-10-31: `./gradlew build` passes; the web checks pass; `deploy/smoke.sh` passes all 13 steps on a fresh container of the image built from this change (real Hydra, the binder cookie carried by the script's cookie jar).

No implementation gap is open. The record stays `in progress` until the change is merged; then a human decides whether to mark it `accepted`.

Confirmed by the user (2026-10-31): migration V2 drops the state of the unversioned format rather than upgrading it, and the YAML reader refuses YAML 1.1 scalars that read as another type than they look.

## Pros and Cons of the Options

### 1A Sections named after the challenge

* Good, because nothing changes.
* Bad, because the IR names host concepts (G1), and a sign up entry would need a second challenge name.

### 1B Typed entries

* Good, because rules key on what an entry does, not on what the host calls it.
* Bad, because the selector is one more piece in the engine.

### 2B Envelope

* Good, because the IR array stays as it was.
* Bad, because every document gains a wrapper and the format could be stripped from the bytes it describes.

### 3B JsonML attributes

* Good, because it is prior art for S expressions in JSON.
* Bad, because it moves the options of `screen` and `call` and breaks the trailing rule the rest of the IR follows.

### 5A Two phase only

* Good, because it is simple.
* Bad, because a crash between the compare and set and the writes loses them, and replay marks must not wait for the compare and set.

### 5B Idempotent inline handlers

* Good, because retries are harmless.
* Bad, because the losing run still writes.

### 5C Compensation

* Bad, because a host accept cannot be undone and a compensating write can fail.

### 5E Claim the revision

* Good, because a losing request never runs a handler.
* Bad, because a lease adds a third state to every flow store, and crashes still need the outbox.

### 9A N and N-1 for everything

* Bad, because a tenant's current version is not bounded by the flow TTL; it lives until the tenant publishes again.

### 9C Recompile from YAML

* Good, because no upgrader is needed.
* Bad, because every format change re hashes every version and moves the stability burden to the YAML DSL.

### 10C Token in the URL

* Bad, because it leaks through history and referrers and is still a bearer token.

## More Information

Research done for this record on 2026-10-31 (sources checked then; recheck before relying on details):

* **LLVM and MLIR.** Verifier versus Lint ([Passes](https://llvm.org/docs/Passes.html)), MLIR verification order ([ODS](https://mlir.llvm.org/docs/DefiningDialects/Operations/)), effects declared on the callee and conservative by default ([LangRef](https://llvm.org/docs/LangRef.html#function-attributes), [side effects rationale](https://mlir.llvm.org/docs/Rationale/SideEffectsAndSpeculation/)), bitcode compatibility and auto upgrade on read ([DeveloperPolicy](https://llvm.org/docs/DeveloperPolicy.html#ir-backwards-compatibility), [AutoUpgrade.cpp](https://github.com/llvm/llvm-project/blob/main/llvm/lib/IR/AutoUpgrade.cpp)), frozen compatibility tests, `expected-error` diagnostic tests ([MLIR testing guide](https://mlir.llvm.org/getting_started/TestingGuide/)). Not copied: TableGen code generation, pass managers, dialects, a binary format, a parseable text form.
* **Durable execution.** Temporal activities and idempotency keys ([docs](https://docs.temporal.io/activity-definition)), Restate journaled steps ([docs](https://docs.restate.dev/concepts/durable_execution)), the transactional outbox ([microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)), version pinning in Temporal, Step Functions, Zeebe and Azure Durable Functions ([Azure](https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-orchestration-versioning)).
* **Identity servers.** Keycloak's `OTPCredentialProvider` marks codes used during verification; its auth sessions are bound to a signed `AUTH_SESSION_ID` cookie checked by `SessionCodeChecks`; Kratos requires the CSRF cookie to fetch or submit a browser flow ([login handler](https://github.com/ory/kratos/blob/master/selfservice/flow/login/handler.go)). No product found verifies flow safety statically; Keycloak only checks at run time that some user is set.
* Two independent reviews of the implementation (2026-10-31) found, besides the effect faults above: integers the schema allowed but the reader could not represent (now bounded in the schema), YAML 1.1 scalars retyped silently (now refused), a consent that granted when its version had two consent entries (now `flow_unavailable`), a consent that ran the current version when the login's could not be read (now `flow_unavailable`), a re-stored draft swept before its flow pinned it (`stored_at` now renews), and a `Secure` cookie on plain http deployments (now set by the Login UI's scheme). Each has a test or vector.
* A losing double submit is answered with the screen the winner led to, not `409`, as Restate answers a repeated request; the binder means only the same browser can lose such a race. A loser of a finished flow gets `flow_finished`: keeping the host's redirect for it would keep an unredeemed verifier at rest and would rarely be saved in time, and both answers reach the same page, which follows the winner's redirect. Fuzzing the outbox, binder and races (F6, F6b, F11) is [ADR 0007](0007-fuzz-and-adversarial-testing.md)'s plan, not this record's.
* Follow ups not decided here: screen specs declaring the fields they produce so the checker can follow data from a screen to a handler; handler versioning `name@major`; attempt counters and lockout as `guard` effects.
