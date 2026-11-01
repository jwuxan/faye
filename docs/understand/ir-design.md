---
description: Why Faye compiles every flow to a small intermediate representation, how that representation is shaped, and what its shape lets the checker prove.
---

# How the IR is designed

Faye compiles every flow file to an intermediate representation (IR) before anything checks or runs it. This page explains why the IR exists, the choices behind its shape, and what those choices make possible. This page is for engineers who change the compiler, the checker or the engine, or who want to build another engine. Flow authors never need the IR. They write flows in the domain specific language (DSL).

> [!TIP]
> If you only write flows, you can stop here and read [Write flows in YAML](../flows/flow-syntax.md) instead. Come back when you want to know how Faye works inside.

The [Core IR specification](../ir-spec.md) is the normative definition. This page explains the specification. If the two differ, follow the specification.

## Why there is an IR

The charter gives the IR two jobs: interoperability, so that other tools and engines can work with flows, and modularity, so that each part of Faye can change on its own. In its words, the IR "lets the DSL, the checker, the editor and the engine evolve separately, and lets another engine implementation prove it behaves the same".

In practice, that means four things:

- **Parts change alone.** The YAML surface can gain blocks and settings. The Admin UI can change how it edits flows. Meanwhile, the checker and the engine stay the same. [ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md) replaced the whole YAML step syntax with blocks and did not change any IR form.
- **One target for every check.** The checker reads the IR, not YAML. Every safety rule is written once and holds for any front end. A rule can depend on the entry kind: sign in, consent or approve.
- **Another engine can prove itself.** The conformance suite in [`src/conformance/`](../../src/conformance/README.md) is a set of IR documents and scripted scenarios. Each scenario, called a vector, says what an engine must do with one input. An engine that passes every vector behaves like the reference engine.
- **Authors never see it.** People write YAML or use the block editor. The charter calls the IR "important but optional for authors". The charter also asks for the simpler path when the IR adds cost but does not help interoperability or modularity.

## The compiler model: source, bytecode, virtual machine

Faye is built like the toolchain of a programming language. If you know the Java virtual machine (JVM) or LLVM, you already know this shape:

- The **YAML flow file is the source code.** People read and write it.
- The **IR is the bytecode.** It is small, exact and versioned. Faye stores, hashes and runs the IR.
- The **engine is the virtual machine (VM).** It runs IR and nothing else.

The diagram shows the three layers. Front ends at the top produce IR. Back ends at the bottom consume it. The IR in the middle is the only thing that front ends and back ends share. That is why the IR is called a narrow waist.

```mermaid
flowchart TB
  subgraph front["Front ends: how people write flows"]
    yaml["YAML flow file"]
    blocks["Block editor in the Admin UI"]
    future["Other front ends (possible, not built)"]
  end
  compiler["DSL compiler (FlowCompiler)"]
  subgraph waist["The narrow waist"]
    ir["Core IR: 11 forms, versioned, hashed"]
    verify["Schema and verifier"]
  end
  subgraph back["Back ends: what reads the IR"]
    checker["Checker: proves rules on every path"]
    engine["Reference engine: runs flows"]
    gen["Path generator: writes test vectors"]
    other["Another engine (possible)"]
  end
  blocks --> yaml
  yaml --> compiler
  future -.-> ir
  compiler --> ir
  ir --> verify
  verify --> checker
  verify --> engine
  verify --> gen
  gen -. "conformance vectors" .-> other
  gen -. "conformance vectors" .-> engine
```

Today there is one text front end. The Admin UI's block editor writes the same YAML that an author can edit by hand ([ADR 0010](../adr/0010-m5-admin-ui.md)). [`FlowCompiler`](../../src/faye-core/src/main/java/faye/dsl/FlowCompiler.java) lowers that YAML to the IR. To lower means to translate into a simpler form at a lower level. Everything below the IR depends only on the IR and on the catalogs: the fixed lists of built in handlers and screens ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md)).

