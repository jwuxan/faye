---
status: in progress
date: 2026-10-31
decision-makers: Josh Wu
---

# Host neutral boundaries for embedding: host parameters, credential issuers and flow outcomes

## Context and Problem Statement

The goal for this quarter is to put Faye behind existing systems:
- **Downstream**, the Login UI is rendered and deployed separately (the redesign).
- **Upstream**, credentials are verified by an existing service's authenticate API.
- **The engine** only runs flows.

[ADR 0024](0024-core-ir-as-a-narrow-waist.md) made the IR independent of the host (`host.*` replaced `hydra.*`) and made screens and handlers catalogs. On `main` at 84bc4ff, three leaks remain between the engine and the systems around it:

| # | Leak | Where |
|---|---|---|
| L1 | **The host's parameters are an untyped map.** `HostRequest.params` is a `Map<String, Object>` that each `FlowHost` fills as it likes. The engine and the built-in screens read protocol-named keys from it (`login_hint` in the identifier screen, `requested_scope` in the consent screen), and no contract says which keys a host must, may or must not provide. A second host can break a flow silently: a missing key reads as null, and an extra key becomes readable by flows and hooks as `host.*`. | `HostRequest`, `Env.host()`, `BuiltinScreen.render`, `ir-spec.md` (Environment) |
| L2 | **The engine issues credential material.** `BuiltinScreen.prepare` generates the WebAuthn challenge (identifier and passkey screens) and the TOTP enrollment secret. `render` hard-codes the otpauth issuer `"Faye"` and the passkey algorithms (`PASSKEY_ALGORITHMS`). When an upstream service owns credentials, the challenge and secret must come from that service, which verifies the answer; today only Faye's own `faye-identity` can verify what the engine issued. | `BuiltinScreen`, `ScreenSpec.prepare`, `IdentityHandlers` (reads `Submission.context()`) |
| L3 | **The flow outcome is shaped for a redirecting authorization server.** `FlowHost.accept`/`reject` return `Redirect`. `EngineResponse` is `ScreenView` or `Redirect`. The entry points take `login_challenge` and `consent_challenge`. A host that is an existing service (it starts a flow by API and turns the outcome into its own session) cannot be expressed without a fake redirect, and the embedding API (`Engine.start`/`view`/`submit` with a binder) has no written contract. | `FlowHost`, `EngineResponse`, `Redirect`, `FlowController`, `Engine` |

How should these three boundaries look so that a host other than Hydra, a separately deployed Login UI, and an upstream credential service can each be plugged in without changing the engine, the IR or the flows?

## Decision Drivers

* Charter principle 3 (host neutral) and success criterion 4 ("the same flow runs unchanged with a second authorization server").
* Charter principle 2 (closed extension): only built-in, reviewed handlers verify credentials and bind a subject. An upstream adapter is such a handler module, and stays reviewed.
* Charter non-goal 2: Faye issues no tokens and owns no sessions. An outcome goes to the host, and the host turns it into a session.
* Charter principle 1: issued material (challenges, secrets) never becomes readable by flows or hooks, and a missing or failed upstream fails closed.
* ADR 0024 invariant 4: the engine and the checker depend only on contracts. No IR format bump and no screen protocol change unless one is unavoidable.

## Considered Options

1. Host parameters (L1)
   * A. Keep the free map (status quo).
   * B. A closed, typed `HostParams` record whose fields are the `host.*` namespace, documented in `ir-spec.md`. Every `FlowHost` maps its protocol into it.
   * C. A free map plus a per-host schema that each adapter publishes.
2. Who issues credential material (L2)
   * A. The engine's screens (status quo).
   * B. A `CredentialIssuer` per screen type, supplied by the credential module and registered beside its handlers. The screen spec asks the issuer for the data and the engine only stores and forwards it.
   * C. An explicit `(call webauthn.challenge)` before the screen in the IR, writing the challenge into the environment.
