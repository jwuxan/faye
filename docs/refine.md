Read `AGENTS.md`, `CHARTER.md`, `docs/roadmap/dsl-and-ir.md`, `docs/roadmap/engine.md`, and ADRs 0001, 0005, 0007, 0008, 0011, and 0017 before you change anything. `AGENTS.md` rules apply throughout. In particular:
- Change `schema/` first.
- Every engine behavior change needs a conformance vector.
- Never hand-edit `conformance/generated/`.
- Write an ADR before changing the IR or a boundary.
- Never mark an ADR `accepted`; only a human does that.
- Never commit binaries.
- Never run `gradle wrapper`.

## North star

```
YAML (sign in DSL)     YAML (future sign up DSL)
        \                   /
   positioned document tree (format adapters)
                  |
   lowering / desugaring  ->  Core IR (S expression, JSON arrays)
                  |
   schema validation -> static checks (by entry kind) -> canonicalize + hash
                  |
   Engine (interpreter + explicit continuations)
        uses only: Core IR, Handler catalog, Screen catalog, FlowHost
   JS hooks: sandboxed sidekick (set / fail / pick only)
```

Invariants this work must establish:
1. **The Core IR is small, stable, and versioned.** It has 11 control-flow forms. New business capability arrives as a handler, screen, or predicate operator plus compiler lowering, never as a new form. Add a form only when control flow, suspension, or termination semantics change. That needs an ADR and an IR format version bump.
2. **The IR is independent of any frontend language but specific to authentication.** It knows entry kinds, credential binding, and amr. It knows nothing about YAML, Hydra, or `HostKind`.
3. **Security rules are checked once, on the IR, keyed by entry kind.** No `"login"` string literal remains in the engine, checker, or IR modules.
4. **The engine and checker depend only on contracts:** the IR, the handler catalog, the screen catalog, and `FlowHost`. They never depend on a frontend, and the checker never depends on an engine enum.
5. **The S expression is the abstract syntax; JSON arrays are its only serialization.** No S expression text reader is added.

**Arity rule.** Required arguments are positional. Every optional or future parameter goes into the trailing options/args object. A form's positional arity never changes within an IR format version.

## Current state (re-reviewed on `main` @ 711b3ab)

Done already (keep it):
- ADR 0001 is implemented. `IrCodec.readFlow` validates against `schema/ir.schema.json` at runtime, using form dispatch with `if`/`then` and reporting the deepest error pointer. The hand-written arity and pattern checks and `Predicate.NAMESPACES` are gone.
- State moved to SQLite (ADR 0017). `faye_flow` has `rev` CAS and an `expires_at` set once at creation, so continuations are bounded by TTL. Versions are never deleted, by design.

Gaps toward the north star (verified in code):

| # | Gap | Where |
|---|---|---|
| G1 | Section names are hard-coded and tied 1:1 to the host challenge kind | `Flow.SECTIONS`, the `section` enum in `ir.schema.json`, `HostKind.section()`, `Engine.start/run`, `FlowCompiler` line ~150 (`case "login", "consent"` plus "missing 'login' section"), `Checker.run` and `Report` (`p.section().equals("login")`), about 12 files in `web/apps/admin` |
| G2 | There is no IR format version. The `version` in `["flow", id, version, ...]` is the author's flow version. A new engine reinterprets pinned in-flight IR and stored versions silently | `ir.schema.json`, `IrCodec`, `FlowVersion`, `FlowStateCodec` |
| G3 | The grammar is still duplicated in the compiler: `FlowCompiler` has its own `ID`, `NAME`, `KEY` regexes and reads 38 SnakeYAML node types directly, mixing parsing, desugaring, and validation in one pass | `server/faye-dsl/.../FlowCompiler.java` |
| G4 | The handler contract is thin and enforced only in tests. `Handler` exposes `amr()` and `bindsSubject()` only. `conformance/handlers.yaml` is read only by tests, so nothing checks that production handlers match it. There is no effect classification and no argument schema | `faye-engine/Handler.java`, `faye-identity/IdentityHandlers.java`, `conformance/handlers.yaml` |
| G5 | Handler side effects run before the CAS. `Engine.submit` runs `run(...)`, which invokes handlers, and only then calls `store.replace`. Writes from `totp.enroll`, `webauthn.register`, and `totp.verify` (last step) persist even when the submit loses the race (ADR 0007). Sign up account creation would make this worse | `Engine.submit`, `IdentityHandlers`, `UserStore` |
| G6 | The screen set is a closed enum inside the engine, and the checker imports it | `faye-engine/ScreenType.java`, `Checker.structure` |
| G7 | The checker and engine disagree: the checker models `subject_mismatch` as retryable, while the engine ends the section (ADR 0011, open gap) | `Checker`, `Engine` |
| G8 | There is no normative IR semantics spec. Semantics are scattered across ADR 0005, `dsl-and-ir.md`, and the vectors. `dsl-and-ir.md` line 3 still says "The Admin UI edits the IR directly", which contradicts ADR 0010 | `docs/` |
| G9 | `flowId` is a bearer capability. `GET /api/flows/{id}` returns the nonce and `otp_enroll` data, and no browser binding exists (ADR 0007) | `FlowController`, `web/apps/login` |
| G10 | The version cache is unbounded (`ConcurrentHashMap`) | `SqliteFlowVersions` |
| G11 | `server/dump.rdb`, a Redis binary dump, is still tracked and was modified in PR #2, although Redis is gone. This violates rule 7 | repo root `server/` |

