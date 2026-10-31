# Engine

How the reference engine (`server/faye-engine`) implements the Core IR. What it must do is [ir-spec.md](ir-spec.md); this document is how. The engine depends only on contracts: the IR (`faye-ir`), the handler catalog (`HandlerRegistry`), the screen catalog (`ScreenRegistry`) and `FlowHost` ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md)).

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

1. `start` asks the host for the challenge, picks the version (the tenant's current one, a preview's draft, or for a consent the login's), checks it is runnable here (L1, `Verifier`), selects the entry, and runs.
2. `view` and `submit` load the state, refuse a binder that does not match as an unknown flow and a version that can no longer run here, then check the nonce and the answer.
3. A run pops frames and evaluates one step at a time. `call` and `hook` run synchronously. A `screen` (or a `choose` that needs the person) yields: the state is saved and the screen returned.
4. The run's state is saved with a compare and set on the revision. If the entry succeeded, its staged writes are applied, then the host is told. A submit that loses the compare and set applies nothing and is answered with the screen the winner led to, or `flow_finished`.
5. `succeed` and `fail` end the entry and hand the outcome to the `FlowHost`.

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

`ScreenSpec` gives a screen type its options, the server data it issues (`prepare`), its payload (`render`), the answers it accepts, and any later screen an answer to it also answers (`hosted`, `answeredBy`). `ScreenRegistry.builtIn()` holds the screens of `schema/screen.schema.json` (`ScreenRegistryTest`); the engine waits on `method_picker` for a `choose`.

## Versioning and pinning

1. A version is immutable and addressed by the SHA-256 of its canonical IR, which includes the IR format and every hook's hash.
2. A tenant has one current pointer; `Publisher` is the only way to move it, and it runs the checker first.
3. Every flow pins its version at start; a publish never changes a flow in progress (`publish-mid-flow`).
4. Published versions are kept forever, so a tenant can roll back. A version only stored, for a preview, is deleted once nobody published it, no live flow pins it and it is older than the flow TTL. `SqliteFlowVersions` caches 256 versions, least recently read first out.
5. A stored version is checked against the bytes kept; an older IR format is upgraded on read, keeping its hash.

## Flow state store

SQLite ([ADR 0017](../adr/0017-one-sqlite-database.md)), table `faye_flow`, with an expiry set at creation equal to the challenge lifetime, and a revision counter for the compare and set. An in memory store implements the same interface for tests.

## Conformance suite

`conformance/` holds language independent vectors ([README](../../conformance/README.md)). The Java engine runs every vector in CI with scripted handlers and hooks. The checker's path enumeration generates a vector per path it can realize, and the build fails when the checker and the engine disagree on how a path ends.