3. Flow outcomes (L3)
   * A. Keep `Redirect` as the only completion (status quo).
   * B. A sealed `Completion` returned by `FlowHost` (`Redirect(url)` or `Handoff(result)`), plus a written embedding contract for `Engine`.
   * C. Let `Engine.submit` return the `Authenticated`/`Consented` outcome itself and remove `accept`/`reject` from `FlowHost`.

## Decision Outcome

1B, 2B, 3B. None of them changes the IR grammar or the IR format. The screen protocol gains one additive response type, `HandoffResponse` (see Flow outcomes); no screen type changes.

### Host parameters (1B)

```java
record HostParams(@Nullable String subject, boolean skip, List<String> acrValues,
                  List<String> requestedScope, List<String> requestedAccessTokenAudience,
                  @Nullable String loginHint, List<String> uiLocales,
                  Map<String, Object> context) {}
```

* `HostRequest.params` becomes `HostParams`.
* The `host.*` namespace is exactly these fields under their wire names (`subject`, `skip`, `acr_values`, `requested_scope`, `requested_access_token_audience`, `login_hint`, `ui_locales`, `context`). `ir-spec.md` lists them as the host contract, with what each means and which host kinds supply it.
* The checker (L2, ADR 0024 8B) refuses a predicate path `host.<x>` where `<x>` is not a field. The refusal happens at publish and preview, not in the L1 verifier on load. Today the path pattern accepts any `host.` suffix.
* A stored format 1 version therefore still loads and runs unchanged. An unknown `host.<x>` reads as null, exactly as it does today with the Hydra host, which never sets such a key. So no format bump is needed, and only a new publish is held to the closed list.
* `context` is the one free-form field: the host's own data, carried to the outcome and readable as `host.context.*`.
* Adding a field is a change to this contract, made through an ADR.
* Each `FlowHost` maps its protocol into `HostParams`. For example, Hydra's `oidc_context.login_hint` becomes `loginHint`. An adapter cannot add a key.
* `HostParams.toEnv()` is what the engine keeps as `host.*` in the flow state, so the state format does not change. Absent strings (`subject`, `login_hint`) are left out and read as null; `skip`, the lists and `context` are always present, a list possibly empty. A predicate's truthiness is the same either way; `exists host.<list>` is now always true, and no shipped flow or vector uses it.
* `HostParams.fromMap` reads the wire form (conformance vectors, tests) and refuses a key that is not a field. A list field may also be given as OIDC's space separated string (`acr_values: "faye:2fa faye:1fa"`), so a host that relays the string does not drop the requirement ([ADR 0007](0007-fuzz-and-adversarial-testing.md), F4).
* 1C was rejected because each host would then define its own namespace, which is the leak itself: a flow could still depend on one host's keys.

### Credential issuers (2B)

```java
interface CredentialIssuer {
    String screen();                       // e.g. "passkey", "otp_enroll", "identifier"
    Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous);
    Map<String, Object> present(Env env, Map<String, Object> issued); // fields the screen shows
}
```

