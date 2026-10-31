# Core IR specification, format 1

This is the normative meaning of the Core IR ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md)). Each fact has one source:

| Fact | Source |
|---|---|
| Grammar: forms, arity, patterns, the format number | [`schema/ir.schema.json`](../../schema/ir.schema.json) |
| Semantics: what each form does, when a flow succeeds, what is checked | this document |
| Executable pins of the semantics | [`conformance/`](../../conformance/README.md): hand written `vectors/` and the `generated/` paths |
| Handler contract | [`conformance/handlers.yaml`](../../conformance/handlers.yaml) |
| Screen protocol | [`schema/screen.schema.json`](../../schema/screen.schema.json) |
| The YAML surface and how it lowers | [dsl-and-ir.md](dsl-and-ir.md) |

Each rule below names the vectors that pin it. A rule without a vector is a gap. An engine conforms when it passes every vector; `server/faye-engine` is the reference engine.

## Notation

The IR's only serialization is JSON arrays. The S expression notation used here is a display of that JSON, position for position (`IrCodec.display`): an array is a list, an object is `{:key value}`, and a string is bare when it reads as a name. Nothing parses it.

```lisp
(flow 1 default-login 1
  (entry login authenticate
    (seq
      (step identify (seq (screen identifier) (call identity.resolve)))
      (step credential (seq (screen password) (call password.verify)))
      (succeed))))
```

Every node is `[head, required positional arguments..., options?]`. A form's positional arity is fixed within a format; every optional parameter is a key of the trailing options object. `seq` and `flow` take a variadic tail and no options object.

## Document

`(flow format id version entry...)`.

* `format` is the IR format, `1` here. A reader refuses a format it does not support before anything else.
* `id` names the flow; `version` is the author's number for it. Neither affects execution.
* There is at least one entry. Entry ids are unique within a flow.

## Entries

`(entry id kind body)` is a body the host can start. Its kind is a typing context: it decides what `succeed` means, what the checker requires of the body, and what the body sees when it starts.

| Kind | Started by | Sees at start | `succeed` means |
|---|---|---|---|
| `authenticate` | a login challenge | `auth` empty, or the host's subject bound for a step up | see below |
| `consent` | a consent challenge | the `user` and `auth` of the authenticate entry that ran for this subject, if the host links one | the access the host asked for is granted |

The engine runs the flow's only entry of the kind a challenge maps to (`EntrySelector`: login to `authenticate`, consent to `consent`). The checker refuses a flow with no `authenticate` entry or with two entries of one kind.

**Starting an authenticate entry.** A client whose tenant has no published flow is rejected (`flow_unavailable`, `unknown-tenant`). If the host remembers a session (`host.skip` with `host.subject`) and one factor satisfies both the client's `host.acr_values` and the tenant's policy, the login is accepted at once with an empty `amr` and `acr` `faye:1fa`, and no entry runs. If it remembers one that does not, the entry runs as a step up with that subject bound. A request that names a subject without `host.skip` (a forced re-authentication) runs the entry with no subject bound. Pinned by `tenant-mfa-remembered-session`, `subject-mismatch-on-first-attempt`; `EngineTest.rememberedSession*`.

**Starting a consent entry.** If the host relays the flow id of a finished login for the same subject (`host.context.flowId`), the consent runs on that login's version and sees its `user` and `auth`; otherwise it runs on the tenant's current version with only the subject. A consent request that names no subject is rejected (`no_subject`). A remembered consent (`host.skip`), or a version with no consent entry, is granted without running anything; a version whose consent entry cannot be selected, or a linked login's version that can no longer be read, is not run (`flow_unavailable`). Pinned by `consent-third-party`, `consent-first-party`, `consent-denied`, `preview-consent-flow-id`.

**Succeeding an authenticate entry.** Reaching `succeed` ends the entry successfully only if, in this order:

1. a subject is bound and at least one credential proved an amr value; else `unauthenticated` (`no-credential`);
2. the `acr` computed from what verified meets the tenant's policy; else `mfa_required` (`tenant-mfa-required`);
3. it meets the client's `host.acr_values`, a list or OIDC's space separated string, of which only the values Faye knows count; else `acr_not_satisfied` (`acr-not-satisfied`, `acr-values-as-a-string`).

`amr` is every amr value proved, each once, in the order first proved; `acr` is derived from it ([ADR 0006](../adr/0006-assurance-amr-acr.md)): two distinct factor classes give `faye:2fa`, one gives `faye:1fa`. Neither can be written by a flow or a hook. Pinned by `two-credentials`, `passkey-without-uv`, `password-success`.