The table maps each part of Faye to its counterpart in the JVM and in LLVM.

| Role | Faye | JVM | LLVM |
|---|---|---|---|
| Source language | YAML flow file, block editor | Java, Kotlin, Scala | C, C++, Rust, Swift |
| Front end | `FlowCompiler` | `javac`, `kotlinc` | `clang`, `rustc` |
| Intermediate form | Core IR, as JSON arrays | Bytecode in `.class` files | LLVM IR |
| Specification | [Core IR specification](../ir-spec.md), [`ir.schema.json`](../../src/schema/ir.schema.json) | The JVM specification | The LLVM language reference |
| Load time check | JSON Schema and `Verifier`: shape, catalogs, hard limits | The bytecode verifier | The IR verifier |
| Static analysis | The checker: every path, safety rules | Static analysis tools | Analysis passes |
| Runtime | The engine: a step by step interpreter | The JVM | Code generators for each machine |
| Compatibility tests | [`src/conformance/`](../../src/conformance/README.md) vectors | The Java compatibility kit (TCK) | The LLVM test suite |
| Library interface | Handler and screen catalogs | The Java class library | Intrinsics and the runtime library |

### Where the analogy stops

The analogy explains the structure. It does not mean that Faye is a general language platform. The differences are on purpose:

- **The IR is not a general purpose language.** JVM bytecode can express any program. Faye's IR can only express sign in flows. Its only loop is a bounded `retry`, and it has no recursion and no arithmetic. These limits make it possible to list every path.
- **Faye checks more than a bytecode verifier.** The JVM verifier checks that bytecode is well formed and type safe. Faye's checker also proves security rules, such as "no path succeeds without a verified credential".
- **There is no optimizer.** The compiler never rewrites meaning. Take two flows that mean the same thing but are written differently, such as `{ not: { not: x } }` and `x`. They stay different versions. This is on purpose. The stored bytes are exactly what the checker checked.
- **The VM pauses between requests.** A flow waits for a person, often for minutes. The engine saves the whole state of the flow as data. It resumes the flow on the next request, even after a restart or in a new engine instance (`EngineTest.progressSurvivesANewEngineInstance`). A JVM keeps a running program in memory.

## YAML is a front end, not the contract

The contract between Faye's parts is the IR, not the YAML. Because of this choice, tenants and the project are never locked in to the YAML.

If the engine read YAML directly, every improvement to how flows are written would be an engine change. Every stored flow would also be bound to one version of one syntax forever. Faye avoids both:

- **Versions are stored as IR.** A published version is the IR's canonical bytes, addressed by their SHA-256 hash. Faye keeps the YAML beside the IR only so that the Admin UI can show the YAML. A sign in in progress runs the IR, never the YAML.
- **The surface can change while the IR stays.** This has already happened twice:
  - [ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md) replaced the whole step syntax with blocks. The IR did not change. The shipped flows still lowered to the same IR as before, which the test suite keeps in reference files called golden files. Stored versions kept running, because they are stored as IR.
  - [ADR 0026](../adr/0026-consent-as-a-step.md) turned consent from a section of its own into a step. The IR, the published versions, the stored flow states and the conformance vectors were untouched.
- **New front ends need no engine work.** A front end only has to produce IR that passes the schema and the checker. A visual designer, a builder library in another language, or an importer from another system could each target the same IR. None of these exists today. The point is that a new front end does not change the checker or the engine.
- **Names specific to one authorization server stay in the front end.** A flow file may write `hydra.` instead of `host.`, because the Hydra adapter gives the compiler that alias. The compiler replaces it. The IR only ever contains `host.`. So a stored flow does not depend on Hydra.

> [!NOTE]
> The YAML still matters. People review flows as YAML in pull requests, and the Admin UI reads and writes the YAML without loss. But the engine never reads the YAML.

## No business logic in the engine

The engine knows how to run eleven forms and how to keep sign ins safe. It knows nothing about any tenant, any product rule, or any authorization server. Each kind of logic has one place, outside the engine:

| Logic | Where it lives | Who changes it |
|---|---|---|
| Which steps run, in what order, under which conditions | The flow, as IR | The tenant, by publishing a version |
| How a credential is verified | A built in handler, such as `password.verify` | Faye's maintainers, with review |
| What a screen shows and accepts | A typed screen spec and the Login UI | Faye's maintainers, with review |
| Custom decisions, such as risk scores and routing | A sandboxed hook with a closed result | The tenant |
| Tenant policy, such as "require two factors" | Tenant settings | The operator |
| The protocol, tokens and sessions | The authorization server, behind a host adapter | Whoever runs the authorization server |

The engine keeps only the rules that must hold for every tenant. These rules say what each form means and when an entry may succeed. They say how the engine computes the authentication methods references (`amr`) and the authentication context class reference (`acr`). They also say how a flow saves and resumes safely.

The code enforces this split. [`BoundaryTest`](../../src/faye-engine/src/test/java/faye/engine/BoundaryTest.java) fails the build if the engine names Hydra, a built in screen or handler, a token claim, or a host parameter outside the host contract. When [ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md) introduced blocks, it rejected a `block` form in the IR. A `block` form would have made every engine resolve blocks. It would also have let a block's behavior change under a published version. Blocks stayed in the front end, as catalog entries that the compiler lowers to the `screen` and `call` forms that already existed.

The result is an engine that rarely changes. New sign in methods come as handlers, screens and blocks in the catalogs. New authorization servers come as hosts ([Embed Faye: the host SPI](host-spi.md)). New ways to write flows come as front ends.

Each fact about the IR has exactly one source:

| Fact | Source |
|---|---|
| Grammar: forms, arity, patterns, format numbers | [`src/schema/ir.schema.json`](../../src/schema/ir.schema.json) |
| Meaning: what each form does, what the checker requires | [Core IR specification](../ir-spec.md) |
| Executable pins of that meaning | [`src/conformance/`](../../src/conformance/README.md) vectors, hand written and generated |
| Handler contract | [`src/conformance/handlers.yaml`](../../src/conformance/handlers.yaml) |

The Admin UI does not keep its own copy of the grammar either. `pnpm generate` reads the schema and writes the id, key and path patterns, the namespaces and the operators into `apps/web/packages/contract/src/ir.ts`. Continuous integration (CI) fails when that file is out of date.

## The design choices

### JSON arrays as S expressions

Founding decision D2 in [ADR 0036](../adr/0036-retire-the-roadmap.md#founding-decisions) chose a YAML surface on top of an S expression IR stored as JSON. The S expression is the abstract syntax. JSON arrays are its only serialization. The head of each array names the form, and the rest of the array holds its arguments.

```json
["retry", 3, ["seq", ["screen", "password"], ["call", "password.verify"]]]
```

Docs and tests print the same tree in a Lisp style display, `(retry 3 (seq (screen password) (call password.verify)))`. Nothing parses that display. It is a view of the JSON, position for position (`IrCodec.display`).

This design needs no Lisp runtime and no second parser. Any language with a JSON library can read the IR. A standard JSON Schema validator checks its shape ([ADR 0001](../adr/0001-validate-ir-with-json-schema.md)).

Every node follows one arity rule: `[head, required arguments..., options?]`. Optional parameters live in one trailing options object, at the end of the node. Examples are `{"guard": ...}` on an option and `{"path": ...}` on a screen. Within a format, the number and order of a form's positional arguments never change. So a reader always knows what each position holds.

### A closed language that always terminates

Founding decision D3 made the language closed. Its only loop is `retry`, and the number of attempts must be 1 to 10. It has no recursion and no calls between flows. Every flow ends.

Because every flow ends, the checker can list every path through an entry. This list of paths is the basis of every static guarantee in [What the checker can prove](#what-the-checker-can-prove).

A new capability comes as a handler, a screen, a block or a predicate operator, not as a new form. Faye adds a form only when control flow, suspension or termination change. A new form needs an architecture decision record (ADR) and a new format number ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md)).