## Work plan: one PR per step, in order

Run `cd server && ./gradlew build` and, when web files change, `cd web && pnpm typecheck && pnpm lint && pnpm test && pnpm build` before each push. Maven Central may return HTTP 429 on the VM; if it does, rely on CI and say so in the PR. Regenerate vectors with `FAYE_REGENERATE=1 ./gradlew :faye-check:test`.

### PR 1: ADR and spec (docs only, plus G11)
- Add ADR `0024-core-ir-as-a-narrow-waist.md` (status `proposed`) recording the invariants above and the decisions in PRs 2 to 6. Consider these options and give their pros and cons:
  - entries vs. sections;
  - where the format version lives;
  - two-phase effects vs. idempotent handlers vs. a compensation log;
  - screen registry vs. enum.
- Add the row to `docs/adr/README.md` and link it from `decisions-and-risks.md` if it changes a D-entry.
- Add `docs/roadmap/ir-spec.md`, the normative semantics of every form:
  - evaluation, failure propagation, suspension, retry unwinding, and choose enablement and pick;
  - terminal outcomes;
  - environment namespaces and who may write each;
  - canonical form and hashing.
  - Cite the conformance flow and vector that pins each rule.
- Fix `dsl-and-ir.md` line 3 to match ADR 0010: YAML is the single source of truth, and the IR is compiled and never edited.
- Run `git rm server/dump.rdb` and add `*.rdb` to `.gitignore`.

### PR 2: Format version and entries (G1, G2, G7)
- Change the schema first:
  - New top level: `["flow", <irFormat:int, const 1>, id, version, entry...]`.
  - New entry: `["entry", id, kind, body]`.
  - `kind` is an enum of `authenticate` and `consent` only. Do not add `register`; the sign up ADR adds it together with its checker rules.
  - `id` uses the existing id pattern and is unique per flow (checked in Java, like duplicate sections today).
- Update the Java side:
  - Rename `Flow.sections` to `entries` (id → `Entry(kind, body)`) and delete `Flow.SECTIONS`.
  - `IrCodec` refuses any `irFormat` other than the supported set (constant `IrCodec.FORMATS = {1}`).
  - The hash covers the format.
- Decouple the host:
  - Delete `HostKind.section()`.
  - Add an engine-side `EntrySelector`: `LOGIN` → the flow's single `authenticate` entry, `CONSENT` → its single `consent` entry.
  - The checker errors if a flow has more than one entry of a kind until an ADR defines selection.
  - `FlowState` stores the entry id instead of deriving it from `kind`.
- Checker:
  - Key its rules by entry kind. Credential, MFA, and acr rules apply to `authenticate`. A `consent` entry must not call a `binds_subject` handler.
  - Remove every `"login"` literal in `faye-check`, `faye-engine`, and `faye-ir`.
  - Fix G7 so the checker models `subject_mismatch` exactly as the engine does. Add a vector that hits it on a non-final attempt.
- Compiler and admin:
  - The YAML surface stays the same: `login:` and `consent:` keys lower to entries `login`/`authenticate` and `consent`/`consent`. Existing tenant YAML must compile unchanged.
  - Admin web: section names come from one module (for example `lib/entries.ts`), not 12 files. No behavior change.
- Persistence:
  - `FlowStateCodec` writes a `stateFormat` field. Reading an unknown format or IR format fails closed: the flow reads as absent and the user restarts sign in.
  - Document in the ADR that breaking engine semantics require an IR format bump. Because flows live at most one TTL, the engine supports N and N-1 for one release.
- Migrate all `conformance/flows/*.json`, regenerate `generated/`, and update `IrCodecTest` pointers.
- Stored versions in SQLite are re-hashed on format change. A new Flyway migration (`V2__...`) must not edit V1; decide whether old `faye_version` rows are dropped (acceptable pre-release, but say so in the ADR) or read via the N-1 path.

