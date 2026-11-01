---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# M4: JavaScript hooks on QuickJS in WebAssembly

## Context and Problem Statement

M4 adds tenant JavaScript hooks: a `HookRuntime` with deadlines, memory limits and no host access; a hook store by hash; capability grants and named HTTP egress targets; `pick` in `choose`; and the licensing decision on the GraalVM edition (open question 1 of the decision log ([ADR 0036](0036-retire-the-roadmap.md))), with a Wasm fallback spiked if needed.

Exit: a risk hook routes high risk sign ins to TOTP; a malicious hook (infinite loop, an attempt to return `{ succeed: true }`, an attempt to read `auth`) fails safely and is covered by tests.

The charter allows hooks to "only set values, fail a step, or pick among declared options" (principle 2) and requires that no hook, however written, can produce a successful sign in (success criterion 3). The risk table rates sandbox escape or resource exhaustion as critical.

## Decision Drivers

* Charter principle 1 and success criterion 3: hostile hook code fails closed. The engine, not the sandbox, enforces the closed output.
* A hard memory cap per run. One tenant's hook must not take the server's heap.
* A wall clock deadline per run that interrupts compute.
* No host access: no Java classes, files, network, processes or timers beyond what Faye hands in.
* License fit for commercial production. Runs on the stock JDK 21+ the build already uses.
* Small: few and small dependencies, no native libraries per platform (AGENTS.md rule 7, charter principle 7).

## Considered Options

1. JavaScript runtime
   * A. GraalJS community (`js-community`), `SandboxPolicy.CONSTRAINED`, a watchdog plus `statementLimit`.
   * B. GraalJS with isolates (`js-isolate-community` from 25.1, or Oracle `js-isolate` under GFTC), `ISOLATED`/`UNTRUSTED` with `MaxCPUTime`, `MaxIsolateMemory`, `MaxHeapMemory`.
   * C. QuickJS compiled to WebAssembly and AOT compiled to JVM bytecode, run by a pure Java Wasm runtime (`io.roastedroot:quickjs4j` on Endive).
2. Where the closed output is enforced
   * A. In the sandbox glue.
   * B. In the engine, on whatever the runtime returns.
3. What a hook's hash covers
   * A. The source only, with capabilities and timeout in tenant config.
   * B. The definition: source, declared egress targets and timeout.
4. What the hook of a `choose` may return
   * A. Anything a step hook may, plus `pick`.
   * B. Only `pick`, `fail` or `{}`.

### Evidence: spikes

Two throwaway spikes on JDK 25.0.1, Apple Silicon, `-Xmx512m` (outside the repo). The numbers are local measurements, not benchmarks.

| | GraalJS community (A) | GraalJS isolate community (B) | QuickJS on Wasm (C) |
|---|---|---|---|
| `for(;;){}`, 200 ms deadline | watchdog `close(true)` stops it 41 to 52 ms late | `MaxCPUTime` stops it at 218 to 223 ms | interrupt stops it at about 210 ms; the worker thread is idle afterwards |
| Memory bomb | guest OOM after 2.66 s **in the server heap**; a later 50 MB host allocation still failed | stops at 584 ms (`MaxIsolateMemory` 256 MB) or 821 ms (`MaxHeapMemory` 64 MB); JVM fine | 16 MiB linear memory cap: the hook sees `out of memory` at about 230 ms; JVM fine |
| Per run, warm | 4.3 ms new context, 0.7 ms shared engine | 3.7 ms, 0.7 ms shared engine | 3.8 ms fresh instance from cached bytecode |
| Size added | 67 MB | about 79 MB per platform (native image library) | about 1.2 MB (quickjs4j 740 KB, Endive 450 KB), plus a Jackson bump |
| License | UPL | UPL/MIT from 25.1.3; Oracle `js-isolate` is GFTC | Apache-2.0 (quickjs4j, Endive) |