### Eleven forms

The IR has eleven control forms, plus three structural ones: `flow`, `entry` and `opt`.

| Form | Shape | What it does |
|---|---|---|
| `seq` | `(seq node...)` | Runs its children in order. |
| `step` | `(step id body)` | Names a boundary. Traces, audit and screen paths use the step id. |
| `screen` | `(screen type options?)` | Waits for the person to answer a typed screen. |
| `call` | `(call handler args?)` | Runs a built in handler. Only a handler can verify a credential. |
| `hook` | `(hook id hash)` | Runs tenant JavaScript, which may only set `vars` or fail. |
| `choose` | `(choose (opt...) {:pick hook}?)` | Offers the enabled options and runs the chosen one. |
| `when` | `(when predicate body)` | Runs its body if the predicate holds. |
| `retry` | `(retry attempts body)` | Runs its body again after a failure, up to `attempts` times in total (1 to 10). |
| `set` | `(set key literal)` | Writes `vars.key`. |
| `succeed` | `(succeed)` | Ends the entry. It succeeds only if the entry kind's conditions hold. |
| `fail` | `(fail reason)` | Ends the entry with a reason. It is never retried. |

No form marks a person as signed in. Only `succeed` ends an entry with success. At that point, the engine checks that a credential verified the subject.

### Entries and entry kinds

A flow holds one or more entries: `(entry id kind body)`. The kind is a typing context. It decides what `succeed` means, which rules the checker applies, and what the body sees when it starts.

| Kind | Started by | `succeed` means |
|---|---|---|
| `authenticate` | the authorization server's login challenge | the subject is signed in, with the `amr` and `acr` its credentials proved |
| `consent` | the consent challenge, after the sign in was accepted | the access the client asked for is granted |
| `approve` | a QR code scanned on a second device | the waiting sign in is approved for this subject |

The engine picks an entry by kind, not by name. The checker refuses a flow with no `authenticate` entry, or with two entries of one kind. Each rule belongs to a kind. For example, a `consent` entry may not verify a credential, and an `approve` entry must end with exactly one approval.

The YAML surface hides the split. The steps of `login:` lower to the `authenticate` entry, up to the first step that asks for consent. That step and every step after it lower to the `consent` entry ([ADR 0026](../adr/0026-consent-as-a-step.md)).

### Predicates are data

A condition is not a string of code. It is a bare path, such as `"user.has_totp"`, or an object with exactly one operator:

```json
{"and": [{"ne": ["auth.acr", "faye:2fa"]}, {"has": ["host.acr_values", "faye:2fa"]}]}
```

The grammar limits paths to seven namespaces: `request`, `client`, `host`, `tenant`, `user`, `vars` and `auth`. Because a predicate is data, tools can read it:

- If a predicate reads only what is known before the flow runs, such as `tenant.mfa_required`, the checker decides it. Otherwise the checker follows both outcomes.
- The checker refuses a `host.*` or `client.*` path that is not part of the closed host and client contracts ([ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)). So a flow cannot depend on one authorization server.
- The Admin UI's condition builder edits predicates as structures, and checks them as you type.

A new operator extends the IR without a new form. It still needs a new format number, because an older reader would refuse it.

### Hooks pinned by hash

A `hook` node names its hook by id and by the SHA-256 hash of the hook's definition. The definition is the hook's source, egress targets and timeout, as canonical JSON. A change to any of the three makes a different hook with a different hash.

The hash is part of the flow's own bytes. So a published version always runs exactly the hook code that the checker checked with it. To edit a hook, you publish a new version.

### Canonical JSON and the version address

The canonical form of a flow is its JSON in UTF-8, with no extra whitespace, object keys sorted, and empty options objects left out. A version's address is `sha256:` followed by the hex SHA-256 of those bytes.

