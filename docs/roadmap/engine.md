# Engine

How the reference engine (`server/faye-engine`) implements the Core IR. What it must do is [ir-spec.md](ir-spec.md); this document is how. The engine depends only on contracts: the IR (`faye-ir`), the handler catalog (`HandlerRegistry`), the screen catalog (`ScreenRegistry` with its `CredentialIssuer`s) and `FlowHost` ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md), [ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)). It names no authorization server and renders nothing; `BoundaryTest` fails the build if `faye-ir`, `faye-dsl`, `faye-engine` or `faye-check` mention Hydra, a screen's protocol fields, or depend on a module around them.

## Model

The IR in Java is sealed records, one per form:

```java
public sealed interface Node permits Seq, Step, Screen, Call, Hook, Choose, When, Retry, SetVar, Succeed, Fail {
    SourceRef source();
}
```

The interpreter, the checker, the verifier and the codec switch over `Node` exhaustively, so a form added without handling it everywhere fails compilation; `IrAgreementTest` checks the schema, these types and the spec name the same forms. A record holds data only: the schema is the grammar, and `IrCodec` reads JSON into records, refusing an unsupported format, a grammar violation, or duplicate entry and option ids.

## Execution: small step, suspendable

The engine is not recursive over the tree. It runs an explicit continuation stack so that a flow can be persisted between HTTP requests.

```
FlowState {
  flowId, tenantId
  versionHash        // pinned at start; never changes
  kind, entry        // the host challenge, and the IR entry it runs (EntrySelector)
  hostRef            // challenge id held for the FlowHost
  binder             // SHA-256 of the secret the starting browser holds
  stack: List<Frame> // the continuation, plain data
  env                // request, client, host, tenant, user, vars, auth
  awaiting           // the screen it waits on: path, type, nonce, server data, error, attempts left
  pending            // writes the entry staged, applied only if it succeeds
  revision           // for the compare and set
}
```

`Frame` records are the rest of the computation for each form: `Eval(path)`, `SeqNext(path, next)`, `StepExit(path, id)`, `RetryExit(path, attempt)`, `Pick(path)`. Frames reference nodes by their child index path within the entry body of the pinned IR, so they serialize to small JSON. `FlowStateCodec` writes them with a `stateFormat`; a state in a format the engine does not read is treated as absent.

A request:

1. `start` asks the host for the request (`FlowHost.load`), picks the version (the tenant's current one, a preview's draft, or for a consent the login's), checks it is runnable here (L1, `Verifier`), selects the entry, and runs.
2. `view` and `submit` load the state, refuse a binder that does not match as an unknown flow and a version that can no longer run here, then check the nonce and the answer.
3. A run pops frames and evaluates one step at a time. `call` and `hook` run synchronously. A `screen` (or a `choose` that needs the person) yields: the state is saved and the screen returned.
4. The run's state is saved with a compare and set on the revision. If the entry succeeded, its staged writes are applied, then the host is told. A submit that loses the compare and set applies nothing and is answered with the screen the winner led to, or `flow_finished`.
5. `succeed` and `fail` end the entry and hand the outcome to the `FlowHost`, whose `Completion` (`Redirect` or `Handoff`) is the answer.

## Handlers

```java
interface Handler {
    String name();                       // "password.verify"
    HandlerMeta meta();                  // amr, binds_subject, identifies, requires_subject, effect, args
    Outcome invoke(Invocation call);     // env, input, args, flowId
    @Nullable String commit(Effect e);   // only for effect: write
}
```

`HandlerMeta` equals the handler's entry in `conformance/handlers.yaml`, which is the contract (`HandlerContractTest`). An `Outcome.Ok` merges into `user`, may name a subject and narrow the amr, and for a `write` handler carries `Effect`s; the engine refuses effects from any other handler. Only handlers that are credentials add to `auth`; `amr` and `acr` are computed from `auth` at `succeed`. Neither hooks nor `set` can touch `auth`.

## Screens

`ScreenSpec` gives a screen type its options, its own server data (`prepare`), its payload (`render`), the answers it accepts, and any later screen an answer to it also answers (`hosted`, `answeredBy`). A spec that shows credential material declares it (`issued()`), and the `CredentialIssuer` registered for it, from the module whose handlers verify the answer, issues the material (`issue`) and its public half (`present`). The engine merges the spec's and the issuer's parts; issued material is kept only as the waiting screen's data and reaches handlers only as `Submission.context()`, never `Env`, hooks or traces. An issuer that throws fails the step with `issuer_error`.

The engine knows no screen but `method_picker`, which a `choose` waits on. The screens of `schema/screen.schema.json` live in `faye-screens` (`BuiltinScreen.registry(issuers)`, `BuiltinScreenTest`); `faye-identity` supplies their issuers (`IdentityIssuers`). `ScreenRegistry` refuses at construction a declaring screen without an issuer and an issuer for a screen that declares none.

## Embedding

The engine is a library. An embedder assembles it from its SPIs and calls three methods; `EmbeddingTest` does exactly this with no module but `faye-engine`.

```java
Engine engine = Engine.builder()
        .versions(versions)            // FlowVersions: published versions (Publisher writes them)
        .handlers(handlers)            // HandlerRegistry: the only code that verifies, binds or writes
        .screens(screens)              // ScreenRegistry: the screens the embedder's UI renders, with issuers
        .store(store)                  // FlowStore: suspended flows, compare and set on the revision
        .host(host)                    // FlowHost: the system flows run for
        .tenants(tenants)              // optional: TenantPolicies, default none
        .hooks(hooks)                  // optional: HookRunner, default none
        .previews(previews)            // optional: Previews, default off
        .build();
```

Registration is explicit: nothing is discovered from the classpath, so the trusted set of handlers, screens and issuers is the list the embedder writes, validated when each registry is built.

| Call | Returns | Notes |
|---|---|---|
| `start(kind, ref, request, binder)` | `ScreenView`, or the host's `Completion` if the flow ended at once (a remembered session, a rejection); a `Redirect` after an authenticate entry that ran and failed is marked `failed` (ADR 0031) | `ref` is the host's id for the request, handed to `FlowHost.load`. `request` is read as `request.*`. `binder` is a secret the caller creates (`Engine.newBinder()`), gives the browser out of reach of the page's scripts (an HttpOnly cookie), and presents on every later call; the state keeps only its hash. |
| `view(flowId, binder)` | `ScreenView` | The screen the flow waits on. |
| `submit(flowId, binder, nonce, submission)` | `ScreenView`, or the host's `Completion` | `nonce` is the `ScreenView`'s; a submission is the person's answer only (`Submission(payload)`), the engine adds the issued context. |

`FlowException` codes: `flow_not_found` (unknown flow, wrong binder, expired, or a version that can no longer run here: start again), `flow_finished` (the flow already ended), `stale_nonce` (an answer to a screen no longer shown: reload the view), `conflict` (a concurrent answer is being saved: reload), `invalid_submission` (malformed answer: the person's input). The first two are fatal for the flow; the others leave it unchanged. Any other exception (a host or store that throws) is a server error; nothing the losing request did lasts.

A host completes a request one of two ways. A redirecting host (Hydra) returns `Redirect(url)`; the app answers the Login UI `{redirectTo}`. A host that started the flow by API returns `Handoff(result)`, for example a one time code its system exchanges server to server for the `Authenticated` outcome; the app answers `{result}`, and the Login UI, opened by the embedding page in a popup, posts it to that page's origin (`FAYE_EMBED_ORIGIN`, exactly one origin; unset, nothing is posted). The sign in screens refuse to be framed.

The host also decides, per request, the tenant whose flow runs and whether the client is a preview client (`HostRequest.tenant`, `preview`); the engine never reads either from `client.*`. A consent's outcome carries what the login learned about the person (`Consented.user`); turning it into claims or tokens is the host's job, outside the engine. `accept` and `reject` are called only by the request whose saved state finished the flow, after its writes are applied.

`HostParams` is the host contract: the closed set of fields flows read as `host.*` (ir-spec.md, Environment). A host maps its protocol into it and cannot add a key.

## Versioning and pinning

1. A version is immutable and addressed by the SHA-256 of its canonical IR, which includes the IR format and every hook's hash.
2. A tenant has one current pointer; `Publisher` is the only way to move it, and it runs the checker first.
3. Every flow pins its version at start; a publish never changes a flow in progress (`publish-mid-flow`).
4. Published versions are kept forever, so a tenant can roll back. A version only stored, for a preview, is deleted once nobody published it, no live flow pins it and it is older than the flow TTL. `SqliteFlowVersions` caches 256 versions, least recently read first out.
5. A stored version is checked against the bytes kept; an older IR format is upgraded on read, keeping its hash.

## Flow state store

SQLite ([ADR 0017](../adr/0017-one-sqlite-database.md)), table `faye_flow`, with an expiry set at creation equal to the host request's lifetime, and a revision counter for the compare and set. An in memory store implements the same interface for tests.

## Conformance suite

`conformance/` holds language independent vectors ([README](../../conformance/README.md)). The Java engine runs every vector in CI with scripted handlers and hooks. The checker's path enumeration generates a vector per path it can realize, and the build fails when the checker and the engine disagree on how a path ends.
