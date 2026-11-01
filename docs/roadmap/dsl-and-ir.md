# DSL and IR

YAML is the single source a tenant writes and the Admin UI edits ([ADR 0010](../adr/0010-m5-admin-ui.md)). The compiler lowers it to the Core IR, an S expression tree stored as JSON arrays, which the checker proves safe and the engine runs. The IR is compiled, never edited: nobody reads or writes it to build a flow (charter, "The IR").

This document is the surface: the YAML and how it lowers. What the IR means is [ir-spec.md](ir-spec.md); its grammar is [`schema/ir.schema.json`](../../schema/ir.schema.json).

## Design principles

1. **Closed language.** A fixed set of forms. No lambda, no recursion, no unbounded loops. Every flow terminates and every path is enumerable.
2. **Structured predicates.** Conditions are data (`{ gt: [vars.score, 50] }`), never strings. No expression parser, no injection surface; operators are an allowlist.
3. **Stable identity.** Step and option ids come from YAML keys, never from the compiler. Audit logs, metrics and resume points depend on them.
4. **Source maps.** Every IR node records the YAML it came from, so errors and runtime findings point at configuration a human can find.
5. **A narrow waist.** The IR knows authentication (entry kinds, credentials, amr), not YAML and not the authorization server. Frontends lower to it; engines run it ([ADR 0024](../adr/0024-core-ir-as-a-narrow-waist.md)).

## YAML surface

GitHub Actions style steps, Keycloak style alternatives.

```yaml
flow: default-login
version: 1

hooks:
  risk: { file: risk.js, capabilities: { http: [ip-reputation] } }

login:
  steps:
    identify:
      block: identifier             # always first: ask who, then decide how
      after: risk                   # a hook runs before or after a block
    credential:
      alternative:                  # Keycloak ALTERNATIVE: the person picks one
        passkey:
          if: user.has_passkey
          block: passkey
        password:
          block: password           # three attempts unless `with: { attempts: n }`
    mfa:
      if:
        or:
          - { eq: [vars.risk, high] }
          - { has: [host.acr_values, "faye:2fa"] }
          - user.mfa_required
      alternative:
        totp:  { if: user.has_totp, block: totp }
        enroll: { if: { not: user.has_totp }, block: enroll-totp }
    consent:                        # from here on: the consent challenge, after the sign in
      if: { not: client.first_party }
      block: consent
```

A flow orchestrates blocks; it does not build them ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)). A block is a built in step whose screen and handler are fixed by its implementation (`BlockSpec`, implemented beside the handlers, e.g. faye-identity's `IdentityBlock`): `identifier`, `password`, `passkey`, `add-passkey`, `totp`, `enroll-totp`, `consent`. A step names one with `block:` and may change only the settings the block declares, under `with:`, each of which has a default (a tenant may replace the defaults, in `faye.tenants.<id>.blocks` or General settings, checked when saved and applied when a flow is compiled; `attempts` is 3 for the typed credentials and 1 for a passkey; the identifier's `passkey` offers passkey autofill, and its `password` asks for the password on the same screen, then takes no other option and no Password block may follow). Every body is exactly one kind of block, and takes only that kind's keys:

| Block | Keys besides `if`, `before`, `after` | Where |
|---|---|---|
| `identifier` | `block`, `with`, `path`; no `if` | the first step, always, and only there |
| `password`, `passkey`, `totp`, `enroll-totp`, `add-passkey` | `block`, `with`, `path` | a step, a group's step, or an option |
| `consent` | `block`, `path` | a step of its own, not in a group or a choice |
| choice | `alternative` (two options or more), `pick` | a step; its options are one credential block or a `fail` |
| group | `steps` | a step |
| set | `set` | a step |
| fail | `fail`; no `after` | a step or an option |

Writing `use`, `screen`, `retry` or a step that is only a `hook` is refused with what to write instead; the Admin UI opens such YAML (from before ADR 0030) converted.