The GraalVM sandboxing guide (https://www.graalvm.org/latest/security-guide/sandboxing/) says sandboxing is available on Community Edition "starting with GraalVM 25.1 ... when using the corresponding isolate artifacts", that `MaxHeapMemory` is "not a hard limit" and "will not prevent the context from causing OutOfMemory errors", and that `MaxIsolateMemory` applies per engine, so per run caps need one engine per run. Quoted as reported by the spike; verify against the page before relying on the exact wording.

### Pros and cons

* 1A: Good, mature and fast. Bad: no memory cap; a hook can exhaust the server heap (measured). Fails a decision driver outright.
* 1B: Good, real CPU and memory limits and JIT inside the isolate. Bad: a 79 MB native library per platform (darwin and linux, amd64 and arm64 for dev, CI and compose); `MaxHeapMemory` is documented as soft; per run memory needs an engine per run; the 25.x release line and artifact naming were inconsistent during the spike (unverified: check the GraalVM release policy before choosing it later).
* 1C: Good, the memory cap is the Wasm linear memory size, which the guest cannot exceed; the only imports are the host functions Faye defines; pure Java; small. Bad: young (quickjs4j 0.1.1, released 2026-10-19; pre 1.0); interpreted QuickJS is slower than GraalJS for heavy compute (irrelevant at a 200 ms budget for routing logic); `Date.now` and `Math.random` exist, so hooks are not deterministic; a promise rejected inside the guest comes back as a stale or null result unless the glue catches it (handled, see below).

## Decision Outcome

1C, 2B, 3B, 4B.

**Licensing (open question 1)**: Faye does not use GraalVM for hooks, so no GraalVM edition or GFTC terms apply. If 1C has to be replaced, 1B on `js-isolate-community` is the candidate; `HookRuntime` keeps that swap inside `faye-hooks`.

**IR**: `(hook id hash)`, and `(choose (hook id hash)? (opt ...) ...)` for a hook that picks. `hash` is `sha256:` of the hook definition's canonical JSON `{"capabilities":{"http":[...]},"source":...,"timeout_ms":...}` (`HookDef`). The flow's own hash covers every hook hash, so editing a hook, its capabilities or its timeout needs a new flow version.

**DSL**: a top level `hooks:` declares each hook (`file:` next to the flow, or inline `source:`; `capabilities: { http: [target] }`; `timeout_ms`, default 200, at most 2000). A step uses `hook: id`; `pick: id` next to `alternative:` makes the choose's hook. The admin API takes YAML only, so hooks published through it carry `source:` inline.

**Engine** (2B): `HookRunner` runs a hook on a snapshot of `request`, `client`, `hydra`, `tenant`, `user`, `vars` (and `options`, the enabled option ids, for the hook of a choose), never `auth`. `HookOutput.parse` accepts only `{}`, `{set: {...}}` (1 to 16 names matching `[a-z][a-z0-9_]*`, each a string up to 1024 characters, an integer or a boolean), `{fail: reason}` (an id) and `{pick: option}`. Anything else, a `HookException` from the runner, `pick` outside a choose, `pick` of an option that is not enabled, and `set` from a choose's hook (4B) fail the step with `hook_error`, subject to an enclosing `retry`. The hook of a choose runs once some option is enabled; its pick wins over an earlier hosted answer (passkey autofill).

**Runtime** (`faye-hooks`): `QuickJsRuntime` gives each run a fresh QuickJS instance with 16 MiB of linear memory (256 pages), from bytecode cached by hook hash. A bounded pool runs 8 hooks at once with 8 waiting; more are refused (`hook_error`) rather than queued. The deadline covers the run and its egress; at the deadline the worker is interrupted. Compiling a hook the runtime has not cached has its own 2 s budget, so the first run of a large hook after a restart does not spend its deadline compiling (found in review). The glue deep freezes `ctx` and `cap`, calls the default export, and turns every throw or rejection into an error result, because host exceptions do not reach the guest's `try/catch` and a rejected promise does not reach the host. The runtime is warmed up once at construction, since loading it (about 170 ms) is longer than a deadline. `cap.log.info` replaces control characters, so a hook cannot forge server log lines; a store that fails to answer fails the step, not the request.

**Capabilities**: `cap.http.getJson(target, query)` and `cap.log.info(...)`. The host function checks every call against the targets the hook declared and its tenant granted (`faye.tenants.<id>.egress`, none when unset), so calling the raw host function (`faye_cap.getJson`) gains nothing. Targets are named base URLs in `faye.egress.<name>`; `HttpEgress` sends a `GET` with the query, does not follow redirects, refuses responses over 64 KiB, and times out at what is left of the deadline.

**Store and checks**: `HookStore` by hash (`hook:{hash}` in Redis, never expiring, verified on read). `Publisher` checks a flow against the hooks that come with it plus those stored, and stores the hooks before the version. The checker adds two errors: a hook not stored ("hooks pinned"), and a hook declaring an egress target the tenant does not grant. For paths, a step hook either fails or may set any `vars`, which are unknown from then on; a choose's hook either fails (once an option may be enabled) or picks one of the options that may be enabled. The credential checks never depend on what a hook returns.

**Demo**: tenant `risk` (client `demo-risk`) runs `risk-totp.yaml`: password, plus TOTP (or enrolling it) when `risk.js` scores the sign in high through the `ip-reputation` target. The hook scores high when the service fails. Compose runs a stub of that service (`deploy/ip-reputation/`) on the JDK image the server already builds with.

### Consequences

* Good, because the exit criteria hold under test: the risk flow routes on the score, and the infinite loop, `{ succeed: true }` and reading or writing `auth` all end in `hook_error` or have no effect, with the real sandbox, through the HTTP API and in compose.
* Good, because the closed output lives in the engine. A sandbox escape could still only return one of four shapes, none of which completes authentication, and the engine's run time checks (`unauthenticated`, `mfa_required`, `acr_not_satisfied`) stay in front of any accept.
* Good, because hooks add no native code and about 1.2 MB of jars.
* Bad, because quickjs4j is pre 1.0 and a few days older than this record. Pinned to 0.1.1; the adversarial tests in `QuickJsRuntimeTest` must pass before any upgrade.
* Bad, because quickjs4j depends on Jackson 2.22.3, which lifts the whole server from Spring Boot's 2.21.4. Every server test passes on 2.22.3; Spring Boot has not been tested against it by its maintainers (unverified).
* Bad, because hook memory is bounded per run (16 MiB of linear memory) and by the pool (8 runs), not by a global budget; about 128 MiB at worst plus Endive's own overhead (not measured).
* Bad, because a host call blocks the guest. `getJson` is synchronous under an `async` surface, so a hook cannot run two calls in parallel.
* Bad, because the checker cannot see inside a hook and treats `vars` as unknown after a step hook. A flow that routes on a hook's output always shows both branches (as `risk-totp` does), and a later `set` does not make a var known again.
* Neutral: hooks see `Date.now` and `Math.random`. Hooks are not required to be deterministic; conformance vectors script hooks rather than run JavaScript.
* Bad, because `HttpRequest.timeout` bounds the wait for response headers, not the body. A target that trickles its body holds a worker until the interrupt at the deadline; whether reading the body stops on interrupt is unverified (test with a slow body stub).
* Bad, because the bytecode cache holds 256 hooks; past that, hooks compile again on their next run (within the compile budget, not the deadline).
* Neutral: `Publisher` stores every hook a published YAML declares, used or not.
* Neutral: there is no admin endpoint to test run a hook yet (M5, "Hooks editor and test run").

### Confirmation

* `cd server && FAYE_REQUIRE_REDIS=1 ./gradlew build`: `QuickJsRuntimeTest` (loop, the only worker back after a loop, a large hook's first run within 50 ms, forged log lines, loop after an await, memory bomb, frozen `ctx` without `auth`, no host globals, no state across runs, egress refusal, the raw host function, throws and rejections, no default export, overload refused); `MilestoneFourTest` (M4 exit through the HTTP API: risk routing with an HTTP egress stub, loop, `{ succeed: true }`, `auth`, ungranted egress refused at publish); `SandboxedHooksTest` (grant revoked at run time, store failure); `HookCheckTest`; `HookCompileTest`; `ConformanceTest` (hand written `hook-*` vectors including nine malformed outputs, and the generated `risk-hook` and `hook-pick` vectors).
* `deploy/smoke.sh` steps 8 and 9 against the compose stack (CI `e2e` job).
