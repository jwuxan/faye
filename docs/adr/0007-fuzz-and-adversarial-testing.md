---
status: proposed
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
| B4 | Redis flow state | Faye, but across deploys and on a shared Redis | `FlowState` JSON, revision, TTL | Unreadable or inconsistent state fails closed; a state from an older deploy either resumes correctly or ends the flow |
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

| # | Harness | Target | Input | Oracles | Lands |
|---|---|---|---|---|---|
| F1 | `IrCodecFuzzTest` | `IrCodec.readFlow` | UTF-8 bytes as JSON | O1, O2, O9, O10 (schema) | Now |
| F2 | `FlowCompilerFuzzTest` | `FlowCompiler.compile` | UTF-8 bytes as YAML; dictionary of DSL keys, screen and handler names | O1 (only `DslException`, every diagnostic inside the source), O2, O9 | Now |
| F3 | `PredicatesFuzzTest` | `Predicates.test`, `Env.lookup` | Generated predicate tree and an `Env` with every JSON type, big and fractional numbers, deep maps | O1, O2, and agreement with a reference evaluator written from [dsl-and-ir.md](../roadmap/dsl-and-ir.md) | Now |
| F4 | `AcrFuzzTest` | `Acr.of`, `Acr.satisfies` | Generated `amr` lists and `acr_values` of any JSON type | O1; monotonicity (adding an `amr` never lowers `acr`; a stronger `acr` satisfies whatever a weaker one does) | Now |
| F5 | `ScreenSubmissionFuzzTest` | `ScreenType.accepts`, `prepare`, `render`, then every handler | Screen type and payload of any JSON shape, with nulls, wrong types and boundary lengths | O1, O8, O14 | Now |
| F6 | `EngineStateMachineFuzzTest` | `Engine` with `InMemoryFlowStore`, a recording `MockFlowHost`, and fake handlers that script a subject per outcome (a conformance format change: today a vector binds one `subject`) | A flow (conformance IR or generated), scripted handler outcomes, and a sequence of actions: start the same challenge again, view, submit valid, submit invalid, submit with a stale or foreign nonce, two interleaved submits, reload through `FlowStateCodec`, new `Engine` instance (restart), host failure, expire | O1 to O8, O13, O14 | Now |
| F6b | `IdentityFlowFuzzTest` | `Engine` with the real `IdentityHandlers`, `InMemoryUserStore` and the shipped flows | Known and unknown identifiers, wrong and right credentials, step up with a Hydra subject, concurrent submits | O6 (`UserStore` included), O11, O16, O17 | Now |
| F7 | `FlowStateCodecFuzzTest` | `FlowStateCodec.read`, then `Engine.view` and `submit` on the result | Mutated serialized states: unknown frame tags, paths outside the IR, missing `awaiting` fields, foreign `versionHash`, revision edits | O1 (only "unreadable flow state" or `FlowException`), O3, O9 | Now |
| F8 | `TotpBase32FuzzTest` | `Base32`, `Totp.match`, `totp.verify`, `totp.enroll` | Secrets, codes, epoch seconds across step boundaries and extremes | O1, O10 (RFC 6238), replay refused for any step at or below the last accepted | Now |
| F9 | `WebAuthnFuzzTest` | `webauthn.assert`, `webauthn.register`, `identity.resolve` with a passkey | Valid ceremonies from the `webauthn4j-test` emulator, then structure aware mutation of every field (decoded, mutated, re-encoded) | O1, O3: no `Ok` unless the signature verifies for the stored key, the challenge is the screen's, the origin and RP id match, and the user handle names the account | Now |
| F10 | `HydraFlowHostFuzzTest` | `HydraFlowHost` against a JDK `com.sun.net.httpserver` fake | Status, headers, body (any JSON, non JSON, huge, slow), `redirect_to`, `context`, `client.metadata` | O1 (only the new host failure exception), O2, Jazzer's SSRF detector: the challenge never changes the admin URL's host or path | Now |
| F11 | `FlowApiFuzzTest` | `FlowController` through `MockMvc`, Hydra faked | Method, path, content type, body bytes (also JSON with duplicate keys and deep nesting), challenge, `flowId`, `nonce` | O1 (declared statuses only, no stack trace or internal message in any body), O6, O14 | Now |
| F12 | `RedisFlowStoreConcurrencyTest` | `RedisFlowStore` | Random interleavings of `create`, `replace`, `load` from several threads | O6, O7: the history is linearizable against a model store | Now, CI Redis |
| F13 | `CheckerSoundnessFuzzTest` | `faye-check` (the module exists) | Generated well formed IR, every handler outcome script up to a bound | O10, O12 | Now |
| F14 | `HookContainmentFuzzTest` | `HookRuntime` | Generated JavaScript return values (getters, proxies, cycles, `__proto__` keys, huge strings, promises that never settle, throwing `toString`) and a curated sandbox escape corpus | O1, O2, O15 | M4 |
| F15 | `screen.fuzz.test.ts` | Login UI screen components and the submit proxy route | fast-check arbitraries for every screen in the generated contract, with hostile strings | Rendering never throws; no markup or `javascript:` URL from data, including the `redirectTo` the client navigates to; the proxy only ever calls `/api/flows/{encoded id}/submit` and does not widen what the server accepts (content type, size) | Now |
| F16 | Admin API and editor harnesses | Admin API, IR edits, publish and rollback | As F1, F11 and F13, plus authorization across tenants | O1, O6, tenant isolation | M5 |

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
4. Harnesses never talk to a real Hydra, Redis or network except F12 against CI's Redis service.
5. No fuzzing only code paths in production code: no build flag that skips signature, MAC or nonce checks. Harnesses produce valid proofs instead.