### PR 3: Handler catalog contract and effects after CAS (G4, G5)
- Replace the metadata methods with `HandlerMeta meta()`:
  - `amr: Set<String>`;
  - `bindsSubject: boolean`;
  - `effect: none | write`;
  - `args`: a JSON Schema fragment for `(call name args)`.
  - Reserve `createsSubject` and `enrolls` in the ADR only. Do not add unused fields.
- Make `conformance/handlers.yaml` the contract:
  - Add a test in `faye-app` asserting that every registered production handler's metadata equals its entry there, and that every entry has a handler.
  - The checker validates `call` args against `meta().args` and reports an error with its `SourceRef`.
- Two-phase effects:
  - `Outcome` can carry deferred writes (`List<Effect>`; `Effect` is a functional interface run against `UserStore`).
  - The engine collects them during `run` and executes them only after `store.replace` succeeds.
  - Writes whose correctness depends on concurrency across flows (the TOTP last-step update) must be conditional in the store: compare-and-set on `totp_last_step`, and a conditional insert for a passkey credential id. A failed condition fails the submit closed.
  - Move every write in `IdentityHandlers` into effects.
- Tests and vectors:
  - Add a test with two concurrent submits on one flow: the loser gets `CONFLICT` and leaves no `UserStore` change.
  - Add a vector proving that effects are not run on a lost race (scripted handler).

### PR 4: Compiler pipeline (G3)
- In `faye-dsl`, introduce a positioned document tree (`Doc.Map`, `Doc.Seq`, `Doc.Scalar`, each with a `SourceRef`) and a `YamlReader` adapter. That adapter is the only class that imports SnakeYAML. Lowering consumes only `Doc`.
- Delete the `ID`, `NAME`, and `KEY` regexes from `FlowCompiler`:
  - Lowering builds the IR JSON, `IrCodec` validates it against the schema, and the failing JSON pointer maps back to the `SourceRef` of the node that produced it (keep a pointer-to-SourceRef table during lowering).
  - Diagnostics keep `file:line:col`.
  - Keep `FILE` (a hook file name is surface-only).
- Golden tests: add `faye-dsl/src/test/resources/golden/*.yaml` with expected canonical IR. Each tenant flow in `faye-app/src/main/resources/flows/` gets one. `FlowCompilerTest` compares them byte-for-byte.
- Add a display printer `IrCodec.display(Flow)` that renders the S expression notation used in docs. It is used in golden failure messages and the spec, and is never parsed.
- Do not add TOML or a JSON frontend. The adapter boundary is enough.

### PR 5: Screen catalog (G6)
- Replace the closed `ScreenType` enum with a `ScreenRegistry` of `ScreenSpec` values (`name`, `prepare`, `accepts`, `hosted`), mirroring `HandlerRegistry`. Built-ins are registered by default.
- The checker receives the registry and no longer imports `faye.engine.ScreenType`.
- Add a test that the registry names equal the screen names in `schema/screen.schema.json`.
- No protocol change, so the TS contract stays the same, and `pnpm generate` leaves no diff.

### PR 6: Runtime hardening (G9, G10)
- Bind the flow to the browser:
  - At `/login` and `/consent`, set an HttpOnly, Secure (when not localhost), SameSite=Lax cookie holding a random binder.
  - Store its hash in `FlowState`.
  - `GET /api/flows/{id}` and submit require a match and otherwise return 404 without revealing whether the flow exists.
  - Inspect `web/apps/login` first to see whether it calls the API from the browser or from its Next server. If from the server, forward the cookie.
  - Add an `ApiTest` for a mismatched or missing binder.
- Bound the `SqliteFlowVersions` cache with an access-ordered `LinkedHashMap` LRU (say 256 entries, synchronized). No new dependency.
- Check whether preview drafts call `FlowVersions.store()`. If they do, record in the ADR whether unreferenced, unpublished versions older than the flow TTL are deleted, and implement the decision.

## Out of scope
- Sign up: no `register` kind, no `creates_subject` logic, and no second DSL. It gets its own ADR after this lands.
- New IR forms. The cross-entry jump and the composite form "validate all, then commit" are deferred to the sign up ADR.
- Object-shaped IR nodes, an S expression text reader, TOML, a second engine, and multi-level IR.
- Changing `CHARTER.md`.

## Definition of done (whole plan)
- `grep -rn '"login"' server/*/src/main` returns only the YAML surface key in `faye-dsl` and the `FlowHost` and `HostKind` host code.
- Adding a handler or screen type touches no file in `faye-engine/Engine.java`, `faye-check`, or `faye-ir`.
- The IR schema, the spec, and the conformance vectors are the only grammar and semantics sources. The compiler holds no copy of the grammar.
- A lost CAS race leaves no side effects.
- CI is green on every PR, and each PR description lists which gaps (G#) it closes and what stays open.
- ADR 0024's status is `in progress` until PR 6 merges. Then leave it for the human to mark `accepted`.
