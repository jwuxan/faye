---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Source lines at run time: a source map per version, IR paths in traces and logs

## Context and Problem Statement

Charter principle 5 says "errors point at what the author wrote". At compile and check time they already do: `YamlReader` and `FlowCompiler` give every IR node a `SourceRef` (`file:line:column`), and every `Diagnostic`, checker `Finding` and catalog `Problem` carries one (`AdminController.Problem`, `CheckerTest`, the `diagnostics/*.yaml` goldens).

At run time they do not. A published version is stored as canonical IR JSON (`faye_version.ir`), which has no positions, and `SqliteFlowVersions` reads it back with `IrCodec.readFlow(json)`, so every node's source is `SourceRef.NONE`. What a run reports today:

* A preview trace event names the step id or handler (`call password.verify invalid_credentials`, `hook risk hook_error`, `end incomplete`), not the YAML that produced it. A step with `set`, `hook`, `screen`, `use` and an `alternative` gives no way to tell which part failed, and nodes outside a step (an entry's `succeed`, a `retry` around several steps) have no step id at all.
* `Engine` line 470 builds `call.source() + ": unknown handler ..."`; for a stored version that prints an empty origin.
* Outside preview there is no trace. A production run that ends with `hook_error`, `host_error`, `incomplete`, `subject_mismatch` or a failed commit leaves the operator with a code and nothing to find the YAML by.
* The person signing in sees a typed error screen with a safe `code` and nothing they can hand to support.

The engine already knows where it is: every continuation frame names its node by a child index path from the entry body (`Frame.path()`, ir-spec.md, Evaluation), and `FlowState.Awaiting` persists that path. What is missing is the way back from an IR path to a YAML line once the version is stored, and a channel that carries it to the people who need it.

How should a failure at run time tell the flow author, the operator and the person signing in where it happened, without changing what the IR means and without telling an attacker how a tenant's flow is built?

## Decision Drivers

* Charter principle 5: errors point at what the author wrote; a flow can be tried before it is published.
* Charter principle 1: safe behavior is the default. A login page must not reveal a tenant's flow structure, file names or which check failed beyond the existing safe codes (account enumeration, flow probing).
* ADR 0024: the IR is a narrow waist and versions are content addressed by the hash of the canonical IR. Reformatting YAML, or adding a comment, must not make a new version.
* AGENTS.md rule 3: the engine never imports Hydra; by the same reasoning it should not know about YAML either. Positions are a frontend concern.
* AGENTS.md rule 2: an engine behavior change needs a conformance vector; a new parser needs a fuzz harness.
* Small and modular (principle 7): one mapping, built once, used by preview, admin and logs alike.

## Considered Options

1. **A. Positions inside the IR**: a source slot on every node in `ir.schema.json`, kept in `faye_version.ir`.
2. **B. A source map per version**: IR path to `SourceRef`, built from the compiled `Flow` at publish, stored beside the version's YAML and keyed by its hash; the engine reports IR paths, the app resolves them.
3. **C. Recompile on demand**: when a run fails, compile the stored YAML again and look the path up in the fresh `Flow`.
4. **D. Step ids only**: keep reporting the enclosing step id, and let the Admin UI find the step's line in the YAML.

## Decision Outcome

Chosen: **B**, with C used once as a backfill for versions published before this record.

### The source map

* `faye-ir` gains `NodePaths`, the one definition of a node's child index path within an entry (the same indexing `Frame` uses: `seq` children by position, the body of `step`, `when`, `retry` at index 0, a `choose` option by its index, then its body or guard). The engine's frame code and the source map both use it, so the two cannot drift.
* `SourceMap.of(Flow)` walks every entry with `NodePaths` and records `entry id + path -> SourceRef` for every node whose source is not `NONE`. Its JSON form is `{"format": 1, "origin": "acme.yaml", "entries": {"login": {"": [3, 3], "0": [5, 5], "0.0": [6, 7], ...}}}`: line and column per path, one origin per map (a flow compiles from one file; hook files are pinned by hash and positioned at their declaration).
* Stored in a new table, `faye_version_source_map (hash TEXT PRIMARY KEY, map TEXT NOT NULL)`, by Flyway migration `V3__version_source_map.sql` (ADR 0020), written in the same transaction as `faye_version_source`. The map belongs to the YAML stored for that hash: when two YAML texts lower to the same IR, the version keeps the first text and its map, and the Versions view already shows that text.
* Backfill (option C, once): on startup, for a version with a stored YAML and no map, the app compiles the YAML; if the compiled IR's hash equals the version's hash it stores the map, otherwise it stores none. A version without a map resolves every path to its step id only, as today. No existing row is rewritten.
* The IR, its grammar, its format number and every version hash are unchanged.

### What the engine reports

* `TraceEvent` gains `at: {entry, path}` on `step`, `option`, `screen`, `call`, `hook`, `retry`, `commit` and `end` events; `describe()` is unchanged, so existing vectors keep passing.
* A run that ends abnormally (any `end` but `succeed`, and every `host_error` and commit failure) returns a `FailureSite(entry, path, step, code)` in its `EngineResponse`, for every run, preview or not. Paths only; the engine never sees a `SourceRef` from a stored version and never formats one.
* The unknown handler error at `Engine` line 470 reports the `FailureSite` instead of `call.source()`.

### Who sees what

| Audience | Channel | What they get |
|---|---|---|
| Flow author, in preview | Trace feed (SSE), Preview panel, demo client result view | Every event with `at` resolved to `acme.yaml:14:7` and the node kind (`use`, `hook`, `fail`, ...). Clicking it moves the YAML editor and selects the block. |
| Flow author, editing | Admin UI Versions view | A failure site resolved against that version's map, so the line refers to the YAML that version was published from, not the current draft. |
| Operator | Server log | One structured `WARN` line per abnormal end: tenant, flow id, version hash, entry, step, path, resolved `file:line:column`, code, and the reference below. Ordinary outcomes a person caused (`invalid_credentials`, `invalid_code`, a retry) are not logged at `WARN`. |
| Person signing in | Error screen | The existing safe `code`, plus a `reference`: a random `ref_` id, unique per failure, also written to the log line. No file, line, step id or handler name. |

* The screen protocol change is additive (as implemented, on `ErrorResponse`; see As implemented): `ErrorScreen` gains an optional `reference` (`^ref_[A-Za-z0-9]{12}$`). Per AGENTS.md rule 1 it lands in `schema/screen.schema.json` first, then the Java types and `pnpm generate`.
* A failure that ends in a redirect to the client through the host (the host's rejection) carries no reference in this record; Hydra's `error_debug` would put it in the relying party's hands. Open gap, decided separately if needed.
* The map never reaches the Login UI: the screen API has no route to it, and the resolver lives in `faye-app` beside the preview and admin APIs (AGENTS.md rule 5).

### As implemented

Built as above, with these changes, each forced by something the proposal got wrong about the code:

* **The reference goes on `ErrorResponse`, not `ErrorScreen`.** `ErrorScreen` is a typed screen a flow shows itself (`screen: error`); no abnormal end reaches it. An abnormal end either becomes the host's rejection (Hydra redirects to the relying party: the open gap above) or, when the host throws, the screen API's `502 host_error`. So `ErrorResponse` gains the optional `reference` (`^ref_[A-Za-z0-9]{12}$`, `schema/screen.schema.json`), set only for `host_error` after a flow ended, and the Login UI shows it with its copy for `host_error`. Still additive.
* **`FailureSite` reaches the embedder through a `Failures` SPI** (`Engine.builder().failures(...)`, like `Previews`), **not inside `EngineResponse`.** `Redirect` and `Handoff` are records the host builds, and a host that throws leaves no response at all. A host that throws in `accept` or `reject` surfaces as `HostFailure`, which carries the site (already reported) and the host's exception as its cause; the app answers it `502 {error: host_error, reference}`. A failure followed by a host that throws is reported twice: the failure, then `host_error`, each with its own reference.
* **Sites the proposal left open:** a write that fails to commit fails at the `succeed` that ran it (commits run when the entry succeeds); a `choose`'s pick hook is at the `choose` (the map positions nodes, and the pick hook is not a child); `incomplete` is at the entry's body (the empty path), with no step. The unknown handler error says `unknown handler <name> at <entry>/<path>`: it is a catalog bug, not a run failure, so it has no reference.
* **A previewed draft's map is kept in memory** (`SourceLines`, the 64 most recent), not stored: a draft is not published, has no row in `faye_version_source`, and its lines are those of the YAML in the author's editor. After a restart, a preview flow still waiting on an old draft resolves no lines (its events keep `at` and step ids).
* `TraceEvent` keeps a constructor without `at` for callers written before it.

Open gaps (why this record is `in progress`):

1. The Admin UI Versions view does not resolve failure sites against a version's map yet; production failure sites are only in the server log.
2. `deploy/smoke.sh` has no check that a draft preview's trace shows a throwing hook at its line (needs the compose stack).
3. The log line is tested by its content (`SourceLinesTest.theLogLineHoldsTheReferenceAndTheLine`), not by capturing the logger's output.
4. The host's rejection carries no reference (unchanged from the proposal's open gap).

### Consequences

* Good, because a run fails at the YAML the author wrote, in preview and in production, which closes the run time half of principle 5.
* Good, because the IR, hashes, stored flow states and conformance flows are untouched; reformatting YAML still makes no new version.
* Good, because one mapping serves the trace, the Versions view and the log, and `NodePaths` gives engine and map one indexing.
* Good, because the person signing in gets something to report without learning anything about the flow.
* Bad, because a second persisted artifact per version: a map that disagrees with its IR would point at the wrong line. Mitigated by building it from the same compiled `Flow` in the same transaction, and by the hash check on backfill.
* Bad, because a version published from IR without YAML (a future API or another frontend) has no map until that frontend supplies one; its failures fall back to step ids.
* Bad, because a hook's failure points at the hook's declaration and the step that ran it, not the JavaScript line inside the hook. QuickJS stack positions in `HookRuntime` errors are a separate decision (they are tenant code and need the same audience rules).
* Neutral: the log line adds one `WARN` per abnormal end; volume is bounded by failed sign ins that are not the person's fault.

### Confirmation

1. `cd server && ./gradlew :faye-ir:test :faye-dsl:test`: `SourceMapTest.everyNodeOfTheShippedFlowsHasALine` (every node of every shipped flow in `faye-app/src/main/resources/flows/` resolves to a line greater than 0, through the map's JSON form), `SourceMapTest.reformattingYamlKeepsTheHashAndMovesTheLines`, `NodePathsTest` (every IR form's children, paths, canonical keys, and the map's JSON form). The engine resolves frame paths with `NodePaths` itself, so the two share one indexing by construction.
2. `cd server && ./gradlew :faye-engine:test`: conformance vectors `trace-at-call-failure.yaml`, `trace-at-hook-failure.yaml`, `trace-at-fail.yaml`, `trace-at-retry-exhausted.yaml` assert each event's `at` and the `FailureSite`; `effect-commit-fails.yaml` and `password-success.yaml` assert the failure site of a non preview run and its absence. Every vector checks that every traced event but `start` has an `at`, and every failure site, a node of the version that ran. `conformance/README.md` documents `@entry/path` and `expect_failure`.
3. `cd server && ./gradlew :faye-app:test`: `SourceLinesTest.publishStoresTheSourceMap`, `.backfillSkipsAVersionWhoseYamlNoLongerCompilesToItsHash`, `.aHostErrorShowsAReferenceAndNoSource` (validated against the screen protocol), `.theLogLineHoldsTheReferenceAndTheLine`; `MilestoneSixTest.aPreviewRunIsTracedAndCheckedAgainstThePredictedPaths` (every event of a draft preview carries its node and line); `FlywayMigrationTest` pins `V3__version_source_map.sql`.
4. Fuzz (ADR 0007): F17 `SourceMapFuzzTest.parse` reads arbitrary map text, path keys and paths with no exception, only refusals; F6 `EngineFuzzTest` asserts every `FailureSite` names a node of the version its flow ran, and its sweep that failure sites are reached.
5. `cd web && pnpm generate && pnpm typecheck && pnpm lint && pnpm test`: the generated contract has `ErrorResponse.reference`; `apps/admin/lib/preview.test.ts` checks that a trace event's source selects the block its line is in.
6. `deploy/smoke.sh`: not yet (open gap 2).

## Pros and Cons of the Options

### A. Positions inside the IR

* Good, because one artifact: nothing to keep in step.
* Good, because another engine implementation would see positions too.
* Bad, because positions enter the canonical IR and so the hash: moving a step down one line, or adding a comment above it, makes a new version, floods the history and defeats deduplication (ADR 0024, content addressed versions).
* Bad, because it changes the IR grammar and bumps the IR format for something that does not change what a flow means.
* Bad, because the IR stops being frontend neutral: a block editor or another DSL has no lines, only node ids.

### B. A source map per version (chosen)

* Good, because the IR and its hash are untouched.
* Good, because the engine stays ignorant of YAML; it reports paths it already has.
* Good, because the pattern is proven: JavaScript source maps keep generated code and positions apart for the same reasons ([Source Map spec](https://tc39.es/ecma426/)).
* Bad, because a second artifact to store, migrate and fuzz.

### C. Recompile on demand

* Good, because no new table.
* Bad, because a `file:` hook needs its file at hand to compile; the stored YAML alone is not enough once the file has changed.
* Bad, because a newer compiler may lower old YAML differently, so a silent mismatch is possible on every lookup, not once; the hash check would catch it but then there is no answer.
* Bad, because compile cost lands on the failure path, which is where an attacker can drive load. Used only once per version as the backfill, where these costs are paid at startup.

### D. Step ids only

* Good, because it already exists.
* Bad, because a step holds several parts (`set`, `hook`, `screen`, `use`, `alternative`, nested `steps`) and the id does not say which one failed; nodes outside steps have no id.
* Bad, because the id to line lookup would have to be redone in the web app, duplicating compiler knowledge (AGENTS.md rule 5).

## More Information

* Compile time positions: `server/faye-dsl/src/main/java/faye/dsl/FlowCompiler.java` (marks), `YamlReader.java`, `faye-ir/SourceRef.java`.
* Where positions are lost today: `server/faye-app/src/main/java/faye/app/SqliteFlowVersions.java` (`IrCodec.readFlow(json)` without sources).
* Continuation paths: `server/faye-engine/src/main/java/faye/engine/Frame.java`, ir-spec.md, Evaluation.
* Docs to update with the implementation: `docs/ir-spec.md` is unaffected (the IR does not change); `conformance/README.md` (`at`), the screen protocol section for `reference`.