### Plan

1. Add Jazzer to `libs.versions.toml` and as `testImplementation` in the modules that own harnesses. Confirm `@FuzzTest` regression mode runs under Gradle's JUnit platform, and that `-Werror`, Error Prone and NullAway accept the harness code.
2. Land F1 to F11 with seeds and dictionaries, fixing the findings below first so the build is green with the oracles at full strength.
3. Land the parser and limit changes in "Security standard", each with its harness.
4. Add `fast-check` as a dev dependency of `web/apps/login` and F15 under `node --test`.
5. Add the T1 and T2 CI jobs and the private triage process; F12 joins the existing `server` job.
6. Nightly, once the compose stack is stable: the OpenID Foundation conformance suite against Hydra with Faye, as an end to end complement to fuzzing.
7. F13 shares the path enumerator with the generated conformance vectors. M4: F14 before any hook runs outside tests. M5: F16.

### Consequences

* Good, because charter success criterion 3 gets continuous evidence, not only examples.
* Good, because every boundary in the threat model has a harness and a written oracle, and a new boundary cannot land without one.
* Good, because findings end as readable conformance vectors that any second engine must also pass.
* Bad, because Jazzer joins the test classpath, and a long running fuzz service needs CI minutes and storage.
* Bad, because harness quality decides what is found; weak generators give false confidence. Coverage reports and the seed findings below are the check on that.
* Neutral, because the runtime classpath and the production image do not change.

### Confirmation

1. `cd server && ./gradlew build` runs every `@FuzzTest` in regression mode and passes (`-Xlint:all -Werror`, NullAway, Checkstyle).
2. `cd web && pnpm test` (new script, `node --test`) runs F15 and passes.
3. The T1 job fails on a planted bug: remove the `checkSubject` call in `Engine` on a throwaway branch and F6, with per outcome subjects, must report O5 within the PR budget. Repeat for one planted bug per oracle O3 to O8 before declaring the setup done.
4. Each seed finding below has a regression test that fails before its fix and passes after.

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

