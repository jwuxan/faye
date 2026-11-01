---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Hard limits on flows and hooks

## Context and Problem Statement

The IR is closed and always terminates (D3), and each hook run is already bounded:

* a wall clock deadline (`timeout_ms`, at most 2000) that interrupts the Wasm worker, which `QuickJsRuntime` tests show comes back free;
* 16 MiB of linear memory;
* 8 hooks running at once, with 8 more waiting;
* 64 KiB of source, a compile budget, 20 log lines and 64 KiB per egress response;
* at most 16 `vars` per result.

The document is bounded too: 1 MiB of IR, 128 levels of nesting, 10,000 paths per entry. The checker allows at most 10,000 events on one path.

Termination is not the same as staying small. Measured on `main` on 2026-11-01:

* **Flow size.** Nothing caps the number of steps, hook attachments, distinct hooks or options of a `choose`. Nothing caps how retries nest, and nested retries multiply how often a node runs. Literals and ids may be up to 1 MiB long.
* **Hooks per request.** A 1 MiB flow can attach thousands of hooks. One request runs every hook between two screens, each for up to 2 s, so one request can hold a worker for hours. Nothing in the engine counts hook runs or hook time.
* **`vars` and state.** Each hook may set up to 16 new names, so `vars` and the stored flow state grow with every hook run. The state's size is never checked.
* **Egress calls.** A hook may call egress any number of times within its deadline.
* **Hook storage.** `DraftPreviews` stores every previewed draft's hooks in `faye_hook`. Previewed versions are swept, but their hooks are kept forever. `SqliteHookStore` caches every hook it ever read.

The shipped and conformance flows are far below any limit proposed here. The largest has 46 nodes, 5 steps, 2 hook attachments, 1 distinct hook, 3 options in a choose, a retry product of 3, and literals of 4 characters.

What hard limits keep every flow, and every request, flow state and store, bounded, without making the checker and the engine disagree?

## Decision Drivers

* Charter principle 1: the system is secure by design, so a tenant's configuration cannot exhaust the server.
* The checker and the engine agree. `EngineFuzzTest` fails the build if the checker accepts a flow that the engine then fails to run, so a limit the engine enforces at run time must be implied by the static limits, or surface as a failure the checker already models.
* Limits are checked on the IR, so they hold for every frontend, not only YAML.
* Only constants: no configuration and no new dependency.

## Considered Options

1. Runtime budgets only: count evaluations, hook runs and hook time per request, and fail when one runs out.
2. Static limits only, in the verifier and the checker.
3. Static limits that bound everything a run can do, plus a few runtime backstops that surface as failures the checker already models.

## Decision Outcome

Chosen option: 3.

* Option 1 lets a published flow fail at run time for its shape alone, which the checker cannot predict.
* Option 2 trusts a hook's declared timeout for wall time, and cannot bound what a hook writes into `vars` or what handlers put into the state.

### Static limits on the IR (L1, `FlowLimits`, run by `Verifier`)

These are checked at publish, at preview, and whenever the engine runs a version. A stored version over a limit reads as unavailable and fails closed, as it does for a catalog that lost a handler.

| Limit | Value |
|---|---|
| IR nodes in a flow (every form, across entries) | 1,000 |
| `step`s in a flow | 64 |
| Hook attachments in a flow (`hook` nodes plus `choose` pick hooks) | 16 |
| Distinct hooks (hashes) in a flow | 8 |
| Options of one `choose` | 8 |
| Product of the attempts of nested `retry`s on any path | 10 |
| Distinct names written by `set` in a flow | 32 |
| Length of a string literal (`set` values, predicate literals) | 1,024 characters |
| Length of an id, key, handler name or `fail` reason | 64 characters |

Since the language has no loops, a node runs at most as many times as the product of the attempts of its enclosing retries, over the whole life of its entry. So an entry evaluates at most 1,000 × 10 nodes, across all its requests.

### Static limits needing the hook definitions (L2, `Checker`)

For each entry, take the sum over its hook attachments of the retry product around each attachment.

| Limit | Value |
|---|---|
| Hook runs in an entry: that sum | 32 |
| Hook time in an entry: that sum, each term weighted by the hook's `timeout_ms` | 5,000 ms |

These bound every request of the entry together.

### Runtime backstops (engine)

| Backstop | Value | When it trips |
|---|---|---|
| Hook runs in one run | 32 | The next hook is not run and fails with `hook_error`. |
| Measured hook time in one run | 10,000 ms | The next hook is not run and fails with `hook_error`. The clock includes queueing, so the value is twice the static bound to leave room for it. |
| Distinct `vars` names | 64 | A hook `set` that would pass it fails with `hook_error`. |
| Encoded flow state when the flow suspends | 256 KiB | The entry ends with the terminal failure `limit_exceeded`. |
| Egress calls per hook run (`SandboxedHooks`) | 4 | The fifth call is an error the hook can catch. |

`hook_error` is a hook failure the checker already enumerates on every hook. The two count and time backstops never trip on a flow that passed L2; they guard against a version that skipped it or a slow server. The state limit sits above everything the static limits let flows and hooks write: 64 names of 1,024 characters is 64 KiB.

### Unbounded storage in the app

* `faye_hook` gets `stored_at` in a new migration (existing rows get the time of the migration).
* `SqliteFlowVersions`' sweep also deletes hooks older than the flow TTL that no kept version's IR names. Storing a hook again renews it, as for versions.
* `SqliteHookStore`'s cache holds at most 256 hooks, least recently read first out.

### Consequences

* Good, because every request, every flow state and every store has a stated bound, and every bound is a constant in one place.
* Good, because no limit makes the checker and the engine disagree.
* Bad, because a tenant can no longer publish an unusually large flow. The limits sit about 10 times above the largest flow on `main`.
* Bad, because a stored version over a new limit stops running. None exists on `main`.
* Neutral: the number of live flows, SSE subscribers and admin calls is not capped. That is rate limiting, which the prototype's non goals leave to production hardening (ADR 0036, R1).

### Confirmation

* `cd src && ./gradlew build`, which runs:
  * `FlowLimitsTest`: each static limit at its value and one past it;
  * `CheckerTest`: the hook run and hook time limits;
  * `EngineTest`: the runtime backstops, with a stub `HookRunner` that advances the clock, and a stub handler that makes the state too large;
  * `SandboxedHooksTest`: the egress call limit;
  * conformance vectors for an over limit flow refused at start, and for the `vars` limit;
  * `EngineFuzzTest`, which keeps the checker and the engine agreeing.
* `cd apps/server && ./gradlew build`: `FlywayMigrationTest`, and a test that a swept preview's unreferenced hooks are deleted while a published version's hooks stay.

Run on 2026-11-01:

* `cd src && ./gradlew build` passes: `FlowLimitsTest`, `CheckerTest`, `EngineTest`, `SandboxedHooksTest`, the vectors `limits-too-many-steps` and `limits-vars`, and `EngineFuzzTest`.
* `cd apps/server && ./gradlew build` passes: `FlywayMigrationTest` and `SqliteHookSweepTest`.

Not yet run: `deploy/smoke.sh` against the compose stack with this change. The record stays `in progress` until a human accepts it.