* The credential module supplies its issuers beside its handlers (`IdentityIssuers.create()` beside `IdentityHandlers.create()`); the embedder registers both, and nothing but review ties the pair together. `faye-identity` provides today's behavior: random WebAuthn challenges, a TOTP secret that survives a retry, and the otpauth URI. `FixedIssuers` (fixed challenge and secret) exists only as test fixtures of `faye-screens` and never ships.
* The spec keeps the non-credential part of each screen; the engine adds what the issuer issues to the stored data and what it presents to the payload.
* The tenant's display name replaces the hard-coded issuer `"Faye"` in the otpauth label. `TenantPolicy` gains `name`, read as `tenant.name`: the tenant's branding name, else its id. Without one (a tenant that is not configured), the label is the client's name, else `Sign in`.
* The passkey algorithms move to the issuer. `BuiltinScreen.PASSKEY_ALGORITHMS` leaves the engine (now `IdentityIssuers.PASSKEY_ALGORITHMS`), and the Login UI still receives them in the screen, as in [ADR 0022](0022-passkey-ceremonies-with-simplewebauthn.md).
* Issued data is stored, as today, only as the waiting screen's data (`FlowState.Awaiting.data`). It reaches the consuming handler only as `Submission.context()`. It is never in `Env`, so predicates and hooks cannot read it.
* The issuer and the handler that consumes its output come from the same module.
* A `ScreenSpec` declares whether it needs an issuer: `identifier` (only for passkey autofill), `passkey` and `otp_enroll` do, while `password`, `otp`, `method_picker`, `consent` and `error` do not.
* At startup, the `ScreenRegistry` refuses a screen that declares an issuer and has none registered, rather than failing at the first flow. It also refuses an issuer registered for a screen that does not declare one.
* The engine merges what the spec prepares with what the issuer issues into the one stored map, and what the spec renders with what the issuer presents into the one payload. A key both write is a bug and throws.
* A failing `issue` fails the step with `issuer_error`, like a handler failure: an enclosing `retry` reruns it, else the entry fails and the host rejects. `present` runs on every view, so it must be local and deterministic. One behavior changes with it: a passkey registration reached without a signed in subject (which the checker already refuses at publish) used to throw a server error, and now fails the step closed with `issuer_error`.
* `method_picker` cannot declare an issuer: the engine fills it with the options of the `choose`.
* The engine's check that a stored state fits its version (ADR 0007, F7) asks the screen whether a waiting instance can resume (`ScreenSpec.resumable(env, data)`) instead of knowing screens itself: the passkey screen refuses a registration with no signed in subject.
* An issuer that calls an upstream service holds its own deadline and throws when it passes; the engine sets none, so a slow upstream slows the screen and a dead one fails it closed.
* Requirements for an upstream issuer:
  * The upstream service creates the challenge or secret.
  * The issuer returns an opaque reference the upstream can verify (for example `{ "challenge": ..., "upstream_ref": ... }`).
  * A failure or timeout raises, and the engine fails the step closed.
* 2C was rejected:
  * It puts challenges and secrets in `Env`, where hooks and predicates can read them.
  * It makes every author plumb a call before each credential screen.
  * It needs a new environment namespace in the IR.

### Flow outcomes (3B)

```java
sealed interface Completion permits Redirect, Handoff {}
record Handoff(Map<String, Object> result) implements Completion {}
interface FlowHost {
    HostRequest load(HostKind kind, String ref);
    Completion accept(HostRequest r, Authenticated outcome);
    Completion accept(HostRequest r, Consented outcome);
    Completion reject(HostRequest r, String error, String description);
}
```

`EngineResponse` permits `ScreenView` and `Completion`:
- A redirecting host (Hydra) returns `Redirect`, unchanged.
- An embedding host returns `Handoff`. Its caller receives the result: for example, a one-time code the existing system exchanges server to server for the outcome, so `amr`/`acr` never travel through the browser as claims.
- The HTTP layer of `faye-app` maps `Redirect` to `{redirectTo}` as now and `Handoff` to `{result}`, a new `HandoffResponse` of the screen protocol (additive: a Login UI that predates it never receives one from the Hydra host). The Login UI posts `{type: "faye:handoff", result}` to the page that opened it in a popup, only to the origin `FAYE_EMBED_ORIGIN` names; unset, or not exactly one origin (`*` included), it posts nothing, so a one time code never reaches an unknown origin. The Login UI refuses to be framed (`frame-ancestors 'none'`, `X-Frame-Options: DENY`): framed sign in screens invite clickjacking, and the flow's binder cookie is `SameSite=Lax`, so a cross site frame could not run the flow anyway.
- Faye's own `/login` and `/consent` routes are the Hydra host's browser navigations; a `Handoff` there is refused rather than rendered, since its result may be a one time code. An embedding host starts flows through its own route or `Engine.start`.
- `Completion` extends `EngineResponse`, so `start` and `submit` return a `Completion` directly; which variant is the host's fixed choice, never the browser's.
- `challenge` is renamed `ref`. It is the host's opaque id for the request (a Hydra challenge, or an id the existing system created).