1. **`null` in a submission gives a 5xx (B1, F11).** `Submission` copies the payload with `Map.copyOf`, which throws `NullPointerException` on a `null` value, so `"payload":{"x":null}` should reach it.
2. **Predicates compare numbers through `longValue()` (B3, F3).** `BigInteger("18446744073709551621").longValue() == 5`, and `1.9` is not greater than `1`. IR literals are integers only, so the trigger is an env value: `client.metadata` (through `HydraFlowHost.plain`) and, later, tenant settings.
3. **Hydra's `redirect_to` is not checked (B3, F10, F11, F15).** On `/login` and `/consent`, `URI.create` throws on a malformed value (5xx). On submit, the value goes back as JSON `redirectTo` unchecked and `flow-screen.tsx` passes it to `window.location.assign`, so a `javascript:` value would run in the Login UI's origin. Severity depends on how far Hydra is trusted; the check belongs in Faye regardless (Security standard 4).
4. **An unknown `metadata.tenant` gives a 5xx (B3, F11).** `Engine.current` throws `IllegalStateException` for a tenant with no published flow.
5. **Unicode whitespace survives normalization (B1, F5).** `trim()` leaves `U+00A0` and `isBlank()` is false for it, so `"\u00A0alice@example.com"` is a distinct identifier. It matters for sign up and account linking.
6. **The Login UI proxy widens what the server accepts (B1, F15).** `app/api/flows/[flowId]/submit/route.ts` forwards any body of any size and always sends `content-type: application/json`, so a cross site `text/plain` POST arrives at Spring as JSON.
7. **A losing submit keeps its credential side effects (B1, F6b, F12).** Addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): writes are effects applied after the compare and set, replay marks are guards owned by the flow (`EffectsTest`, `effect-lost-race`). Handlers run inside `Engine.run` before `store.replace`; `totp.verify`, `totp.enroll`, `webauthn.register` and `webauthn.assert` write the `UserStore` even when the submit then gets `409`.
8. **Step up can mix accounts in `user.*` (B1, F6b).** On step up the subject comes from Hydra, but `identity.resolve` resolves whatever identifier is typed and `Env.withUser` merges it. Consent then takes `email`, `email_verified` and `name` from `user.*`, so the ID token could describe another account. The same applies to a `retry` around identify.
9. **Enrollment and the `flowId` (B1, F6b).** The bearer part is addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): a flow is bound to the browser's cookie (`binder-mismatch`, `FlowApiTest`); the enrollment policy question remains. `GET /api/flows/{id}` needs only the id and returns the nonce and, on `otp_enroll`, the TOTP secret. In `passkey-totp.yaml`'s enroll branch, someone holding only the password enrolls a second factor of their own and reaches `faye:2fa`. That may be the intended policy; O17 makes it an explicit decision rather than an accident.
10. **`IrCodec` truncates `retry` attempts (B5, F1).** Addressed: the schema bounds attempts to 1 to 10 and `IrCodecTest` refuses `4294967297`. It reads `intValue()` without `canConvertToInt()`, so `["retry", 4294967297, …]` is accepted as 1.
11. **YAML limit errors escape the compiler (B5, F2).** Addressed by [ADR 0024](0024-core-ir-as-a-narrow-waist.md): `YamlReader` sets its limits and reports a plain `YAMLException` as a diagnostic. `FlowCompiler` catches only `MarkedYAMLException`; SnakeYAML's alias, nesting and code point limits throw a plain `YAMLException`, which is not a `DslException`.
12. **Wrong typed Hydra strings give a `NullPointerException` (B3, F10).** A numeric `subject`, `client_name` or `login_hint` passes the `asText()` non empty check, `textValue()` returns `null`, and `HostRequest`'s copy throws.

### Unverified claims