## Environment

A flow reads its environment through predicate paths `namespace.key...`. A missing path is null: falsy, equal to nothing, absent for `exists`.

| Namespace | Holds | Written by |
|---|---|---|
| `request` | IP, user agent, locale of the request that started the flow | the engine, at start (a preview scenario may override it) |
| `client` | display data of the client: `client_id`, `client_name`, `first_party`, `metadata`; the engine decides nothing from it (the host decides the tenant and the preview flag) | the host, at start |
| `host` | the request as the host relays it, exactly the fields of the host contract below | the host, at start |
| `tenant` | the tenant's settings when the flow started: `mfa_required`, and `name`, its display name, when it has one | the engine, at start |
| `user` | what handlers learned about the person | handlers, by merging fields on success |
| `vars` | the flow's own values | `set`, and hooks in steps |
| `auth` | `subject`, `amr`, `acr` | the engine only, from credentials that verified |

`user` merges shallowly and keeps its fields across retry attempts. Pinned by `login-hint`, `tenant-mfa-predicate`, `picker`, `hook-set-cannot-write-auth`.

### Host contract

`host.*` is closed ([ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)): a host maps its protocol into these fields (`HostParams`) and cannot add one, so a flow runs unchanged on any host. The checker refuses a predicate path `host.<x>` for any other `<x>`, and any path below a field other than `context`, at publish and preview; a stored version that reads one still loads, and reads null.

| Field | Type | Means | Supplied by |
|---|---|---|---|
| `subject` | string, absent when none | who the host already knows: a remembered session (login with `skip`), a step up, the subject of a consent | redirecting authorization servers; an embedding host for a step up |
| `skip` | boolean | the host remembers this subject (login) or this grant (consent) | redirecting authorization servers; false otherwise |
| `acr_values` | list of strings | assurance the client asked for, most preferred first | any host that relays OIDC `acr_values`, else empty |
| `requested_scope` | list of strings | scopes the client asks for; the consent screen lists them | OAuth2 hosts, else empty |
| `requested_access_token_audience` | list of strings | audiences the client asks for | OAuth2 hosts, else empty |
| `login_hint` | string, absent when none | who the client expects to sign in; prefills the identifier | any host |
| `ui_locales` | list of strings | the person's preferred languages | any host, else empty |
| `context` | object | the host's own data carried from one request to the next (a consent receives what the login's accept returned), readable as `host.context.<key>` | any host, else empty |

Lists, `skip` and `context` are always present; an empty list is falsy, like null.

## Evaluation

A flow runs from its entry's body as a small step machine over an explicit continuation: a stack of frames, each naming a node by its child index path from the entry body. A run continues until the flow waits on a screen, or the entry ends. Between requests the flow is plain data ([engine.md](engine.md)); nothing of it lives in the engine.