The embedding contract is in `docs/roadmap/engine.md` (Embedding):
- `start(kind, ref, request, binder)`, `view(flowId, binder)` and `submit(flowId, binder, nonce, submission)`.
- What each returns.
- The binder rules from ADR 0024 10B: the caller creates the binder, keeps it from the page's scripts, and presents it on every call.
- Which `FlowException` codes are user errors and which are fatal.

3C was rejected because it would move `accept` and `reject` out of the engine. The engine would then no longer save before it accepts (only the submit that wins the revision race may accept), and every host would have to re-implement that ordering.

### The engine as an embeddable core

The three boundaries are only useful if nothing else ties the engine to Hydra or to the Login UI. So, in the same change:

* **Built in screens leave the engine.** The screens of `schema/screen.schema.json` move from `faye-engine` to a new module, `faye-screens` (`BuiltinScreen`, `BuiltinScreen.registry(issuers)`). The engine keeps the screen SPI (`ScreenSpec`, `ScreenRegistry`, `CredentialIssuer`) and knows one screen name, `method_picker`, because `choose` waits on it (ir-spec.md, choose). The verifier refuses a `choose` of several options when no `method_picker` is registered, instead of the registry requiring one, so an embedder with no choice in its flows registers only its own screens. `ScreenRegistry.builtIn()` is removed: `Checker`, `Publisher` and `Engine` take their screens explicitly.
* **Registration is explicit.** `Engine.builder()` replaces the telescoping constructors: `versions`, `handlers`, `screens`, `store` and `host` are required, tenants, hooks and previews default to none. Nothing is discovered (no `ServiceLoader`), so the trusted set of handlers, screens and issuers is the list the embedder writes, and every catalog is validated when built (charter principle 2).
* **The DSL knows no host.** The compiler's `hydra.` alias becomes a caller supplied namespace alias (`FlowCompiler.compileWithHooks(..., aliases)`). The app passes `HydraFlowHost.DSL_PATH_ALIASES` (`hydra` to `host`), so tenant YAML compiles as before; the compiler alone refuses `hydra.skip` as a grammar error.
* **TOTP encoding leaves the engine.** `Base32` moves to `faye-identity`, which is the only module that uses it.

Each choice follows what the SPI survey (More Information) found in mature identity engines: the module that verifies a credential also issues its challenge (Keycloak's WebAuthn authenticator, Kratos strategies, unlike Keycloak's TOTP secret, which the theme generates and the client sends back); issued material stays server side and out of hooks (Kratos `InternalContext`, PingAM transient state); and the UI only receives typed view models (Kratos `ui.nodes`).

### The outcome and the client: the host's, not the engine's

Faye is the IdP's frontend: it completes the sign in and reports what was proven, and the host sets up the session. The token exchange, and every claim in it, happens outside the engine (charter non-goal 2). Two leaks of that boundary are closed in this same change, by the decision maker's call:

* **The engine computes no claims.** `Consented` carries `user`, what the login's handlers learned about the person, in place of `idTokenClaims`. `HydraFlowHost.idTokenClaims` maps it by granted scope (OIDC Core 5.4: `email`, `email_verified` with scope email, `name` with scope profile), and adds `faye_flow_id` for a preview client, from the consent's context. Conformance vectors check `user` (`expect_result.user`), not claims.
* **The host decides the tenant and the preview flag.** `HostRequest` gains `tenant` (null for the default) and `preview`. The engine and the identity handlers read them, through `FlowState.preview` and `Invocation.tenant`/`preview`, never from `client.metadata`; `Previews.isPreview` is removed. Preview runs drafts and preview only accounts, so a host sets the flag only for clients its operator registered as such, never from caller input. The Hydra and mock hosts read `metadata.tenant` and `metadata.preview` of the client as before. `FlowState.preview` is absent in states saved earlier and reads as false, so the state format does not change; a preview flow in flight at the upgrade stops being traced.
* `client.*` remains readable by flows as display data (`client_id`, `client_name`, `first_party`, `metadata`); nothing in the engine decides anything from it. `BoundaryTest` fails the build if the core reads `"metadata"` or names a claim.