Canonicalization changes only syntax. The compiler never simplifies a predicate or rewrites meaning, because that would change hashes and node paths. Two YAML files that lower to the same IR are the same version. A new layout of the YAML, or a new comment, does not create a new version.

A store checks a version against the bytes it kept, never against a fresh serialization.

### Format numbers

The IR format number is the second element of every flow: `["flow", 1, ...]`. It is inside the canonical bytes, so it is part of the hash.

| Format | What it is |
|---|---|
| 1 | The first versioned format: eleven forms, `authenticate` and `consent` entries |
| 2 | Format 1 plus the `approve` entry kind ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)) |

The compiler writes format 2 only for a flow with an `approve` entry, and format 1 for any other. So adding format 2 changed no existing version or hash. A format 1 document that names the `approve` kind is refused.

The rule for a new format is this. Each of these changes needs a new format number and an ADR: a change to what a form means, a new form, a new predicate operator, or a new options key. A reader refuses any format it does not know before it reads anything else. It never guesses. When a reader reads a stored version in an older format, the reader upgrades it in memory. The reader keeps the version's hash, and it must keep every node at the same position.

### State format

Faye saves a flow in progress as a continuation. A continuation is a stack of frames. Each frame names its IR node by a path of child indexes from the entry body. The meaning of those frames has its own version number, the state format. The current state format is 1.

A new optional field needs no new state format. A changed meaning does. The engine reads the current and the previous state format. A state in any other format, or one that does not fit the version it pinned, reads as absent. The person then starts the sign in again. The engine fails closed: it refuses the state rather than guess.

This is why an IR upgrade must keep node positions. If an upgrade cannot keep them, the state format changes too.

### Source references

The compiler gives every IR node a source reference: the file, line and column of the YAML that produced it. Every compile error and checker finding points there, such as `tenant.yaml:8:11`.

Grammar errors work the same way. The compiler keeps no copy of the grammar. It builds the JSON, validates it against the schema, and maps each error's JSON pointer back to the YAML that produced the node. The compiler test fixture `grammar.yaml` pins this, for example `13:14: variable name: does not match the regex pattern ^[a-z][a-z0-9_]*$`.

Source references are not part of the JSON, so they never change a hash. A stored version is read back without them. A source map for each version is planned and not built yet. It would let run time errors point at YAML lines too ([ADR 0027](../adr/0027-source-lines-at-run-time.md)).

## Worked example: a password only flow

This is the shipped flow `apps/server/faye-app/src/main/resources/flows/password-only.yaml`:

```yaml
# Password only: identity first, then a password with three attempts.
flow: password-only
version: 1

login:
  steps:
    identify:
      block: identifier
    credential:
      block: password
```

The compiler lowers it to this IR, shown in the display notation. The golden file `src/faye-core/src/test/resources/golden/password-only.ir` pins it.

```lisp
(flow 1 password-only 1
  (entry login authenticate
    (seq
      (step identify (seq (screen identifier) (call identity.resolve)))
      (step credential (retry 3 (seq (screen password) (call password.verify))))
      (succeed))))
```

Each line of YAML became something concrete:

- `login:` became the entry `login` of kind `authenticate`. There is no consent step, so there is no consent entry.
- `block: identifier` became the identifier screen and the `identity.resolve` handler. This block gets no `retry`, because a mistyped identifier does not fail. An unknown identifier resolves like an account that has only a password.
- `block: password` became the password screen and `password.verify`, inside `(retry 3 ...)`. Three attempts is the Password block's default.
- The compiler added `(succeed)` at the end of the entry.

The canonical JSON is the only form that is stored and hashed:

```json
["flow",1,"password-only",1,["entry","login","authenticate",["seq",["step","identify",["seq",["screen","identifier"],["call","identity.resolve"]]],["step","credential",["retry",3,["seq",["screen","password"],["call","password.verify"]]]],["succeed"]]]]
```

Its address is `sha256:129be1ff8b8c89f74745e796de0186aa71b15144df6941887b3521eb865fbb8d`. The conformance suite holds the same document as `src/conformance/flows/password-only.json`.