**Suspension and resumption.** A flow that reaches a screen waits on it, with a fresh single use nonce. An answer must carry that nonce, else `stale_nonce`; must be a well formed answer to that screen (the screen catalog decides), else `invalid_submission`. Both refusals change nothing. The run then continues from where it waited, with the answer as the input to the handlers it reaches. Server data the screen issued (a WebAuthn challenge, a TOTP secret, the picker's options) is kept with the waiting flow and handed to handlers as the answer's context, never taken from the person.

**Concurrency.** Each saved state has a revision. A run's state is saved only if the revision it started from is still current. A run that loses applies nothing it staged (see Effects) and is answered with the screen the winning answer led to, as if it had been that answer, or `flow_finished` if the winner finished the flow; only the winner carries the host's redirect, and only the winner tells the host. Only the browser that holds the flow's binder can lose a race to itself, so this reveals nothing. If the stored revision did not move, the answer is `conflict`. Pinned by `double-submit-sees-the-winners-screen`, `double-submit-after-the-flow-finished`, `effect-lost-race`, `EngineTest.concurrentDoubleSubmitAcceptsOnce`.

**Binding.** A flow belongs to the browser that started it. Every read and answer presents the binder it was started with; a wrong or missing binder is answered exactly as a flow that does not exist (`flow_not_found`). Pinned by `binder-mismatch`, `FlowApiTest`.

## Forms

### seq

`(seq node...)` runs its children in order. It completes when the last child does. A failure in a child propagates (see Failures). Pinned by `identity-resolve-fails`.

### step

`(step id body)` runs its body. It names a boundary: the innermost enclosing step of a waiting screen is reported with the screen, and is the unit of audit and preview traces. Step ids are unique within a flow (checker). Pinned by `nested-steps`.

### screen

`(screen type options?)` makes the flow wait on a screen of that type from the screen catalog, prepared with the options; the answer is the input of the run that resumes it. If the run's input also answers this screen (a hosted answer, see Screen catalog), the flow does not wait and the screen completes at once. A screen reached again at the same path, by a retry, keeps the server data it issued before (an enrollment secret). A screen whose credential issuer cannot issue does not wait: it fails with `issuer_error` like a failing call, so an enclosing `retry` reruns it and otherwise the entry fails. Pinned by `password-retry`, `passkey-autofill`, `step-up-enroll`, `issuer-fails-then-retries`, `issuer-fails-closed`.

### call

`(call handler args?)` invokes a handler of the handler catalog with the current environment, the run's input and the args.

* On failure, the call fails with the handler's code (see Failures).
* On success of a handler that binds the subject, the subject it proved must equal any subject already bound; otherwise the entry ends with `subject_mismatch`, never retried: a credential for someone else is not a typo. Pinned by `subject-mismatch-on-first-attempt` and the generated `mfa-policy` paths that end in `subject_mismatch`.
* On success, `user` merges the fields the handler returned. If the handler is a credential, what it proved (its declared amr, or a nonempty part of it) is recorded in `auth`, and a subject binding handler binds its subject. Pinned by `password-success`, `passkey-without-uv`.
* Writes the handler returns are staged with the entry (see Effects); a call that ends in `subject_mismatch` stages none.

### hook

`(hook id hash)` runs the tenant's hook stored under `hash` on a read only snapshot of `request`, `client`, `host`, `tenant`, `user` and `vars` (never `auth`). Its result is `{}` (nothing), `{set: {...}}` (merged into `vars`; names are `key`s of the schema), or `{fail: reason}` (fails with that reason, an `id` of the schema), within the limits of [hooks.md](hooks.md). Anything else, and a throw, timeout or memory overrun, fails with `hook_error`. A hook can never succeed a flow, bind a subject, write `auth` or show a screen. Pinned by `hook-*`, [hooks.md](hooks.md).

### choose

`(choose (opt...) {:pick hook}?)` offers its enabled options and runs the chosen one.

1. An option is enabled when it has no guard or its guard holds now.
2. No option enabled: the choose fails with `no_method` (`no-method`).
3. With a pick hook and at least one option enabled, the hook runs with the enabled option ids as `options`. It may return `{}`, `{fail: reason}` or `{pick: id}` naming an enabled option; `{set: ...}`, a pick of anything else, or a runtime failure fails with `hook_error` (`hook-pick*`).
4. The chosen option is, by precedence: the hook's pick; else the option whose first screen the run's input already answers (a hosted answer); else the only enabled option.
5. Otherwise the flow waits on the `method_picker` screen, whose answer must name an enabled option (`picker`, `picker-single-option`, `preview-trace-choices`).

### opt

`(opt id body {:guard predicate}?)` is one alternative of a choose. Option ids are unique within their choose. A `when` in an option's body is an ordinary `when`: it does not decide whether the option is offered.

### when

`(when predicate body)` runs its body if the predicate holds now, else completes. Pinned by `tenant-mfa-predicate`, `hook-low-risk`.

### retry

`(retry attempts body)` runs its body up to `attempts` times in total. A failure inside the body, on any attempt but the last, unwinds to the retry and runs the body again; the next screen the flow waits on shows the failure's code and the attempts left. `vars`, `user` and `auth` keep what earlier attempts did. Terminal failures are not retried. Pinned by `password-retry`, `password-retry-exhausted`, `preview-trace-retry`.

### set

`(set key literal)` writes `vars.key`. Pinned by `picker`.

### succeed

`(succeed)` ends the entry, successfully if its kind's conditions hold (Entries).

### fail

`(fail reason)` ends the entry with `reason`, never retried. Pinned by `explicit-fail`.

## Failures

A failure is retryable or terminal.

| Failure | Raised by | Kind |
|---|---|---|
| a handler's code (`invalid_credentials`, `invalid_code`, ...) | `call` | retryable |
| a hook's reason, or `hook_error` | `hook`, a choose's pick | retryable |
| `no_method` | `choose` | retryable |
| `issuer_error` | `screen`, when its credential issuer cannot issue | retryable |
| `subject_mismatch` | `call` | terminal |
| a `fail` form's reason | `fail` | terminal |
| a staged write's commit code, or `write_error` | after a successful end (Effects) | terminal |
| `unauthenticated`, `mfa_required`, `acr_not_satisfied` | `succeed` | terminal |
| `incomplete` | the body completing without `succeed` or `fail` | terminal |

A retryable failure unwinds the continuation to the innermost enclosing `retry` with attempts left and reruns its body; with none, the entry ends with that failure. A terminal failure ends the entry. Pinned by `password-wrong`, `password-retry-exhausted`, `no-terminal`, `explicit-fail`.

An entry that ends with a failure is reported to the host as a rejection with that reason; one that succeeds, as an accept with the computed `subject`, `amr` and `acr` (authenticate) or the granted scopes and identity claims (consent).

## Predicates

A predicate is a bare path (truthiness: false for null, false, empty string, zero, empty list or object) or an object with exactly one operator:

| Operator | Holds when |
|---|---|
| `and`, `or` | every / some operand holds |
| `not` | its operand does not hold |
| `exists` | the path is not null |
| `eq`, `ne` | the path's value equals / does not equal the literal; integers compare by exact value whatever their width, and a fractional number equals no integer |
| `gt`, `lt` | the path's value is an integer greater / less than the integer literal; a fractional number is neither (`numbers-wider-than-64-bits`, `numbers-fractional`) |
| `has` | the path's value is a list containing the literal |

New operators extend the IR without a new form; each needs a format bump, since an older reader would refuse them.

## Handler catalog

A handler is built in and reviewed (charter principle 2). It declares (`HandlerMeta`, equal to its entry in `conformance/handlers.yaml`):

| Field | Meaning |
|---|---|
| `amr` | what it may prove; nonempty makes it a credential |
| `binds_subject` | on success it names the subject |
| `identifies` | it fills `user` from what the person typed; credentials run only after one succeeded |
| `requires_subject` | it runs only once a credential verified the subject in this flow |
| `effect` | `none`, `guard` or `write` (absent: `write`) |
| `args` | the JSON Schema of the call's args (absent: none) |

A handler that binds a subject is a credential and must name a subject on success. It may prove part of its declared amr, never more.

### Effects

* `none`: the handler reads only.
* `guard`: it makes an atomic check and mark that is part of verifying, inline: idempotent for the flow that makes it, refused for any other flow. A TOTP step is used once ([RFC 6238](https://www.rfc-editor.org/rfc/rfc6238#section-5.2) section 5.2); a passkey's sign count only rises. A losing run in the same flow leaves only a mark its winner treats as its own.
* `write`: a lasting change the person asked for. The handler returns it as an effect, which the entry stages: it is kept in the flow's state across screens and applied only if the entry succeeds, so an entry is the transaction. The request whose saved state ended the entry successfully applies the staged writes in order through the handlers' commits, and only then tells the host; no other request applies anything, and loading a flow never does. An entry that fails, or is abandoned, drops its writes. A commit whose precondition no longer holds, or that throws (`write_error`), fails the entry with its code. Writes are applied before the host is told, so a failing write can still make the host reject; if the host then cannot be told, the writes stay (the entry succeeded) and the person starts the sign in again. Pinned by `effect-lost-race`, `effect-commit-fails`, `effect-dropped-on-failure`, `EffectsTest`.

## Screen catalog

A screen spec gives a screen type its options, the kinds of entry it belongs in, whether an instance acts for the signed in subject (the checker refuses it before a credential verified one), the server data it issues (credential material through the issuer registered for it, never readable by predicates or hooks), how it renders, which answers it accepts, and which later screen an answer to it also answers (a hosted answer: a passkey chosen from autofill on `identifier` also answers the next `passkey` screen that uses, not registers, a passkey, once). The engine uses the `method_picker` screen for `choose`; the verifier refuses a `choose` of several options when the catalog has none. The built in types are those of `schema/screen.schema.json`. A screen whose issuer fails fails the step with `issuer_error`. Pinned by `passkey-autofill`, `EngineTest.hostedPasskeyAnswersOnlyOneScreen`.

## Checks

Checks run in three layers. Every finding points at the source the frontend recorded for the node.

| Layer | What | When |
|---|---|---|
| L0, the schema | shape, arity, patterns, the format; a document nested deeper than 128 levels is refused before validation (`IrCodec.MAX_DEPTH`), as is JSON with a repeated key | every read of IR |
| L1, the verifier | unique entry and option ids (at every read); every handler and screen is in the catalogs, screen options are ones the screen takes, no step shows `method_picker` (only a `choose` does; `catalog-method-picker-step`), call args match the handler's `args` | the checker, and the engine whenever it runs a version: a new flow on one that fails is rejected (`flow_unavailable`, `catalog-unknown-handler`), and a flow pinned to one reads as absent |
| L2, the checker | path enumeration, then the rules below | publish, rollback, preview, startup |

The checker enumerates every path through each entry once. The language has no loops but bounded `retry` and no recursion, so paths are finite; more than 10,000 paths in an entry is an error. A predicate is decided when it reads only what is known before the flow runs, else both ways are paths. A call succeeds proving any nonempty part of its amr, fails, or (subject binding handlers) proves another subject; a retry's attempts may fail before one succeeds; a hook fails or sets unknown `vars`; a pick hook fails or picks any option that may be enabled. The checker over-approximates: a rule that holds on every enumerated path holds on every run. The generated vectors check the engine against every enumerated path the generator can realize.

| Rule | Applies to | Kind | Pinned by |
|---|---|---|---|
| A flow has an `authenticate` entry and at most one entry of each kind | flow | error | `CheckerTest.entriesAreOnePerKindWithAnAuthenticateEntry` |
| Step ids are unique | flow | error | `CheckerTest` |
| Handlers and screens are enabled for the tenant; hooks are stored; hook egress is granted | every entry | error | `CheckerTest.tenantAllowlists`, `HookCheckTest` |
| A `requires_subject` handler runs only after a credential verified the subject on the path | every entry | error | `CheckerTest.aCredentialWriteNeedsAVerifiedSubjectFirst` |
| No consent screen and no `consent.record` in an `authenticate` entry | `authenticate` | error | `CheckerTest.consentBelongsOnTheConsentChallenge` |
| A passkey registration screen (`passkey` with `register`) shows only after a credential verified the subject on the path | every entry | error | `CheckerTest.aPasskeyRegistrationScreenNeedsAVerifiedSubjectFirst` |
| The checker accepts no flow the engine then fails to run, and none whose success path the engine refuses as `unauthenticated` | every entry | build failure | `EngineFuzzTest` (ADR 0007) |
| On a success path, a credential runs only after an `identifies` handler succeeded | `authenticate` | error | `CheckerTest.credentialBeforeIdentityIsAnError` |
| On a success path, a credential that binds the subject verified | `authenticate` | error | `CheckerTest.branchWithoutCredentialPointsAtItsLine` |
| If the tenant requires MFA, every success path reaches `faye:2fa` | `authenticate` | error | `CheckerTest.mfaPolicyNeedsTwoFactorsOnEveryPath` |
| No handler that is a credential or binds a subject | `consent` | error | `CheckerTest.aConsentEntryCannotVerifyACredential` |
| No success path, or no path reaching `faye:2fa` | `authenticate` | warning | `CheckerTest` |
| A choose with one option; a retry without a screen; a `when` or option that never runs | every entry | warning | `CheckerTest` |
| The checker agrees with the engine on how every realizable path ends | every entry | build failure | `GeneratedVectorsTest` |

## Canonical form and hashing

The canonical form of a flow is its JSON: UTF-8, no insignificant whitespace, object keys sorted, empty options objects omitted. Canonicalization is syntactic only and belongs to the format; nothing rewrites meaning (no predicate simplification), because that would change hashes and node paths. A version's address is `sha256:` and the hex SHA-256 of its canonical bytes. The format number is in those bytes, and every hook node carries its hook's hash, so both are part of the address. A store checks a version against the bytes it kept, never against a re-serialization.

## Versioning

* **IR format.** A change to what a form means, a new form, a new predicate operator or options key, needs a format bump and an ADR. A reader upgrades older formats in memory to the current one when it reads a stored version, keeping its hash; an upgrade must keep every node at the same child index path, or the state format must be bumped too.
* **State format.** What continuation frames mean. A new optional field, read as absent from older states, needs no bump; a changed meaning does. The engine reads the current and the previous state format for one release; a flow saved in any other reads as absent, so it fails closed and the person starts again. So does a state that cannot be read, or that does not fit the version it pinned (its entry, every frame's node, the screen it waits on and that screen's data): the engine checks before it resumes one (ADR 0007, F7).
* **Handler semantics.** A handler that changes what it proves or binds gets a new name.

Fixtures of every supported format are frozen under `conformance/compat/`, and a test reads them.
