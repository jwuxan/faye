---
status: proposed
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

1B, 2B, 3B. None of them changes the IR grammar, the IR format or the screen protocol (`schema/screen.schema.json`).

### Host parameters (1B)

```java
record HostParams(@Nullable String subject, boolean skip, List<String> acrValues,
                  List<String> requestedScope, List<String> requestedAccessTokenAudience,
                  @Nullable String loginHint, List<String> uiLocales,
                  Map<String, Object> context) {}
```

* `HostRequest.params` becomes `HostParams`.
* The `host.*` namespace is exactly these fields under their wire names (`subject`, `skip`, `acr_values`, `requested_scope`, `requested_access_token_audience`, `login_hint`, `ui_locales`, `context`). `ir-spec.md` lists them as the host contract, with what each means and which host kinds supply it.
* The verifier (L1 layer, ADR 0024 8B) refuses a predicate path `host.<x>` where `<x>` is not a field. Today the path pattern accepts any `host.` suffix.
* `context` is the one free-form field: the host's own data, carried to the outcome and readable as `host.context.*`.
* Adding a field is a change to this contract, made through an ADR.
* Each `FlowHost` maps its protocol into `HostParams`. For example, Hydra's `oidc_context.login_hint` becomes `loginHint`. An adapter cannot add a key.
* 1C was rejected because each host would then define its own namespace, which is the leak itself: a flow could still depend on one host's keys.

### Credential issuers (2B)

```java
interface CredentialIssuer {
    String screen();                       // e.g. "passkey", "otp_enroll", "identifier"
    Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous);
    Map<String, Object> present(Env env, Map<String, Object> issued); // fields the screen shows
}
```

* The credential module registers its issuers with its handlers. `faye-identity` provides today's behavior: random WebAuthn challenges, a TOTP secret that survives a retry, and the otpauth URI.
* `BuiltinScreen.prepare` and `render` delegate the credential part to the issuer registered for the screen.
* The tenant's display name replaces the hard-coded issuer `"Faye"` in the otpauth label.
* The passkey algorithms move to the issuer. `ScreenType.PASSKEY_ALGORITHMS` leaves the engine, and the Login UI still receives them in the screen, as in [ADR 0022](0022-passkey-ceremonies-with-simplewebauthn.md).
* Issued data is stored, as today, only as the waiting screen's data (`FlowState.Awaiting.data`). It reaches the consuming handler only as `Submission.context()`. It is never in `Env`, so predicates and hooks cannot read it.
* The issuer and the handler that consumes its output come from the same module. The `ScreenRegistry` refuses a screen whose issuer is missing at startup, not at the first flow.
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
- The HTTP layer of `faye-app` maps `Redirect` to `{redirectTo}` as now and `Handoff` to `{result}`. The Login UI hands a `Handoff` to its embedding page.
- `challenge` is renamed `ref`. It is the host's opaque id for the request (a Hydra challenge, or an id the existing system created).

The embedding contract goes in `docs/roadmap/engine.md`:
- `start(kind, ref, request, binder)`, `view(flowId, binder)` and `submit(flowId, binder, nonce, submission)`.
- What each returns.
- The binder rules from ADR 0024 10B: the caller creates the binder, keeps it from the page's scripts, and presents it on every call.
- Which `FlowException` codes are user errors and which are fatal.

3C was rejected because it would move `accept` and `reject` out of the engine. The engine would then no longer save before it accepts (only the submit that wins the revision race may accept), and every host would have to re-implement that ordering.

### What stays as it is

* `HostKind` (`LOGIN`, `CONSENT`, `LOGOUT`) names kinds of request, not Hydra endpoints. It and `EntrySelector` are unchanged.
* The `/login?login_challenge=` and `/consent?consent_challenge=` routes are the Hydra host's HTTP surface. They stay in `faye-app`. An embedding host adds its own start route, or calls `Engine.start` as a library.
* The screen protocol, the IR grammar and format 1, and `conformance/handlers.yaml` do not change. Only the handler modules gain issuers.

### Consequences

* Good, because a second host, a separately deployed Login UI and an upstream credential service each plug into one written contract (`HostParams`, `ScreenView`, `CredentialIssuer` with its handlers, `Completion`), and none of them changes the engine, the IR or a flow.
* Good, because `host.*` becomes closed: a flow cannot come to depend on a key that only one host provides.
* Good, because challenges and secrets can come from the service that verifies them, which an upstream credential owner requires.
* Bad, because `FlowHost` and `HostRequest` change signature. `HydraFlowHost`, `MockFlowHost`, `EngineTest` and `FlowApiTest` change with them.
* Bad, because an issuer adds a network call before a credential screen when the issuer is upstream. Its latency adds to the screen's (unmeasured).
* Neutral: `Handoff` lets Faye return an outcome to a non-redirecting host. That is still reporting an outcome, not issuing a token (charter non-goal 2). The host owns what it does with the result.

### Confirmation

These tests are proposed; none of them exists yet.

* `cd server && ./gradlew build` with:
  * `faye-engine`: `ConformanceTest` passes unchanged, which proves the behavior is the same. A new vector, `host-param-unknown`, is refused by the verifier.
  * `faye-engine`: an `EngineTest` with a `FlowHost` that returns `Handoff`. A finished flow returns the handoff, and a lost race returns `conflict` without calling `accept`.
  * `faye-identity`: an issuer test showing that the TOTP secret survives a retry, the otpauth label uses the tenant's display name, and a failing issuer fails the step closed.
  * `faye-app`: a `ScreenRegistry` startup test that refuses a screen with no issuer, and a `FlowApiTest` for a handoff response.
  * The command `grep -rn 'login_hint\|requested_scope\|PASSKEY_ALGORITHMS\|"Faye"' server/faye-engine/src/main` prints nothing.
* `deploy/smoke.sh` passes on a fresh stack, which proves the Hydra behavior is unchanged.

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

* Charter: principles 1–3 and 7, non-goal 2, success criterion 4.
* [ADR 0024](0024-core-ir-as-a-narrow-waist.md): 4B (`host.*`), 7B (screen catalog), 8B (verifier), 10B (binder).
* [ADR 0022](0022-passkey-ceremonies-with-simplewebauthn.md): algorithms come from the server.
* The field list of `HostParams` is the set of keys `HydraFlowHost.load` writes on `main` at 84bc4ff: `skip`, `subject`, `requested_scope`, `requested_access_token_audience`, `acr_values`, `ui_locales`, `login_hint` and, for consent, `context`. `HostRequest`'s Javadoc omits `requested_access_token_audience` and `ui_locales`. The only `host.` paths in shipped flows and vectors are `host.acr_values` and `host.login_hint`, so closing the namespace breaks no existing flow.