### What stays as it is

* `HostKind` (`LOGIN`, `CONSENT`, `LOGOUT`) names kinds of request, not Hydra endpoints. It and `EntrySelector` are unchanged.
* The `/login?login_challenge=` and `/consent?consent_challenge=` routes are the Hydra host's HTTP surface. They stay in `faye-app`. An embedding host adds its own start route, or calls `Engine.start` as a library.
* The IR grammar and format 1, the state format, the screen types and `conformance/handlers.yaml` do not change. Only the handler modules gain issuers.
* `ClientDirectory`, the read only list of the authorization server's clients for the Admin UI, stays in `faye-engine` though the engine never uses it; moving it to a host API module is left for a later change.

### Consequences

* Good, because a second host, a separately deployed Login UI and an upstream credential service each plug into one written contract (`HostParams`, `ScreenView`, `CredentialIssuer` with its handlers, `Completion`), and none of them changes the engine, the IR or a flow.
* Good, because `host.*` becomes closed: a flow cannot come to depend on a key that only one host provides.
* Good, because challenges and secrets can come from the service that verifies them, which an upstream credential owner requires.
* Bad, because `FlowHost`, `HostRequest`, `Engine`'s construction, `Checker` and `Publisher` change signature. Every host, test and the app's wiring change with them.
* Bad, because an issuer adds a network call before a credential screen when the issuer is upstream. Its latency adds to the screen's (unmeasured).
* Neutral: `Handoff` lets Faye return an outcome to a non-redirecting host. That is still reporting an outcome, not issuing a token (charter non-goal 2). The host owns what it does with the result.

### Confirmation

* `cd server && ./gradlew build`, which runs:
  * `faye-engine`: `ConformanceTest` over every vector, which proves the behavior is the same, plus `issuer-fails-then-retries` and `issuer-fails-closed` (scripted `issuers:`), and the consent vectors checking `user` in place of ID token claims.
  * `faye-engine`: `EmbeddingTest`, the engine built from its SPIs alone (no Hydra, no built in screen): a host that returns `Handoff`, an issuer whose material reaches the handler only as the submission's context and the UI only as what it presents, a failing issuer that fails the step closed, a lost race that returns `conflict` without calling `accept`, the closed `HostParams`, a `choose` that needs a picker nobody registered (refused by the verifier, `flow_unavailable` at start), and a picker that declares an issuer (refused by the registry).
  * `faye-engine`: `BoundaryTest`, which fails if the main sources of `faye-ir`, `faye-dsl`, `faye-engine` or `faye-check` name Hydra, a protocol parameter of a screen (`login_hint`, `requested_scope`), `otpauth`, the passkey algorithms, the `"Faye"` label, client metadata or a token claim, or if any of their build files depends on a host, screen or credential module.
  * `faye-check`: `CheckerTest.aHostPathOutsideTheContractIsRefusedAtPublish`, and `CheckerTest.aStoredVersionThatReadsAnUnknownHostPathStillLoads`.
  * `faye-screens`: `BuiltinScreenTest`, the built in screens equal the protocol's, a declaring screen without its issuer and an issuer for a screen that declares none are refused when the registry is built, and `password` and `consent` need none.
  * `faye-identity`: `IdentityIssuersTest`, the TOTP secret survives a retry, the otpauth label uses the tenant's display name, and a passkey registration without a subject fails to issue.
  * `faye-dsl`: `FlowCompilerTest.theCompilerKnowsNoHostSpelling` and `aliasedNamespacesLowerToTheirNamespace`.
  * `faye-app`: `HandoffApiTest`, a host that hands off: a flow started through `Engine.start` and answered over HTTP finishes with `{result}`, valid against the screen protocol, with its cookie expired; the Hydra routes refuse to render a handoff.
  * `faye-engine`: `StoredStateFuzzTest.corruptStatesAreNotFound`, a passkey registration for nobody is refused through the screen's `resumable`.
  * `faye-host-hydra`: `HydraFlowHostTest`, the Hydra host's redirects are unchanged, it maps Hydra's request into the host contract, takes the tenant and preview flag from the client's metadata, and maps `user` to ID token claims by scope, with `faye_flow_id` for preview clients only.
