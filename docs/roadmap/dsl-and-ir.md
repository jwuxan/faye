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
      screen: identifier            # identity first: ask who, then decide how
      use: identity.resolve
    risk:
      hook: risk
    credential:
      alternative:                  # Keycloak ALTERNATIVE: the person picks one
        passkey:
          if: user.has_passkey
          screen: passkey
          use: webauthn.assert
        password:
          screen: password
          use: password.verify
          retry: 3
    mfa:
      if:
        or:
          - { eq: [vars.risk, high] }
          - { has: [host.acr_values, "faye:2fa"] }
          - user.mfa_required
      alternative:
        totp:  { if: user.has_totp, screen: otp, use: totp.verify, retry: 3 }
        enroll: { if: { not: user.has_totp }, screen: otp_enroll, use: totp.enroll }
    consent:                        # from here on: the consent challenge, after the sign in
      if: { not: client.first_party }
      screen: consent
      use: consent.record
```

Consent is a step like any other ([ADR 0025](../adr/0025-consent-as-a-step.md)). The step that calls `consent.record` (anywhere in its body) and every step after it run on the host's consent challenge, once the sign in was accepted, so they lower to the `consent` entry; the steps before it lower to the `authenticate` entry. A flow with no such step has no consent entry, and the host's consent is granted without a screen. The checker's rules for each entry kind still apply, so a credential step after consent is refused at the YAML that wrote it.

## Lowering

The compiler (`faye-dsl`) reads YAML into a positioned document (`YamlReader`, the only class that knows SnakeYAML; it refuses duplicate keys, anchors and aliases, and YAML 1.1 scalars that read as another type), lowers it to IR JSON while recording which YAML produced each JSON node, and hands the JSON to `IrCodec`, which checks it against the schema. Every grammar error is reported at the YAML that produced the offending node; the compiler keeps no copy of the grammar, only the rules of the surface itself (its keys, hook declarations and files).

| YAML | IR |
|---|---|
| `flow: id`, `version: n` | `(flow 1 id n entry...)` |
| `login:` steps before the first that calls `consent.record` | `(entry login authenticate (seq step... (succeed)))` |
| that step and every step after it | `(entry consent consent (seq step... (succeed)))` |
| a top level `consent:` (before ADR 0025) | refused: move its steps to the end of `login.steps` |
| `steps: { id: body }` | `(step id body)` each, in order |
| `set: { name: v }` | `(set name v)` each, first in the body |
| `hook: id` | `(hook id hash)`, the hash of the declared hook's definition |
| `screen: type` or `{ type: t, opt: v }` | `(screen t {:opt v})` |
| `use: handler` | `(call handler)` |
| `alternative: { id: body }` | `(choose ((opt id body)...))` |
| `pick: hook` beside `alternative` | `(choose (...) {:pick (hook id hash)})` |
| `fail: reason` | `(fail reason)`, last in the body |
| a body with several parts | `(seq part...)` in the order set, hook, screen, use, alternative or steps, fail |
| `retry: n` | `(retry n body)` around the body |
| `if: p` on a step | `(when p (retry ...))` around the retry |
| `if: p` on an option | the option's guard, `(opt id body {:guard p})` |
| a path `hydra.x` | `host.x`, the spelling before ADR 0024 |

Predicates are written in the IR's shape (`{ eq: [path, literal] }`, `{ and: [...] }`, a bare path); see ir-spec.md, Predicates. A hook is declared once under `hooks` (`file:` next to the flow, or `source:` inline, with `capabilities` and `timeout_ms`; see [hooks.md](hooks.md)) and pinned by the hash of its definition, so editing a hook makes a new flow version.

The lowering of each shipped tenant flow is pinned by a golden file (`server/faye-dsl/src/test/resources/golden/`, in the display notation), and each diagnostic by a case file (`.../diagnostics/`) that lists exactly what compiling it reports.

## Checks and paths

The checker's rules, keyed by entry kind, are in ir-spec.md, Checks. It enumerates every path through each entry and reports them, for example `login: 7 success paths, 9 failure paths; assurance pwd (faye:1fa) to hwk+otp+pwd (faye:2fa)`. The Admin UI shows this summary and the paths on every edit.