## A richer lowering

The shipped flow `acme.yaml` uses a hook, choices with conditions, a condition on a whole step and a changed attempts count. This slice is lines 14 to 51:

```yaml
hooks:
  risk:
    file: risk.js
    capabilities: { http: [ip-reputation] }
    timeout_ms: 500

login:
  steps:
    identify:
      block: identifier
      after: risk
    credential:
      alternative:
        password:
          block: password
        email:
          if: user.email_verified
          block: email-code
        phone:
          if: user.has_phone
          block: sms-code
        # Scanned and approved on a phone, which runs the approve steps below
        qr:
          block: qr
    second-factor:
      if: { eq: [vars.risk, high] }
      alternative:
        totp:
          if: user.has_totp
          block: totp
        enroll:
          if: { not: user.has_totp }
          block: enroll-totp
    # Offered to people without phone codes; "Not now" skips it
    add-phone:
      if: { not: user.has_phone }
      block: add-phone
      with: { attempts: 1 }
```

This is the matching part of the golden file `acme.ir`:

```lisp
(step identify
  (seq
    (screen identifier)
    (call identity.resolve)
    (hook risk sha256:3281897b274bd5d56dd70e10e3052884f9754c1a3560cc2f24b20689117d878b)))
(step credential
  (choose
    ((opt password (retry 3 (seq (screen password) (call password.verify))))
      (opt email
        (retry 3 (seq (screen code_sent {:channel email}) (call email_code.verify)))
        {:guard user.email_verified})
      (opt phone
        (retry 3 (seq (screen code_sent {:channel sms}) (call sms_code.verify)))
        {:guard user.has_phone})
      (opt qr (retry 3 (seq (screen qr) (call qr.redeem)))))))
(step second-factor
  (when
    {:eq (vars.risk high)}
    (choose
      ((opt totp (retry 3 (seq (screen otp) (call totp.verify))) {:guard user.has_totp})
        (opt enroll
          (retry 3 (seq (screen otp_enroll) (call totp.enroll)))
          {:guard {:not user.has_totp}})))))
(step add-phone (when {:not user.has_phone} (seq (screen phone_enroll) (call phone.enroll))))
```

The table maps each YAML key in the slice to what it became.

| YAML | IR | Why |
|---|---|---|
| `after: risk` | `(hook risk sha256:...)` after the block's parts | A hook runs before or after a block, never as a step. The hash pins `risk.js`, its egress target and its 500 ms timeout. |
| `alternative:` | `(choose (opt ...) ...)` | Each option is one credential block. |
| `if:` on an option | `{:guard ...}` on the `opt` | A guard decides whether the option is offered. |
| `if:` on a step | `(when ...)` around the whole step body | The step runs only when the condition holds. |
| `block: email-code` | `(screen code_sent {:channel email})` and `email_code.verify` | The block fixes its screen, the screen's channel and its handler. |
| default attempts | `(retry 3 ...)` around the screen and the call | The retry wraps only the block's own parts. |
| `with: { attempts: 1 }` | no `retry` | One attempt needs no retry. |

Two details matter for the checker. A hook sits outside the block's `retry`, so `risk` runs once, not once per attempt. Also, `vars.risk` is unknown until the hook runs, so the checker follows both outcomes of `{:eq (vars.risk high)}`.

The full flow also has an `approve:` section, so `acme.ir` starts with `(flow 2 acme 1` and holds three entries: `login`, `consent` and `approve`.

## What the checker can prove

The checker runs at publish, at rollback, before a preview and at startup. Its core is path enumeration. The checker lists every path through each entry once.

Because the language has no unbounded loops and no recursion, the set of paths is finite. On each path:

- The checker decides a predicate if the predicate reads only what is known before the flow runs. That is `tenant.*`, `vars` written by `set`, and `auth.amr` and `auth.acr` from the credentials verified so far on that path. Otherwise both outcomes are paths.
- A call either fails or succeeds. A call that succeeds proves any nonempty part of its handler's `amr`. A handler that binds the subject may also prove a different person. That outcome ends the entry with `subject_mismatch`.
- A retry's attempts may fail before one succeeds.
- A hook either fails or sets `vars`. Those values are unknown from then on. A pick hook either fails or picks any option that may be enabled.

So the list holds more paths than can really happen. Some enumerated paths may be impossible in practice. But every real run follows one of them. So a rule that holds on every enumerated path holds on every run.

For the password only flow, the checker reports `login: 3 success paths, 5 failure paths; assurance pwd (faye:1fa) to pwd (faye:1fa)`.

On those paths the checker proves, among other rules:

- Every success path of an `authenticate` or `approve` entry verifies a credential that binds the subject.
- Every credential runs after a handler identified the person.
- A handler that needs a signed in subject, such as `totp.enroll`, runs only after a credential verified one.
- If the tenant requires multi factor authentication (MFA), every success path reaches `faye:2fa`.
- A `consent` entry verifies no credential.
- An entry runs at most 32 hooks and 5,000 ms of hook time over all its requests.

When a rule fails, the finding names the path. For example: `a path succeeds without verifying a credential: identify > credential > (condition at 8 false) > succeed with no credential`.

Two tests check that the checker agrees with the engine. The path generator takes each enumerated path of the conformance flows that some input can take. It turns the path into a vector and runs the engine along it. If the engine ends differently, the build fails ([`src/conformance/generated/`](../../src/conformance/README.md)). A fuzz harness, `EngineFuzzTest`, searches for flows that the checker accepts and the engine then cannot run. The build replays its stored inputs ([ADR 0007](../adr/0007-fuzz-and-adversarial-testing.md)).

Enumeration has a cost. The number of paths multiplies with every choice, condition and retry. An entry with more than 10,000 paths is an error, not a partial check. The message says `entry 'login' has more than 10000 paths or a path longer than 10000 steps; split it`. Acme's flow, the largest shipped one, has 5,980 paths. It allows one attempt at adding a phone to keep that number down: with the default three attempts, it would have 8,752.

## Alternatives that were rejected

The founding decision log in [ADR 0036](../adr/0036-retire-the-roadmap.md#founding-decisions) records these alternatives. It gives one reason for each choice, not a separate reason for each rejected option.

| Decision | Alternatives considered | Reason recorded for the choice |
|---|---|---|
| D2: a YAML surface on top of an S expression IR in JSON | Code first flows; BPMN; string expressions | Data is diffable, checkable and editable by a UI. JSON arrays need no Lisp runtime. |
| D3: a closed language that always terminates | An embedded general purpose language | It enables path enumeration, static safety checks and generated tests. |

Later records rejected other shapes for the IR, each with its own reason:

| Rejected | Record | Reason |
|---|---|---|
| A text S expression reader | [ADR 0001](../adr/0001-validate-ir-with-json-schema.md) | There is no S expression text to read. It would add a second syntax and a second parser. |
| The format number in an envelope around the IR | [ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md) | Every document gains a wrapper, and the format could be stripped from the bytes it describes. |
| An attributes object after the head of every form | [ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md) | It would move the options of `screen` and `call` and break the trailing options rule. |
| A `block` form in the IR | [ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md) | It changes what the IR means, so every engine must resolve blocks. A block's behavior could also change under a published version, which breaks D5. |
| One IR entry for both login and consent | [ADR 0026](../adr/0026-consent-as-a-step.md) | It changes what the IR means. An entry would span two host challenges. That change needs a new format number, a new kind of suspension in the engine, new checker rules and new vectors. The only gain is a change to how a flow is written. |

## Next steps

- Read the normative [Core IR specification](../ir-spec.md).
- See every key of the YAML surface in the [Flow DSL reference](../reference/dsl.md).
- Learn how the engine runs the IR in [How the engine works](engine.md).
- Browse the conformance suite in [`src/conformance/`](../../src/conformance/README.md).
