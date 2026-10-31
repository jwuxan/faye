---
status: in progress
date: 2026-10-31
decision-makers: Josh Wu
---

# Fuzz and adversarial testing

## Context and Problem Statement

Charter success criterion 3 says no flow or hook, however written, can produce a successful sign in unless a built in handler verified or enrolled a credential for that subject, and that "adversarial tests check it". Today the evidence is the hand written conformance vectors and unit tests. They check the cases someone thought of. They do not check the inputs nobody wrote down: a `null` inside a submission, a 20 digit integer in a predicate, a mutated CBOR attestation, a Hydra response with the wrong types, a flow state that a newer deploy cannot read.

Faye is an authentication component that faces the internet before the user is authenticated. Every request to the screen API, every `login_challenge` and every field Hydra relays from the authorize URL is attacker controlled. Tenants write the flows, and from M4 they write JavaScript hooks. A production Faye has to show, continuously and not once, that hostile input on any of these boundaries fails closed.

What fuzzing and adversarial testing does Faye run, with which tools, against which invariants, how often, and what happens to a finding?

## Decision Drivers

* Charter principle 1 and success criterion 3: the security guarantees are properties of the system, so they are tested as properties, not as examples.
* Fail closed: hostile input ends in a declared refusal (`FlowException`, `DslException`, `IrException`, `Outcome.Fail`, a host reject), never an undeclared exception, a hang, or an accept.
* AGENTS.md rule 2: engine behavior is pinned by conformance vectors, so fuzz findings about the engine must end as vectors.
* AGENTS.md rule 7: no binaries in the repo, tools fetched on demand with pinned versions.
* Global rule "simplicity" and charter principle 7: as few new dependencies and tools as reach the goal.
* Fuzzing must run in the normal build (regression), on every PR (short), and continuously (long), without making `./gradlew build` slow or flaky.
* A finding is a security bug until triaged, and must not leak through public CI logs.

## Threat model

Fuzzing is aimed at trust boundaries. Each row says who controls the input and what Faye must guarantee.