Consent is a step like any other ([ADR 0026](../adr/0026-consent-as-a-step.md)). The step that asks for consent (a block that shows a consent screen or calls a consent handler, in its body or in a nested `alternative` or `steps`; the compiler learns which from its caller, `FlowCompiler.Options`, and the app derives them from its catalogs: the `consent` screen and `consent.record`, which belong in consent entries only) and every step after it run on the host's consent challenge, once the sign in was accepted, so they lower to the `consent` entry; the steps before it lower to the `authenticate` entry. A flow with no such step has no consent entry, and the host's consent is granted without a screen. The checker's rules for each entry kind still apply, so a credential step after consent is refused at the YAML that wrote it.

## Lowering

The compiler (`faye-dsl`) reads YAML into a positioned document (`YamlReader`, the only class that knows SnakeYAML; it refuses duplicate keys, anchors and aliases, and YAML 1.1 scalars that read as another type), lowers it to IR JSON while recording which YAML produced each JSON node, and hands the JSON to `IrCodec`, which checks it against the schema. Every grammar error is reported at the YAML that produced the offending node; the compiler keeps no copy of the grammar, only the rules of the surface itself (its keys, its blocks' places and settings, hook declarations and files).

| YAML | IR |
|---|---|
| `flow: id`, `version: n` | `(flow 1 id n entry...)` |
| `login:` steps before the first that asks for consent | `(entry login authenticate (seq step... (succeed)))` |
| that step and every step after it | `(entry consent consent (seq step... (succeed)))` |
| a top level `consent:` (before ADR 0026) | refused: move its steps to the end of `login.steps` |
| `steps: { id: body }` | `(step id body)` each, in order |
| `set: { name: v }` | `(set name v)` each, first in the body |
| `block: id` | `(screen s {:opt v}) (call h)`, the block's screen `s` with its fixed options and the ones its settings turn on, and its handler `h`; an identifier with `password` also `(call p)`, the password block's handler |
| `with: { attempts: n }`, `n` > 1 | `(retry n (seq (screen ...) (call ...)))` around the block's own parts |
| `path: /challenge/p` | the block's screen's option, `(screen s {:path "/challenge/p"})`; where the Login UI shows it ([ADR 0029](../adr/0029-screen-paths.md)) |
| `before: id`, `after: id` | `(hook id hash)` first and last in the body, once around its attempts; the hash of the declared hook's definition |
| `alternative: { id: body }` | `(choose ((opt id body)...))` |
| `pick: hook` beside `alternative` | `(choose (...) {:pick (hook id hash)})` |
| `fail: reason` | `(fail reason)`, last in the body |
| a body with several parts | `(seq part...)` in the order before, set, block, alternative or steps, fail, after |
| `if: p` on a step | `(when p body)` around the whole body |
| `if: p` on an option | the option's guard, `(opt id body {:guard p})` |
| a path `hydra.x` | `host.x`, the spelling before ADR 0024 |

Predicates are written in the IR's shape (`{ eq: [path, literal] }`, `{ and: [...] }`, a bare path); see ir-spec.md, Predicates. A hook is declared once under `hooks` (`file:` next to the flow, or `source:` inline, with `capabilities` and `timeout_ms`; see [hooks.md](hooks.md)) and pinned by the hash of its definition, so editing a hook makes a new flow version.

The lowering of each shipped tenant flow is pinned by a golden file (`server/faye-dsl/src/test/resources/golden/`, in the display notation), and each diagnostic by a case file (`.../diagnostics/`) that lists exactly what compiling it reports.

## Checks and paths

The checker's rules, keyed by entry kind, are in ir-spec.md, Checks. It enumerates every path through each entry and reports them, for example `login: 7 success paths, 9 failure paths; assurance pwd (faye:1fa) to hwk+otp+pwd (faye:2fa)`. The Admin UI shows this summary and the paths on every edit.
