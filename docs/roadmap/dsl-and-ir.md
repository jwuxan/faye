# DSL and IR

YAML is the surface syntax humans read and diff. The IR is an S expression tree that machines check and execute. The Admin UI edits the IR directly; YAML is an import / export view of the same tree.

## Design principles

1. **Closed language.** A fixed set of special forms. No lambda, no recursion, no unbounded loops. Every flow terminates and every path is enumerable.
2. **Structured predicates.** Conditions are data (`{ gt: [risk.score, 50] }`), never strings. No expression parser, no injection surface, predicate operators are an allowlist.
3. **Stable identity.** Step ids come from YAML keys (or the editor), never from the compiler. Audit logs, metrics and resume points depend on them.
4. **Source maps.** Every IR node records its origin (YAML line, or editor node id) so runtime errors point at configuration a human can find.

## YAML surface

GitHub Actions style steps, Keycloak style requirements.

```yaml
flow: default-login
version: 1

login:
  steps:
    identify:
      screen: identifier            # identity first: ask who, then decide how
      use: identity.resolve
    risk:
      hook: risk.js
    credential:
      alternative:                  # Keycloak ALTERNATIVE: user picks one
        passkey:
          if: user.has_passkey
          use: webauthn.assert
        password:
          screen: password
          use: password.verify
          retry: 3
    mfa:
      if:
        or:
          - { eq: [vars.risk, high] }
          - { has: [hydra.acr_values, mfa] }
          - user.mfa_required
      alternative:
        totp:  { if: user.has_totp, screen: otp, use: totp.verify, retry: 3 }
        enroll: { if: { not: user.has_totp }, screen: otp_enroll, use: totp.enroll }

consent:
  steps:
    grant:
      if: { not: client.first_party }
      screen: consent
      use: consent.record
```

## IR

Stored as JSON arrays: `["seq", …]`. Shown here as S expressions.

```lisp
(flow default-login 1
  (login
    (seq
      (step identify  (seq (screen :identifier) (call identity.resolve)))
      (step risk      (hook risk "sha256:…"))
      (step credential
        (choose
          (opt passkey  (when user.has_passkey (call webauthn.assert)))
          (opt password (retry 3 (seq (screen :password) (call password.verify))))))
      (step mfa
        (when (or (eq vars.risk "high") (has hydra.acr_values "mfa") user.mfa_required)
          (choose
            (opt totp   (when user.has_totp (retry 3 (seq (screen :otp) (call totp.verify)))))
            (opt enroll (when (not user.has_totp) (seq (screen :otp_enroll) (call totp.enroll)))))))
      (succeed)))
  (consent
    (seq
      (step grant (when (not client.first_party) (seq (screen :consent) (call consent.record))))
      (succeed))))
```

## Special forms

| Form | Meaning |
|---|---|
| `(seq a b …)` | Run in order; any failure fails the sequence |
| `(step id body)` | Named boundary for audit, metrics, source maps |
| `(screen :type opts?)` | Suspend; emit a typed screen; resume with the user's submission |
| `(call handler args?)` | Invoke a registered server handler; the only place built in side effects happen |
| `(hook id hash)` | Invoke a pinned JS hook; output restricted (see [hooks.md](hooks.md)) |
| `(choose (hook id hash)? (opt id body) …)` | Offer the enabled options to the user, or let the leading hook pick one (YAML: `pick: hook-id` beside `alternative`); run the chosen one |
| `(when pred body)` | Run body if predicate holds, else skip |
| `(retry n body)` | Rerun body up to n times on failure, preserving screen errors |
| `(set path value)` | Write a literal into `vars` |
| `(succeed)` / `(fail reason)` | Terminate the section |

`choose` maps directly onto typed screens: when more than one option is enabled the engine emits a `method_picker` screen whose capabilities list the options, unless the first step of every option is itself a screen that can host the choice (for example passkey autofill on the password screen).

## Predicates and environment

Operators: `and`, `or`, `not`, `eq`, `ne`, `gt`, `lt`, `has`, `exists`. A bare path is truthiness.

Readable namespaces (read only to hooks and predicates):

| Namespace | Source |
|---|---|
| `request.*` | IP, user agent, locale |
| `client.*` | Hydra client id, metadata (`first_party`, tenant) |
| `hydra.*` | `skip`, `acr_values`, `requested_scope`, `login_hint` |
| `tenant.*` | Tenant settings: `mfa_required` |
| `user.*` | Set only by `identity.resolve`: existence, enrolled factors, policy |
| `vars.*` | Writable by `set` and hooks |
| `auth.*` | Engine owned: verified credentials, computed `amr`. Never writable. |

## Static checks (run at validate and publish)

Errors block publishing:

1. **Schema**: every node well formed, every handler, screen type and predicate operator registered.
2. **Identity before credential**: every path to `succeed` in `login` passes `identity.resolve` before any credential handler.
3. **Credential on every success path**: every path to `succeed` in `login` includes at least one verified credential `call` that binds the subject.
4. **Tenant policy**: if the tenant mandates MFA, every success path includes a second factor of a different `amr` class.
5. **Tenant allowlist**: handlers, screens and hooks are enabled for this tenant; every egress target a hook declares is granted to the tenant.
6. **Hooks pinned**: every `hook` references an existing hash.
7. **Unique step ids** within a flow.

Warnings shown but not blocking: unreachable options (predicate statically false), `retry` without a screen, `choose` with a single option.

The checker also enumerates paths and reports them (for example: "7 success paths, minimum assurance: pwd; maximum: hwk+otp"). The Admin UI displays this summary on every edit.

Because the language has no loops other than bounded `retry` and no recursion, path enumeration is finite. `retry` is treated as "body succeeds" or "body fails" for path purposes.
