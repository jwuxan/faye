# Engine

## Model

The IR in Java:

```java
public sealed interface Node permits Seq, Step, Screen, Call, Hook, Choose, Opt, When, Retry, Set, Succeed, Fail {
    SourceRef source();
}
public record Seq(List<Node> children, SourceRef source) implements Node {}
public record Screen(String type, Map<String, Object> opts, SourceRef source) implements Node {}
// …
```

The interpreter is a `switch` over `Node` with pattern matching. Adding a form without handling it in the interpreter, the checker, and the serializer fails compilation.

## Execution: small step, suspendable

The engine is not recursive over the tree. It runs an explicit continuation stack so that state can be persisted between HTTP requests.

```
FlowState {
  flowId
  tenantId
  versionHash        // pinned at start; never changes during the flow
  section            // login | consent
  stack: List<Frame> // continuation, plain data
  env: Env           // request, client, hydra, user, vars, auth
  hostRef            // challenge id held for the FlowHost
}
```

`Frame` records are the "rest of the computation" for each form: `SeqFrame(node path, index)`, `RetryFrame(path, remaining)`, `ChooseFrame(path, enabled options)`, and so on. Frames reference nodes by **path within the pinned IR**, so they serialize to small JSON.

Loop:

1. Pop a frame, evaluate one step.
2. `call` and `hook` execute synchronously and return `ok` or `fail` plus env changes.
3. `screen` (or a `choose` that needs user input) **yields**: the engine persists `FlowState` and returns the screen.
4. A submission loads `FlowState`, validates the submission against the screen it is answering (step id and nonce), pushes the result, continues the loop.
5. `succeed` / `fail` end the section and hand off to the `FlowHost`.

## Handlers

```java
interface Handler {
    String name();                       // "password.verify"
    Set<String> amr();                   // amr classes it proves, empty if not a credential
    boolean bindsSubject();
    Outcome invoke(Env env, Submission input, Map<String, Object> args);
}
```

Only handlers marked as credentials may add to `auth.verified`. `amr` and `acr` are computed from `auth.verified` at `succeed`. Neither hooks nor `set` can touch `auth.*`.

## Versioning and pinning

1. A published version is immutable and addressed by `sha256(canonical IR + hook hashes)`.
2. A tenant has one "current" pointer per flow; publishing moves the pointer.
3. Every `FlowState` pins `versionHash` at creation. In flight flows finish on the version they started with, so a publish never breaks a user mid login.
4. Old versions are retained while any flow state references them (bounded by state TTL).

## Flow state store

Redis with TTL equal to Hydra's challenge lifetime. Keys: `flow:{flowId}`. Optimistic concurrency via a version counter to reject double submits. An in memory store implements the same interface for tests.

## Conformance suite

`conformance/` holds language independent test vectors:

```yaml
name: password retry then success
ir: ./flows/password-only.json
env: { user: { exists: true } }
handlers:
  password.verify: [fail, ok]
events:
  - expect_screen: identifier
  - submit: { identifier: alice@example.com }
  - expect_screen: password
  - submit: { password: wrong }
  - expect_screen: password
    expect_error: invalid_credentials
  - submit: { password: right }
  - expect_result: { succeed: true, amr: [pwd] }
```

The Java engine runs every vector in CI. Handlers are scripted fakes. Path enumeration from the checker can generate vectors automatically: one per success path and one per failure branch, which is the bulk of the "huge test suite" for free.