* `cd web && pnpm generate && pnpm typecheck && pnpm lint && pnpm test`, and the contract diff check in CI.
* `deploy/smoke.sh` passes on a fresh stack, which proves the Hydra behavior is unchanged.

Open gaps, which keep this record `in progress`:

* `deploy/smoke.sh` has not been run against this change (it needs the compose stack).
* The latency an upstream issuer adds before a credential screen is unmeasured: there is no upstream issuer yet. Measure it with the first one, against its own deadline.

## Pros and Cons of the Options

### 1A Free map

* Good, because adapters are trivial.
* Bad, because the contract is implicit, and the engine's screens read protocol keys.

### 1C Per-host schema

* Good, because each host can expose its richer data.
* Bad, because flows written against one host's schema do not run on another, which breaks success criterion 4.

### 2A Engine issues

* Good, because there is nothing to plug in.
* Bad, because an upstream credential owner cannot verify what the engine issued, and the issuer label and the algorithms are fixed in the engine.

### 2C Call before the screen

* Good, because issuing becomes visible in the flow.
* Bad, because secrets land in `Env`, authors must add plumbing, and the IR needs a new namespace.

### 3A Redirect only

* Good, because it already works with Hydra.
* Bad, because an embedding host must fake a redirect, and the result would travel in a URL.

### 3C Outcome out of the engine

* Good, because `FlowHost` gets smaller.
* Bad, because the save-before-accept ordering would move to every host.

## More Information

* SPI survey for this record (sources opened during the change): Keycloak [`WebAuthnAuthenticator`](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/authentication/authenticators/browser/WebAuthnAuthenticator.java) issues its challenge into the auth session and verifies it; Keycloak [`TotpBean`](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/forms/login/freemarker/model/TotpBean.java) generates the TOTP secret in the theme, and [`UpdateTotp`](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/authentication/requiredactions/UpdateTotp.java) enrolls the secret the form posts back, the pattern 2B avoids; Ory Kratos [`login.Flow`](https://github.com/ory/kratos/blob/master/selfservice/flow/login/flow.go) keeps strategy data in `InternalContext` (never serialized) and sends the UI typed nodes, and its [login hook](https://github.com/ory/kratos/blob/master/selfservice/flow/login/hook.go) answers an API flow with data and a browser flow with a redirect, fixed per flow; PingAM [transient state](https://docs.pingidentity.com/pingam/8/auth-nodes/store-values-shared-state.md) separates sensitive node state from shared state (unverified beyond the documentation).

* Charter: principles 1–3 and 7, non-goal 2, success criterion 4.
* [ADR 0024](0024-core-ir-as-a-narrow-waist.md): 4B (`host.*`), 7B (screen catalog), 8B (verifier), 10B (binder).
* [ADR 0022](0022-passkey-ceremonies-with-simplewebauthn.md): algorithms come from the server.
* The field list of `HostParams` is the set of keys `HydraFlowHost.load` writes on `main` at 84bc4ff: `skip`, `subject`, `requested_scope`, `requested_access_token_audience`, `acr_values`, `ui_locales`, `login_hint` and, for consent, `context`. `HostRequest`'s Javadoc omits `requested_access_token_audience` and `ui_locales`. The only `host.` paths in shipped flows and vectors are `host.acr_values` and `host.login_hint`, so closing the namespace breaks no existing flow.
