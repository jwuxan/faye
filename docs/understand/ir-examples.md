---
description: Two real flows next to the IR that the compiler makes from them, line by line, with the canonical JSON and the version hash.
---

# The IR by example

This page shows what the compiler makes from two real flow files. It is for engineers who want to see the IR concretely, after reading [How the IR is designed](ir-design.md). Each example comes from a flow that ships with Faye, and a golden file in the test suite pins its IR.

## A password only flow

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

## Next steps

- Back to the design: [How the IR is designed](ir-design.md).
- The exact meaning of every form: [Core IR specification](../ir-spec.md).
- How the engine runs this IR: [How the engine works](engine.md).