| # | Boundary | Who controls it | Inputs | Must hold |
|---|---|---|---|---|
| B1 | Browser to screen API (`GET /api/flows/{id}`, `POST .../submit`, via the Login UI proxy) | Anyone on the internet, before authentication | `flowId`, `nonce`, `action`, `payload` of any JSON shape, headers, content type, body size | Declared status only (200, 400, 404, 405, 409, 410, 413, 415), all with the `ErrorResponse` body; state unchanged on refusal; no accept without a verified credential. The `flowId` alone is a bearer capability: it reads the screen, its nonce and any secret the screen carries |
| B2 | Browser to entry points (`/login`, `/consent`) | Anyone; relying parties | `login_challenge`, `consent_challenge`, `User-Agent` | Challenge reaches Hydra only as an encoded query value; no 5xx |
| B3 | Hydra admin API responses | Partly attacker: `login_hint`, `acr_values`, `ui_locales` come from the authorize URL; `client_name` and `metadata` come from client registration (whether Hydra lets dynamic registration set `metadata` is unverified; check Hydra's source before relying on either answer) | Every field, any JSON type, status codes, `redirect_to`, `context` | Wrong types are ignored or refused; tenant, `first_party` and `context.flowId` cannot be used to cross tenants or subjects; `redirect_to` is never followed if malformed |
| B4 | Stored flow state (the `faye_flow` table of the one SQLite database, [ADR 0017](0017-one-sqlite-database.md)) | Faye, but across deploys, and anyone who can write the database file | `FlowState` JSON, revision, TTL | Unreadable or inconsistent state fails closed; a state from an older deploy either resumes correctly or ends the flow |
| B5 | Tenant flow source (YAML DSL, IR JSON) | Tenant authors, untrusted relative to the platform | Any YAML or JSON document | Compiler and codec refuse with a pointer; anything that publishes keeps the charter guarantees |
| B6 | Credential material | The person signing in, or an attacker replaying or forging | WebAuthn JSON, base64url, CBOR, COSE keys, `authenticatorData`, `clientDataJSON`, TOTP codes, passwords, base32 secrets | Only a valid proof for the stored credential yields `Outcome.Ok`; parsers never throw undeclared exceptions |
| B7 | Hooks (M4) | Tenant JavaScript | Return values, exceptions, timing, memory | Only `set`, `fail`, `pick`; deadline and memory limits hold; no host access |
| B8 | Screen payloads to the Login UI | Faye, carrying B3 strings (`login_hint`, `client_name`, scopes) | Any string the protocol allows | Every payload validates against `schema/screen.schema.json`; rendering never interprets a string as markup or a URL scheme |

## Considered Options

Fuzz engine for Java:

* A. Jazzer `@FuzzTest` (coverage guided, libFuzzer based, JUnit 5)
* B. jqwik (property based, JUnit 5 engine, stateful action sequences)
* C. JQF with Zest (coverage guided generators)
* D. Hand written randomized tests with `java.util.Random`, no new dependency

TypeScript:

* E. fast-check (property based) under `node --test`
* F. Jazzer.js

Black box over HTTP:

* G. Schemathesis or RESTler against an OpenAPI description
* H. In process HTTP fuzzing through Spring `MockMvc`, driven by the Java engine chosen above

Where fuzz inputs live:

* I. Full corpora committed to the repo
* J. Corpora in CI storage; only text seeds and minimized reproducers in the repo

Continuous fuzzing service:

* K. Plain GitHub Actions jobs running Gradle with `JAZZER_FUZZ=1` and a duration, corpus in Actions cache
* L. ClusterFuzzLite
* M. OSS-Fuzz

## Decision Outcome

Chosen: **A, E, H, J and K.** Jazzer is the one Java fuzz engine, used for both coverage guided byte fuzzing and structured, stateful fuzzing (an action sequence decoded from `FuzzedDataProvider`). fast-check covers the web apps. HTTP fuzzing runs in process through `MockMvc`. Corpora live outside the repo; the repo holds text seeds and every minimized reproducer as a readable regression test.

The invariants are what matter; the tools only search for counterexamples. The sections below are normative for every harness.

### Oracles

A harness fails on any of these. Each is checked inside the harness, not by eyeballing logs.

| # | Oracle | Applies to |
|---|---|---|
| O1 | **No undeclared exception.** Only the declared refusal types for the unit under test may escape: `FlowException`, `DslException`, `IrException`, and two types to add, a host failure exception in `faye-engine` (today `HydraFlowHost` throws `IllegalStateException` for everything) and an unreadable state exception for `FlowStateCodec`. `NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `IllegalStateException`, `StackOverflowError` and `OutOfMemoryError` are findings. Over HTTP: no 5xx; Spring's own 400, 405 and 415 replies are mapped to `ErrorResponse`; no other body than `ErrorResponse`, a redirect or a valid screen | All |
| O2 | **Bounded resources.** Each input completes within a per target time limit and heap cap (Jazzer `-timeout`, `-rss_limit_mb`, and a reduced `-Xss` to surface recursion) | All |
| O3 | **No accept without proof.** A login the host accepts has a subject, and at least one handler with non empty `amr` returned `Ok` for that subject in this flow, or the accept came from a `skip` whose assurance meets `acr_values` (ADR 0006) | Engine, API, checker |
| O4 | **Assurance is computed.** For an accept that ran a flow: `amr` equals the union of what handlers proved (`Outcome.Ok.amr`, never wider than declared); `acr` equals `Acr.of(amr)`; `Acr.satisfies(acr, acr_values)` holds, otherwise the flow rejected with `acr_not_satisfied`. For an accept from `skip` (ADR 0006): `amr` is empty, `acr` is `faye:1fa`, and `faye:1fa` satisfies `acr_values` | Engine, API |
| O5 | **Subject is sticky.** Once bound, a subject never changes within a flow; a credential for another subject ends the flow with `subject_mismatch` | Engine |
| O6 | **Refusals do not mutate.** A refused submission (stale nonce, invalid payload) and a submit that loses the revision race (`409`) leave the stored `FlowState` byte for byte unchanged **and** the `UserStore` unchanged: no enrolled TOTP, no TOTP step consumed, no passkey added, no sign count moved. The `UserStore` is part of the model in F6 and F12 | Engine, API, store |
| O7 | **One outcome per challenge.** Across every flow started for one challenge (each `/login` call starts a new flow), the host receives at most one `accept` or `reject`. Concurrent submits for one nonce produce one winner and `409` for the rest. If the host call fails after the finished state is saved, the user gets a declared error and the challenge is rejected or can be finished by a retry; it is never left without an outcome and never accepted twice | Engine, store |
| O8 | **Server data is server data.** Nothing in a payload reaches `Submission.context`, `auth`, `user` keys that handlers own, or the pinned `versionHash` | Engine |
| O9 | **Round trips.** `FlowStateCodec.read(write(s))` equals `s`; `IrCodec.readFlow(canonicalJson(f))` equals `f`, and the hash is stable; compiled YAML validates against `schema/ir.schema.json` | Codecs, compiler |
| O10 | **Differential agreement.** Two implementations of one rule agree: `IrCodec` vs `json-schema-validator` on accept or reject (ADR 0001); `Totp` vs the RFC 6238 reference; `Base32` decode vs encode; static checker vs exhaustive execution (O12); Java vs ajv once the Admin UI validates IR in TypeScript | Codecs, checker |
| O11 | **No enumeration signal.** For one flow and one submission sequence, a known and an unknown identifier with wrong credentials produce the same sequence of screen types, error codes, `attemptsLeft` and response field sets. Scope: the shipped flows and the default flow, with the real `IdentityHandlers`. Tenant flows may branch on `user.exists` by explicit choice (charter principle 1), so O11 applies to them only when the checker shows they do not read `user.exists`, `user.has_*` or `user.mfa_required` before a credential verified | Engine, API |
| O12 | **Checker soundness.** If `faye-check` accepts a flow, exhaustive execution of that flow over every scripted handler outcome finds no path that violates O3 to O5. If it rejects, the counterexample path it reports really reaches `succeed` | Checker (M3) |
| O13 | **Termination.** Every run reaches a screen or a result within a step bound computed from the IR size and the product of `retry` counts | Engine |
| O14 | **Protocol conformance.** Every `ScreenView` the server returns validates against `schema/screen.schema.json` | Engine, API |
| O15 | **Hook containment (M4).** Whatever a hook returns, throws or does, its only effects are `set` into `vars.*`, `fail`, or `pick` among enabled options; deadline and memory overrun fail the step | Hooks |
| O16 | **Claims describe the subject.** Every `user.*` value that reaches the host (ID token claims on consent: `email`, `email_verified`, `name`) belongs to the account of `auth.subject`, never to an account named by a typed identifier the credential did not prove, on step up and after a `retry` around identify alike | Engine, API |
| O17 | **Secrets stay server side.** No response carries a value that would let someone holding only the `flowId` raise assurance without the person: a screen secret shown for enrollment is only shown to a flow whose subject already verified, and enrollment within one flow is counted in `acr` only as the policy in ADR 0006 allows | Engine, API |

### Harnesses

Every harness is a Jazzer `@FuzzTest` in the module that owns the code (or a fast-check test in the web package), runs in regression mode in `./gradlew build`, and is listed here. Adding a parser, handler, screen type, IR form, host or store without a harness, or without extending an existing one, is not done.

| # | Harness | Target | Input | Oracles | Status |
|---|---|---|---|---|---|
| F1 | `faye-ir` `IrCodecFuzzTest.readFlow` | `IrCodec.readFlow` | UTF-8 bytes as JSON; seeds are the conformance flows and hostile shapes (deep nesting, duplicate keys, lone surrogates, 2^64 literals) | O1, O2, O9, O10 (the reader agrees with the schema on what it wrote) | Done |
| F2 | `faye-dsl` `FlowCompilerFuzzTest.compile` | `FlowCompiler.compile` | UTF-8 bytes as YAML; seeds are the shipped flows, the diagnostic cases, aliases, YAML 1.1 scalars, tags, multiple documents, deep steps | O1 (only `DslException`, every diagnostic inside the source), O2, O9, determinism | Done |
| F3 | `faye-engine` `PredicatesFuzzTest.predicates` | `Predicates.test`, `Env.lookup` | Generated predicate trees; an `Env` with every JSON type, `BigInteger`, `BigDecimal`, NaN, `-0.0`, short | O1, and agreement with a reference evaluator written from [ir-spec.md, Predicates](../roadmap/ir-spec.md#predicates) | Done |
| F4 | `faye-engine` `PredicatesFuzzTest.acr` | `Acr.of`, `Acr.satisfies` | Generated `amr` lists and `acr_values` of any JSON type, nulls inside lists | O1; monotonicity (adding an `amr` never lowers `acr`; a stronger `acr` satisfies whatever a weaker one does); `faye:2fa` is never met by one factor, as a list or as OIDC's string | Done |
| F5 | `ScreenSubmissionFuzzTest` | `ScreenSpec.accepts`, `prepare`, `render`, then every real handler | Screen type and payload of any JSON shape, with nulls, wrong types and boundary lengths | O1, O8, O14 | Open. The engine side is covered by F6 (random payloads against every screen) and F11 (over HTTP); the real handlers are not |
| F6 and F13 | `faye-check` `EngineFuzzTest.loginStateMachine` and `.sweep` | `Engine` with an in memory store, the mock host, fake handlers whose outcomes, subjects and amr the fuzzer picks, and fuzzed hooks | A flow (a conformance flow, or IR generated from the grammar), the tenant's MFA policy and the authorization request, then up to 40 actions: valid answers, random payloads, a stale nonce, another browser's binder, a reload through `FlowStateCodec`, an engine restart, a lost revision race, a newer version published mid flow, a second flow for the challenge, and a consent after the login for the same or another subject. Flows the checker accepts are checked against every oracle; flows it refuses but the engine's catalog check lets through test the engine's own guards | O1, O3 to O9, O12 (checker soundness), O14 | Done. Not yet: interleaved threads, host failure, expiry |
| F6b | `IdentityFlowFuzzTest` | `Engine` with the real `IdentityHandlers`, `InMemoryUserStore` and the shipped flows | Known and unknown identifiers, wrong and right credentials, step up with a Hydra subject, concurrent submits | O6 (`UserStore` included), O11, O16, O17 | Open |
| F7 | `faye-engine` `StoredStateFuzzTest.storedState` | `FlowStateCodec.read`, then `Engine.view` and `submit` | Real states of five conformance flows at each screen, mutated: values of the wrong type, missing and extra fields, paths outside the IR, unknown frame tags, another version's hash, huge numbers; and raw bytes | O1 (a screen, a redirect or `FlowException` only) | Done |
| F8 | `faye-identity` `TotpFuzzTest.base32`, `.totp` | `Base32`, `Totp.code`, `Totp.match` | Bytes, strings, secrets, epoch seconds from 1970 to the far future, steps around the window | O1, O9, O10 (an RFC 4226 reference and the RFC 6238 vectors); a code matches only within one step | Done. Replay refusal is pinned by the `totp.verify` guard tests (ADR 0024), not fuzzed |
| F9 | `WebAuthnFuzzTest` | `webauthn.assert`, `webauthn.register`, `identity.resolve` with a passkey | Valid ceremonies from the `webauthn4j-test` emulator, then structure aware mutation of every field (decoded, mutated, re-encoded) | O1, O3: no `Ok` unless the signature verifies for the stored key, the challenge is the screen's, the origin and RP id match, and the user handle names the account | Open |
| F10 | `HydraFlowHostFuzzTest` | `HydraFlowHost` against a JDK `com.sun.net.httpserver` fake | Status, headers, body (any JSON, non JSON, huge, slow), `redirect_to`, `context`, `client.metadata` | O1, O2, Jazzer's SSRF detector: the challenge never changes the admin URL's host or path | Open. A 400, 404 or 410 on load is now `flow_not_found` (`HydraFlowHostTest.hydraErrorsSurface`) |
| F11 | `faye-app` `FlowApiFuzzTest.screenApi` | `FlowController` through `MockMvc`, the mock host | Method, path, content type, cookie, body bytes (also JSON with duplicate keys, nulls and oversized bodies), challenge, `flowId`, `nonce` | O1 (declared statuses only, `ErrorResponse` or no body, no exception text), O14 (screens and redirects validate against the protocol), redirects absolute http(s) | Done |
| F12 | `SqliteFlowStoreConcurrencyTest` | `SqliteFlowStore` | Random interleavings of `create`, `replace`, `load` from several threads on one database file | O6, O7: the history is linearizable against a model store | Open |
| F14 | `HookContainmentFuzzTest` | `HookRuntime` | Generated JavaScript return values (getters, proxies, cycles, `__proto__` keys, huge strings, promises that never settle, throwing `toString`) and a curated sandbox escape corpus | O1, O2, O15 | Open. F6 fuzzes what the engine does with any hook output, including output that tries to write `auth`; the sandbox itself is not fuzzed |
| F15 | `web/apps/login` `lib/redirect.test.ts` | `isNavigable`, which the Login UI runs before `window.location.assign` | fast-check strings, web URLs and hostile prefixes, 5000 runs from a fixed seed | Only an absolute http(s) URL with a host is navigated to, and it matches the protocol's `redirectTo` pattern | Done for redirects. Rendering every screen with hostile strings is open |
| F16 | Admin API and editor harnesses | Admin API, IR edits, publish and rollback | As F1, F11 and F13, plus authorization across tenants | O1, O6, tenant isolation | Open |

The enumeration oracle O11 is functional. Timing is a separate, statistical test (a dudect style comparison of `password.verify` for known and unknown accounts, many samples, nightly), because a fuzzer cannot measure timing reliably.

### Edge case catalog

Harness generators and seed corpora must cover every class below. A Jazzer dictionary per harness carries the tokens; seeds carry the shapes.

**JSON and types**
1. Every field as every JSON type: string, number, boolean, `null`, array, object, missing.
2. `null` as a map value and as a list element (`Map.copyOf` and `List.copyOf` reject both).
3. Duplicate object keys (`{"nonce":"a","nonce":"b"}`), and disagreement between parsers on them: the Login UI proxy forwards raw text, Jackson keeps the last key.
4. Extra fields, empty objects, empty arrays, single element arrays where a string is expected.
5. Deep nesting up to and past Jackson's `StreamReadConstraints` limits; very long strings and numbers; very large documents.
6. Non UTF-8 bytes, overlong encodings, BOM, escaped lone surrogates (`"\ud800"`), escaped NUL.

**Numbers**
7. `0`, `-0`, `-1`, `Integer` and `Long` bounds and one past them, `2^64 + n`, `1e308`, `1e400`, `1.0` vs `1`, `1.9`, exponent forms.
8. Comparison across `Integer`, `Long`, `BigInteger`, `Double` (predicates compare through `longValue()`).

**Strings and identifiers**
9. Empty, whitespace only, Unicode whitespace (`U+00A0`, `U+2007`, `U+3000`) that `trim()` and `isBlank()` treat differently.
10. Length at the limits in UTF-16 units and in code points: 255, 256, 257 for identifiers and 1023, 1024, 1025 for passwords, with surrogate pairs straddling the limit.
11. Case folding and normalization: Turkish dotted and dotless I, `ß`, the Kelvin sign, NFC vs NFD, full width forms, homoglyphs, so that two identifiers that look alike either resolve to one account or are refused, consistently.
12. Control characters, CR and LF (log and header injection), bidi overrides (spoofed `client_name` on the consent screen), zero width characters.
13. Markup and scheme payloads: `<script>`, `"><img>`, `javascript:`, `data:`, template syntax, in every string that reaches a screen (`login_hint`, `client_name`, scopes, `identifier`, error codes).

**Flow ids, nonces and state**
14. `flowId` empty, very long, with `/`, `..`, `%2F`, `%00`, `;`, `*`, `{}`, Unicode; a valid looking id that does not exist; another tenant's flow; a finished flow; an expired flow.
15. Nonce missing, `null`, wrong type, of an earlier screen, of another flow, replayed after success.
16. The `flowId` as a bearer capability: a leaked id read by a third party (`GET /api/flows/{id}` returns the nonce and, on `otp_enroll`, the TOTP secret); a person holding only the password enrolling their own second factor in the same flow and reaching `faye:2fa`; the same challenge started twice, giving two live flows.
17. Two and more concurrent submits with the same nonce, and a submit racing an expiry.
18. Host accept failing after the store saved the finished state (Hydra down, timeout, 5xx), and the user retrying.
19. A stored state from an older build: renamed frame tags, a removed screen type, a path into an IR that changed shape, a `versionHash` no longer published.

**Flow control**
20. `method_picker` answers naming a disabled option, an unknown option, two options with the same id, an empty choice.
21. `retry` at 0, 1 and the maximum; failure in the last attempt; nested retries; a failure outside any retry.
22. Passkey autofill on the identifier screen answering a later passkey screen, a registration screen, or no screen.
23. `skip` with and without a subject, with every `acr_values` shape; consent with a `context.flowId` naming another subject's or another tenant's login.
24. Step up where the person types another account's identifier, and a `retry` around identify that resolves a second account: `user.*` must not mix accounts.

**Time**
25. TOTP at `now - 1`, `now`, `now + 1` steps and one beyond, at step boundaries, epoch `0`, negative and far future instants; the same code twice; two codes of one step concurrently.
26. Flow TTL exactly at expiry; clock moving backwards.

**WebAuthn**
27. `clientDataJSON`: wrong `type` (`webauthn.create` vs `webauthn.get`), origin variants (trailing slash, port, case, IDN and punycode, `http` vs `https`, `null`), challenge of another screen or flow, `crossOrigin: true`, extra and duplicate keys.
28. `authenticatorData`: RP id hash mismatch, flag combinations (UV without UP, BE and BS, AT and ED set without data), sign count zero, equal, lower, wrapped.
29. CBOR: indefinite lengths, declared lengths beyond the buffer, deep nesting, duplicate map keys, non canonical encodings, trailing bytes, unknown tags.
30. COSE keys: algorithm differing from the key type, points off the curve, wrong curve, RSA with tiny modulus or exponent 1, unsupported algorithms.
31. Attestation: unknown `fmt`, `none` with a statement, credential id length 0 and very long; registration replaying an existing credential id, or for an account other than the signed in one.
32. User handle absent, empty, another subject's, not base64url; `rawId` and `id` disagreeing.

**Hydra responses**
33. Non 2xx statuses, timeouts, empty and non JSON bodies, bodies of many megabytes.
34. `subject`, `skip`, `requested_scope`, `acr_values`, `client.metadata` of the wrong type; `metadata.tenant` naming no published flow; `metadata.first_party` as a string.
35. `redirect_to` missing, relative, not a URI, with CR or LF, with a `javascript:` scheme, both on the `/login` path (a `Location` header) and the submit path (JSON `redirectTo` the Login UI navigates to).
36. `subject`, `client_name`, `login_hint` as numbers or objects (they pass `asText()` checks but `textValue()` is `null`).

**YAML and IR**
37. Anchors and aliases (alias expansion), merge keys, tags, multiple documents, duplicate keys at every level, non string keys.
38. YAML 1.1 scalars that change type: `no`, `on`, `yes`, `~`, `0x1F`, `1_000`, `.inf`, octal forms, as step ids, option ids and literals.
39. Nesting past SnakeYAML's limits; very long scalars; tabs; BOM.
40. IR with unknown forms, wrong arity, ids outside the pattern, duplicate sections, step ids and option ids, empty `seq`, `choose` with zero options, `retry` at 0, huge counts, and counts past `Integer.MAX_VALUE` that wrap.

**HTTP**
41. Every method on every route; content types `text/plain`, `application/x-www-form-urlencoded`, missing, with parameters; oversized bodies and headers; chunked bodies.

### Security standard for parsers and limits

Fuzzing checks these; it does not replace them. Each is a code change in its own PR, with the harness that proves it.

1. Jackson mappers that read untrusted input set `StreamReadConstraints` explicitly, including document length and token count, which are unlimited by default, and enable `STRICT_DUPLICATE_DETECTION`. The JSON a browser posts is refused, not silently reinterpreted, when keys repeat.
2. SnakeYAML `LoaderOptions` set explicitly: duplicate keys refused, alias count, nesting depth and code point limits stated, rather than relying on library defaults that change between versions.
3. The screen API and the Login UI proxy cap the request body size and accept only `application/json`.
4. Every value that becomes a redirect (`redirect_to` from Hydra, the Login UI URL) is parsed and checked (absolute `http` or `https`, on Hydra's public origin) before it reaches a `Location` header or the `redirectTo` the Login UI navigates to.
5. Unknown tenants, missing versions and inconsistent stored state end the flow with a declared error and a host reject, not a 5xx.
6. Error responses carry only the wire error code. Host and parser messages go to logs, with control characters escaped.

### Corpora and reproducers

1. **Seeds in the repo, as text.** Each harness has Jazzer's input directory (`src/test/resources/<package>/<Class>Inputs/<method>`) in its module, populated with readable JSON, YAML and conformance flows. Harnesses take `byte[]` and decode UTF-8, so a text file is a valid seed. No generated or binary corpus is committed (AGENTS.md rule 7).
2. **Corpora outside the repo.** The generated corpus (`.cifuzz-corpus/`, git ignored) lives in CI storage keyed by harness, is minimized (`-merge=1`) weekly, and is never published as a public artifact.
3. **Every finding becomes a readable regression** before the fix merges: an engine finding becomes a conformance vector (AGENTS.md rule 2); anything else becomes a named JUnit or fast-check case and a text seed. Raw crash files are never committed.
4. **Dictionaries** per harness list DSL keys, form names, screen types, handler names, error codes, JSON and CBOR tokens.

### Cadence and gates

| Tier | When | What | Budget | Gate |
|---|---|---|---|---|
| T0 | Every `./gradlew build` and `pnpm` check | Regression mode: every seed and reproducer through every harness; fast-check with a fixed seed count | Measure; target under 60 s added to the build (unverified) | Build fails |
| T1 | Every PR | Coverage guided fuzzing of the harnesses whose module changed | 5 min per harness (unverified, tune by measurement) | PR blocked on any finding |
| T2 | Nightly | Every harness, corpus kept between runs, coverage report per harness; timing test for enumeration | 1 h per harness | Findings open a private issue; red nightly blocks release |
| T3 | Before a release | Every harness from the merged corpus | 24 CPU hours in total, and no new coverage in the last 4 h | Release blocked until every finding is triaged and every critical or high is fixed |

Coverage is a ratchet: the nightly report records line and branch coverage of each harness's target package (Gradle's built in `jacoco` plugin), and a drop of more than 2 points needs a reason in the PR that caused it. No absolute target is set until the first nightly runs give a baseline.

### Triage and severity

A fuzz finding is a security bug until triaged. It is tracked privately (GitHub private security advisory or an equivalent private tracker), not in a public issue, and its reproducer is not attached to public CI logs.

| Severity | Examples | Fix by |
|---|---|---|
| Critical | O3, O4, O5, O8, O12, O15, O16 or O17 broken: an accept without proof, inflated `amr` or `acr`, subject switch, payload reaching server data, claims about another account, hook escaping containment; script execution in the Login UI origin | Stop ship; fix before any release; 24 h target |
| High | Pre authentication 5xx or hang reachable from B1 to B3; O6 or O7 broken; O11 broken; resource exhaustion from one request | 7 days |
| Medium | Undeclared exception reachable only from B4 or B5 (stored state, tenant source); O9 or O10 disagreement without a security effect | 30 days |
| Low | Diagnostic quality, a pointer off by one | Next milestone |

Each fixed finding records the harness, the minimized input as a regression, the oracle it broke and the root cause in the PR description.

### Security of the fuzzing setup

1. PR fuzzing runs from forks without secrets and with a read only token. Nightly and release runs use trusted runners and the corpus store; PRs read the corpus but cannot write to it.
2. Harnesses use only demo accounts and freshly generated keys; no real credentials or tenant data enter a corpus.
3. Fuzz tools are pinned by version in `libs.versions.toml` (Jazzer) and `pnpm-lock.yaml` (fast-check); CI actions are pinned by commit SHA; any native tool is downloaded into a git ignored directory with a checksum.
4. Harnesses never talk to a real Hydra or network; F12 uses a database file under `build/`.
5. No fuzzing only code paths in production code: no build flag that skips signature, MAC or nonce checks. Harnesses produce valid proofs instead.

### Plan

1. Add Jazzer to `libs.versions.toml` and as `testImplementation` in the modules that own harnesses. Confirm `@FuzzTest` regression mode runs under Gradle's JUnit platform, and that `-Werror`, Error Prone and NullAway accept the harness code.
2. Land F1 to F11 with seeds and dictionaries, fixing the findings below first so the build is green with the oracles at full strength.
3. Land the parser and limit changes in "Security standard", each with its harness.
4. Add `fast-check` as a dev dependency of `web/apps/login` and F15 under `node --test`.
5. Add the T1 and T2 CI jobs and the private triage process; F12 joins the existing `server` job.
6. Nightly, once the compose stack is stable: the OpenID Foundation conformance suite against Hydra with Faye, as an end to end complement to fuzzing.
7. F13 shares the path enumerator with the generated conformance vectors. M4: F14 before any hook runs outside tests. M5: F16.

### Where the plan stands (2026-10-31)

Steps 1 to 5 are done for the harnesses marked Done above; the rest are the open gaps listed at the end of this section.

**Setup, as built.** Jazzer `jazzer-junit` 0.30.0 is a `testImplementation` of every server module (`server/build.gradle.kts`, `libs.versions.toml`). Regression mode needs nothing; the `Test` task passes `JAZZER_FUZZ` and `JAZZER_MAX_DURATION` to the forked test JVM explicitly, since a Gradle daemon's environment need not be the caller's (with it, fuzzing starts as asked; whether it would without it was not tested). Error Prone, NullAway and Checkstyle accept the harness code as written. Jazzer fuzzes one test per JVM, so `deploy/fuzz.sh [duration] [filter]` runs one Gradle invocation per harness; `.github/workflows/fuzz.yml` runs it for 2 minutes per harness on pull requests that touch the server (T1, reading the cached corpus) and 30 minutes per harness nightly (T2, writing it). The corpus is the git ignored `.cifuzz-corpus/` of each module; `crash-*` files are git ignored too. fast-check 4.10.2 is a dev dependency of `web/apps/login`, whose new `pnpm test` runs F15.

**Stored inputs are text.** Byte harnesses (F1, F2) keep readable seeds in `src/test/resources/<package>/<Class>Inputs/<method>/`. A `FuzzedDataProvider` input is not readable, so the structured harnesses keep their regressions as named `@Test` cases instead, and `EngineFuzzTest.sweep` runs 3000 inputs from a fixed seed through the same harness in every build. Jazzer's provider can only be built inside its agent, so the harness draws its choices through a small `Source` interface with a provider backed adapter and a byte array one.

**A harness must keep reaching the code.** `EngineFuzzTest.sweep` fails if the generator stops producing work: at 3000 inputs it measured 689 flows the checker accepted, 1870 it refused that still ran, 740 accepts, 2997 rejects (813 by the engine's own credential check), and 281 consents; the floors are well below that.

**Budgets, measured.** T0: the harness suites add 41 s of test time to `./gradlew build`, spread over modules that run in parallel (from the JUnit reports on this machine; the build's wall clock grew less). One fuzzing run of 60 to 240 s per harness, single core: F1 1.16 M inputs in 120 s, F2 0.91 M in 120 s, F3 5.5 M in 60 s, F4 10.1 M in 60 s, F7 127 k in 180 s, F8 7.1 M and 3.9 M in 60 s, F11 19 k in 240 s (Spring's MockMvc dominates), F6 about 2 k per second.

**The harness catches planted bugs** (Confirmation 3). Each was planted in `Engine.java` or `BuiltinScreen.java` alone and `EngineFuzzTest` run in regression mode with the sweep:

| Planted bug | Caught by |
|---|---|
| O5: `checkSubject` never reports `subject_mismatch` | O3/O4: the accepted `amr` was proved for another subject |
| O4: a handler's narrowed `amr` widened to its declared set | O4: `amr` is what credentials proved |
| O4: `acr_values` ignored | O4: meets `acr_values` |
| O4: the tenant's MFA policy ignored | O4: `faye:2fa` when the tenant requires MFA |
| O6: a stale nonce accepted | O6: must have been refused |
| O6: a submit that lost the revision race applied anyway | O6: must have been refused |
| O14: the consent screen without `scopes` | O14: screen protocol |

Removing "at least one credential verified" from the success check is not caught, and cannot be: a subject is bound only by a credential or by a step up, and a step up only runs when one factor does not meet the request, so the `acr` checks after it refuse the same runs. It is kept as defense in depth.

**What the harnesses found, and what changed.** Each finding has the regression named; none had reached a release.

| Harness | Oracle | Finding | Fix | Regression |
|---|---|---|---|---|
| F1 | O1, O2 | An IR document nested a few hundred levels deep overflowed the stack in schema validation | `IrCodec` refuses nesting past 128 levels before validating, and reads JSON with explicit limits (1 MiB, 64 digit numbers) and duplicate keys refused | seeds `deep-seq.json`, `seq-at-limit.json` |
| F2 | O1, O2 | A recursive YAML alias (`a: &a [*a]`) overflowed the stack | The surface refuses anchors and aliases | `diagnostics/aliases.yaml`, seed `recursive-alias.yaml` |
| F6 | O14, O13 | A step showing `method_picker` rendered `options: null`, and no answer fits it | The verifier refuses it; only an `alternative` shows the picker | `CheckerTest.aStepCannotShowTheMethodPicker` |
| F6 | O9 | `FlowStateCodec` wrote map keys in an unspecified order | Keys are sorted | `EngineFuzzTest` checks every stored state |
| F6 (10 min) | O12 | The checker let a passkey registration screen show before any credential verified a subject; the engine then failed | The checker requires a verified subject before the screen, as for `webauthn.register` | `CheckerTest.aPasskeyRegistrationScreenNeedsAVerifiedSubjectFirst` |
| F4 | O1 | `acr_values` with a `null` element threw | Only known strings count; OIDC's space separated string is read instead of meaning no requirement | `PredicatesFuzzTest.acrValuesOfAnyShapeAreRead` |
| F7 | O1 | An unreadable stored state was a 5xx | It reads as absent (the flow ends as not found) and is logged | `StoredStateFuzzTest.corruptStatesAreNotFound` |
| F7 | O1 | State records accepted the nulls Jackson fills in for missing fields | Their constructors refuse them | same |
| F7 | O1 | A state that does not fit its pinned version (entry, frames, awaited screen and its data) failed mid run | `Engine` checks a loaded state against its version before resuming | same |
| F7 | O1, O3 | Success required a recorded verification rather than a proved `amr`; one with none reached the host's accept with a null `acr` | Success requires an `acr` | F7 corpus only |
| F11 | O1 | `/consent` for a request without a subject was a 5xx | Rejected with `no_subject` | vector `consent-without-subject` |
| F11 | O1 | A malformed host redirect was a 5xx (seed finding 3) | See seed finding 3 | `FlowApiFuzzTest.onlySafeRedirectsReachTheBrowser` |

The research for this record also found that Spring Boot 3.5.16 manages jackson-core 2.21.4, which three High advisories of 2026 affect (GHSA-649p-m576-vr99, GHSA-p6pp-m3f8-5c89, GHSA-7hhh-6rmp-j9qf, fixed in 2.21.7); the build now imports `jackson-bom` 2.21.8.

**Review.** An independent review of the change found: the consent split of ADR 0025 keyed only on `consent.record`, so a consent screen without it fell into the sign in and consent was then granted without a screen (fixed there, and the checker now refuses consent in an `authenticate` entry); engine behavior changes without conformance vectors (added: `acr-values-as-a-string`, `numbers-wider-than-64-bits`, `numbers-fractional`, `catalog-method-picker-step`, beside `consent-without-subject` and `unknown-tenant`; the stored state check is unreachable from a vector and is pinned by `StoredStateFuzzTest`); and oracles that could not fail (`realStatesResume` now requires every real state to resume; the consent after a login is checked to carry no claims of the login when it is for another subject, O16). It also confirmed that the stored state check accepts every state the engine writes, and that the stricter success and `acr_values` rules loosen nothing.

**Consequences for existing data.** The new verifier rule (`method_picker` in a step) and checker rules (a registration screen before a verified subject, consent in the sign in) refuse versions that were valid before. A stored version that breaks one no longer passes the checker at startup and its pinned flows end as not found. No tenant has been released, so nothing is migrated.

**Open gaps.** Until these close the record stays in progress:

1. Harnesses F5, F6b, F9, F10, F12, F14 and F16, and in F6 interleaved threads, host failures and expiry. F6b is where O11, O16 and O17 get tested at all.
2. Seed findings 5 (Unicode whitespace in identifiers), 8 (step up mixing accounts in `user.*`), 9 (the enrollment policy) and 12 (wrong typed Hydra strings, unverified since the official client of ADR 0021).
3. Security standard 2 is met by the reader's own duplicate key report and the alias refusal, with limits set explicitly; standard 4 checks the scheme and host of every redirect, not yet that the host is Hydra's public origin.
4. A challenge that Hydra answers with 404 while a submit finishes (after the state was saved and the writes applied) is now answered `flow_not_found`; the writes stand, as before. Moving the host load before the save is a separate change.
5. The coverage ratchet (no `jacoco` report yet), the dudect style timing test, T3, the private triage process for findings, CI actions pinned by commit SHA (the workflows use tags, as `ci.yml` does), and the OpenID Foundation suite.
6. Dictionaries per harness: none yet; the seeds carry the tokens.

### Consequences

* Good, because charter success criterion 3 gets continuous evidence, not only examples.
* Good, because every boundary in the threat model has a harness and a written oracle, and a new boundary cannot land without one.
* Good, because findings end as readable conformance vectors that any second engine must also pass.
* Bad, because Jazzer joins the test classpath, and a long running fuzz service needs CI minutes and storage.
* Bad, because harness quality decides what is found; weak generators give false confidence. Coverage reports and the seed findings below are the check on that.
* Neutral, because the runtime classpath and the production image do not change.

### Confirmation

1. `cd server && ./gradlew build` runs every `@FuzzTest` in regression mode, `EngineFuzzTest.sweep` and the named regressions, and passes (`-Xlint:all -Werror`, NullAway, Checkstyle). Passes.
2. `cd web && pnpm test` runs F15 (`web/apps/login`, fast-check) and passes. Passes.
3. A planted bug fails the build: one per oracle O3 to O8 and O14, in "Where the plan stands" above. Done for O4, O5, O6 and O14 (O3's only mutant is equivalent, see there); O7 and O8 have no planted bug yet.
4. `deploy/fuzz.sh 4m` runs every harness for four minutes and reports no findings (the pull request job runs it at 2m).
5. Each finding above and each fixed seed finding has a regression that fails before its fix and passes after.

## Pros and Cons of the Options

### A. Jazzer

* Good, because it is coverage guided, so it reaches deep branches in parsers (CBOR, YAML, JSON) that random generation does not.
* Good, because `FuzzedDataProvider` gives structured and stateful generation as well, so one tool serves F1 to F14.
* Good, because `@FuzzTest` replays seeds as ordinary JUnit 5 tests, so regressions run in every build without a fuzzing mode.
* Good, because its bug detectors (SSRF, deserialization, path traversal, regex and reflection injection) cover the Hydra client and the future hook egress.
* Bad, because it adds a test dependency with a native agent.

### B. jqwik

* Good, because its stateful testing and shrinking are the best fit for F6 and F12.
* Bad, because it is not coverage guided, so it is weak on parsers.
* Bad, because a second engine for one style of test costs more than writing action sequences over `FuzzedDataProvider`.

### C. JQF with Zest

* Good, because generator based coverage guidance suits structured inputs.
* Bad, because its JUnit 5 and Gradle integration is weaker, and its community is smaller.

### D. Hand written randomized tests

* Good, because nothing is added.
* Bad, because without coverage guidance or shrinking they find shallow bugs only, and every team rebuilds corpus and reproducer handling by hand.

### E. fast-check

* Good, because it is the standard property based tool for TypeScript, with shrinking and replayable seeds.
* Good, because `node --test` is built into Node, so only one dev dependency is added.

### F. Jazzer.js

* Good, because it is coverage guided.
* Bad, because the web apps hold no parsers or security rules (AGENTS.md rule 5); the risk there is rendering and proxying, which property tests cover.

### G. Schemathesis or RESTler

* Good, because they fuzz the deployed stack, including Spring's own request handling.
* Bad, because Faye has no OpenAPI description, and writing one only for fuzzing duplicates `schema/screen.schema.json`. Revisit if an OpenAPI description is adopted for the Admin API (M5).

### H. In process HTTP through MockMvc

* Good, because it reuses Jazzer and the Spring test setup already in `faye-app`, and runs in the normal build.
* Bad, because it bypasses the servlet container, so container limits (header size, body size) are tested separately against the compose stack.

### I. Corpora in the repo

* Good, because anyone can reproduce a nightly run from a clone.
* Bad, because corpora are large, mostly unreadable, churn on every run, and conflict with AGENTS.md rule 7.

### J. Corpora outside the repo, text seeds and reproducers inside

* Good, because the repo stays readable and every committed input is reviewable.
* Bad, because reproducing a nightly run needs the corpus artifact.

### K. Plain GitHub Actions jobs

* Good, because it is the same Gradle command developers run, with no Docker build or extra service.
* Good, because the Actions cache and private artifacts hold the corpus without a token to another repo.
* Bad, because corpus merging, pruning and coverage reports are scripts Faye owns.

### L. ClusterFuzzLite

* Good, because PR, batch, prune and coverage modes come ready made.
* Bad, because it needs a Docker build of the server for fuzzing, a corpus repo with a write token, and its repository activity is low.
* Neutral: revisit if K's scripts grow past a page.

### M. OSS-Fuzz

* Good, because it gives continuous fuzzing at scale for free.
* Bad, because it only takes open source projects with significant use, and findings go through Google's disclosure process. Apply if Faye is published and adopted.

## More Information

### Seed findings

Reading the code for this ADR, and an independent review of the draft, found these candidates. The Java behavior behind 1, 2, 4 and 5 was confirmed with a scratch program on JDK 25; the rest come from code reading. None is confirmed end to end; the named harness is how to confirm each, and each becomes a regression test before its fix.

1. **`null` in a submission gives a 5xx (B1, F11).** Fixed: `Submission` refuses a null field as `invalid_submission` (`FlowApiFuzzTest.nullInAPayloadIsRefused`). `Submission` copies the payload with `Map.copyOf`, which throws `NullPointerException` on a `null` value, so `"payload":{"x":null}` should reach it.
2. **Predicates compare numbers through `longValue()` (B3, F3).** Fixed: integers compare exactly and only integers order, as ir-spec.md always said (`PredicatesFuzzTest.numbersCompareExactlyAndOnlyIntegersOrder`). `BigInteger("18446744073709551621").longValue() == 5`, and `1.9` is not greater than `1`. IR literals are integers only, so the trigger is an env value: `client.metadata` (through `HydraFlowHost.plain`) and, later, tenant settings.
3. **Hydra's `redirect_to` is not checked (B3, F10, F11, F15).** Fixed: `FlowController.checked` passes only an absolute http(s) URL with a host and no whitespace or control characters, and answers 502 `host_error` otherwise; the protocol's `redirectTo` has that pattern; the Login UI checks again (`isNavigable`) before `window.location.assign`. On `/login` and `/consent`, `URI.create` throws on a malformed value (5xx). On submit, the value goes back as JSON `redirectTo` unchecked and `flow-screen.tsx` passes it to `window.location.assign`, so a `javascript:` value would run in the Login UI's origin. Severity depends on how far Hydra is trusted; the check belongs in Faye regardless (Security standard 4).
4. **An unknown `metadata.tenant` gives a 5xx (B3, F11).** Fixed: rejected as `flow_unavailable` (vector `unknown-tenant`). `Engine.current` throws `IllegalStateException` for a tenant with no published flow.
5. **Unicode whitespace survives normalization (B1, F5).** `trim()` leaves `U+00A0` and `isBlank()` is false for it, so `"\u00A0alice@example.com"` is a distinct identifier. It matters for sign up and account linking.
6. **The Login UI proxy widens what the server accepts (B1, F15).** Fixed: the proxy forwards only `application/json` of at most 64 KiB, the server's `BodyLimit` caps the screen API at the same size, and Spring refuses repeated JSON keys (`FlowApiFuzzTest.repeatedKeysAndHugeBodiesAreRefused`). `app/api/flows/[flowId]/submit/route.ts` forwards any body of any size and always sends `content-type: application/json`, so a cross site `text/plain` POST arrives at Spring as JSON.
7. **A losing submit keeps its credential side effects (B1, F6b, F12).** Addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): writes are effects applied after the compare and set, replay marks are guards owned by the flow (`EffectsTest`, `effect-lost-race`). Handlers run inside `Engine.run` before `store.replace`; `totp.verify`, `totp.enroll`, `webauthn.register` and `webauthn.assert` write the `UserStore` even when the submit then gets `409`.
8. **Step up can mix accounts in `user.*` (B1, F6b).** On step up the subject comes from Hydra, but `identity.resolve` resolves whatever identifier is typed and `Env.withUser` merges it. Consent then takes `email`, `email_verified` and `name` from `user.*`, so the ID token could describe another account. The same applies to a `retry` around identify.
9. **Enrollment and the `flowId` (B1, F6b).** The bearer part is addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): a flow is bound to the browser's cookie (`binder-mismatch`, `FlowApiTest`); the enrollment policy question remains. `GET /api/flows/{id}` needs only the id and returns the nonce and, on `otp_enroll`, the TOTP secret. In `passkey-totp.yaml`'s enroll branch, someone holding only the password enrolls a second factor of their own and reaches `faye:2fa`. That may be the intended policy; O17 makes it an explicit decision rather than an accident.
10. **`IrCodec` truncates `retry` attempts (B5, F1).** Addressed: the schema bounds attempts to 1 to 10 and `IrCodecTest` refuses `4294967297`. It reads `intValue()` without `canConvertToInt()`, so `["retry", 4294967297, …]` is accepted as 1.
11. **YAML limit errors escape the compiler (B5, F2).** Addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): `YamlReader` sets its limits and reports a plain `YAMLException` as a diagnostic. `FlowCompiler` catches only `MarkedYAMLException`; SnakeYAML's alias, nesting and code point limits throw a plain `YAMLException`, which is not a `DslException`.
12. **Wrong typed Hydra strings give a `NullPointerException` (B3, F10).** Unverified since ADR 0021 replaced the hand written client with Ory's generated one; F10 is how to check. A numeric `subject`, `client_name` or `login_hint` passes the `asText()` non empty check, `textValue()` returns `null`, and `HostRequest`'s copy throws.