* The research behind the tool choice is summarized in [Tool research](#tool-research); version and license facts there were checked on 2026-10-31 and must be rechecked when the dependency is added.
* The T0 and T1 budgets are targets, not measurements. Measure after plan step 2 and record the numbers here.
* The flow link handoff case (an attacker sends a victim the `/flow/{id}` link of a flow the attacker started) is expected to be stopped by Hydra's login CSRF cookie, because the victim's browser cannot complete the attacker's challenge and the attacker never sees the verifier. This needs an end to end test against real Hydra (`deploy/smoke.sh` style), not a fuzz harness.

### Tool research

Checked on 2026-10-31 against Maven Central, npm and the projects' repositories. Recheck when adding the dependency.

* **Jazzer.** `com.code-intelligence:jazzer-junit` 0.30.0 (2026-02-24), Apache 2.0. In 2024 Code Intelligence stopped maintaining it as open source and relicensed it (2024-08), then returned it to Apache 2.0 on 2024-12-03; releases resumed with 0.23.0. The license history is a risk: if it changes again, pin the last Apache release, since harnesses are plain JUnit and `FuzzedDataProvider` code. Regression mode is the default; `JAZZER_FUZZ=1` fuzzes; `JAZZER_MAX_DURATION` bounds a run. Inputs live in `src/test/resources/<package>/<Class>Inputs/<method>`, and the generated corpus in `.cifuzz-corpus/`, which must be git ignored. Bug detectors include SSRF, deserialization, path traversal, regex, reflection, LDAP, JNDI, OS command, SQL, script engine, template and expression language injection. The Gradle section of its README covers the dependency; fuzzing mode runs one test per invocation (`--tests`). Unverified: whether `JAZZER_FUZZ` reaches Gradle's forked test JVM without `environment(...)` in the `Test` task, and whether Error Prone and NullAway accept harness code. Plan step 1 checks both. Sources: [repository](https://github.com/CodeIntelligenceTesting/jazzer), [return to open source](https://code-intelligence.com/blog/jazzer-back-to-open-source).
* **jqwik.** 1.10.2, EPL 2.0. In maintenance mode since 2025-07, and since 1.10 every run prints an "anti AI usage" notice telling AI agents to ignore its results. That conflicts with a repo where agents run the tests, and supports rejecting option B. [Repository](https://github.com/jqwik-team/jqwik).
* **JQF.** 3.0 (2026-07), BSD 2, active; Maven first, Gradle support unverified. [Repository](https://github.com/rohanpadhye/JQF).
* **ClusterFuzzLite.** Supports JVM projects through Jazzer, GitHub Actions, PR (`code-change`), `batch`, `prune` and `coverage` modes; needs a Docker build and a corpus store (a separate repo with a token, or artifacts). Repository activity is low. [Docs](https://google.github.io/clusterfuzzlite/).
* **fast-check.** 4.10.2, MIT, active. [Repository](https://github.com/dubzzz/fast-check). **Jazzer.js** 4.0.0 (2026-04), Apache 2.0, Jest integration.
* **Schemathesis** 4.32.7, MIT, very active, needs an OpenAPI description; **RESTler** low activity; **EvoMaster** LGPL 3.0, needs OpenAPI and a driver. The [OpenID Foundation conformance suite](https://gitlab.com/openid/conformance-suite) runs OP test plans; against Hydra with Faye it would exercise the login and consent screens, which needs browser automation configured in the suite (unverified).
* **Prior art.** Keycloak's `CredentialValidatorFuzzer` in [cncf-fuzzing](https://github.com/cncf/cncf-fuzzing/tree/main/projects/keycloak) drives webauthn4j and is the closest existing harness to F9. Spring Security and SnakeYAML have OSS-Fuzz Jazzer harnesses. Go commits minimized crashers next to the fuzz test and replays them in `go test`, the same shape as Jazzer's regression mode. BoringSSL builds a fuzzer mode that skips signature checks to get past crypto; Faye does not, because a verification bypass compiled into the server is itself a risk. F9 signs valid inputs with the emulator instead.
* **Parser limits.** Jackson `StreamReadConstraints` defaults (2.15+): nesting 1000, number length 1000, string length 20,000,000, name length 50,000, and **no limit** on document length or token count, so Faye must set those. SnakeYAML `LoaderOptions` defaults: 50 aliases, nesting 50, 3 MB code points, and `allowDuplicateKeys` **true** (`FlowCompiler` reports duplicates itself today).
* **Dependency advisories (to verify).** The research found 2026 jackson-core advisories that bypass `StreamReadConstraints` (for example GHSA-2m67-wjpj-xhg9 on document length, CVE-2026-29062 on nesting depth), reported fixed in 2.21.7. Spring Boot 3.5.16 resolves jackson-core 2.21.4 here (`./gradlew :faye-app:dependencies`). Check the advisories at <https://github.com/FasterXML/jackson-core/security/advisories>, and upgrade if they apply, independently of this ADR. Resolved versions today: snakeyaml 2.4, webauthn4j-core 0.30.3.RELEASE.