### Unverified claims

* The research behind the tool choice is summarized in [Tool research](#tool-research); version and license facts there were checked on 2026-10-31 and must be rechecked when the dependency is added.
* The T0 budget is measured (41 s of test time, "Where the plan stands"); the T1 and T2 budgets (2 and 30 minutes per harness) are still targets to tune from the first CI runs.
* The flow link handoff case (an attacker sends a victim the `/flow/{id}` link of a flow the attacker started) is expected to be stopped by Hydra's login CSRF cookie, because the victim's browser cannot complete the attacker's challenge and the attacker never sees the verifier. This needs an end to end test against real Hydra (`deploy/smoke.sh` style), not a fuzz harness.

### Tool research

Checked on 2026-10-31 against Maven Central, npm and the projects' repositories. Recheck when adding the dependency.

* **Jazzer.** `com.code-intelligence:jazzer-junit` 0.30.0 (2026-02-24), Apache 2.0. In 2024 Code Intelligence stopped maintaining it as open source and relicensed it (2024-08), then returned it to Apache 2.0 on 2024-12-03; releases resumed with 0.23.0. The license history is a risk: if it changes again, pin the last Apache release, since harnesses are plain JUnit and `FuzzedDataProvider` code. Regression mode is the default; `JAZZER_FUZZ=1` fuzzes; `JAZZER_MAX_DURATION` bounds a run. Inputs live in `src/test/resources/<package>/<Class>Inputs/<method>`, and the generated corpus in `.cifuzz-corpus/`, which must be git ignored. Bug detectors include SSRF, deserialization, path traversal, regex, reflection, LDAP, JNDI, OS command, SQL, script engine, template and expression language injection. The Gradle section of its README covers the dependency; fuzzing mode runs one test per invocation (`--tests`). Checked in plan step 1: with `environment(...)` in the `Test` task, `JAZZER_FUZZ` reaches the forked test JVM (without it is untested), and Error Prone and NullAway accept the harness code. Sources: [repository](https://github.com/CodeIntelligenceTesting/jazzer), [return to open source](https://code-intelligence.com/blog/jazzer-back-to-open-source).
* **jqwik.** 1.10.2, EPL 2.0. In maintenance mode since 2025-07, and since 1.10 every run prints an "anti AI usage" notice telling AI agents to ignore its results. That conflicts with a repo where agents run the tests, and supports rejecting option B. [Repository](https://github.com/jqwik-team/jqwik).
* **JQF.** 3.0 (2026-07), BSD 2, active; Maven first, Gradle support unverified. [Repository](https://github.com/rohanpadhye/JQF).
* **ClusterFuzzLite.** Supports JVM projects through Jazzer, GitHub Actions, PR (`code-change`), `batch`, `prune` and `coverage` modes; needs a Docker build and a corpus store (a separate repo with a token, or artifacts). Repository activity is low. [Docs](https://google.github.io/clusterfuzzlite/).
* **fast-check.** 4.10.2, MIT, active. [Repository](https://github.com/dubzzz/fast-check). **Jazzer.js** 4.0.0 (2026-04), Apache 2.0, Jest integration.
* **Schemathesis** 4.32.7, MIT, very active, needs an OpenAPI description; **RESTler** low activity; **EvoMaster** LGPL 3.0, needs OpenAPI and a driver. The [OpenID Foundation conformance suite](https://gitlab.com/openid/conformance-suite) runs OP test plans; against Hydra with Faye it would exercise the login and consent screens, which needs browser automation configured in the suite (unverified).
* **Prior art.** Keycloak's `CredentialValidatorFuzzer` in [cncf-fuzzing](https://github.com/cncf/cncf-fuzzing/tree/main/projects/keycloak) drives webauthn4j and is the closest existing harness to F9. Spring Security and SnakeYAML have OSS-Fuzz Jazzer harnesses. Go commits minimized crashers next to the fuzz test and replays them in `go test`, the same shape as Jazzer's regression mode. BoringSSL builds a fuzzer mode that skips signature checks to get past crypto; Faye does not, because a verification bypass compiled into the server is itself a risk. F9 signs valid inputs with the emulator instead.
* **Parser limits.** Jackson `StreamReadConstraints` defaults (2.15+): nesting 1000, number length 1000, string length 20,000,000, name length 50,000, and **no limit** on document length or token count, so Faye must set those. SnakeYAML `LoaderOptions` defaults: 50 aliases, nesting 50, 3 MB code points, and `allowDuplicateKeys` **true** (`FlowCompiler` reports duplicates itself today).
* **Dependency advisories (to verify).** The research found 2026 jackson-core advisories that bypass `StreamReadConstraints` (for example GHSA-2m67-wjpj-xhg9 on document length, CVE-2026-29062 on nesting depth), reported fixed in 2.21.7. Spring Boot 3.5.16 resolves jackson-core 2.21.4 here (`./gradlew :faye-app:dependencies`). Check the advisories at <https://github.com/FasterXML/jackson-core/security/advisories>, and upgrade if they apply, independently of this ADR. Resolved versions today: snakeyaml 2.4, webauthn4j-core 0.30.3.RELEASE.
