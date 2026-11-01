---
description: The complete specification of the YAML flow file, with every key, block, condition, rule, limit and checker message.
---

# Flow DSL reference

This page specifies the YAML flow file: every key it may hold, every block a step may be, how conditions are written, and the limits and messages of the compiler and the checker. It is for flow authors and for engineers who review flows. To learn the language step by step, start with [Write your first flow](../build/first-flow.md). To see what the YAML becomes, read [How the IR is designed](../understand/ir-design.md).

The compiler is [`FlowCompiler`](../../src/faye-core/src/main/java/faye/dsl/FlowCompiler.java). The blocks are [`BuiltinBlock`](../../src/faye-engine/src/main/java/faye/handlers/BuiltinBlock.java), listed as a contract in [`src/conformance/blocks.yaml`](../../src/conformance/blocks.yaml). Every example on this page comes from a shipped flow in `apps/server/faye-app/src/main/resources/flows/`, from a test, or was compiled before it was published here.

## How a flow file is processed

A flow file passes three gates before it runs. Each gate reports problems as `file:line:column: message`, at the YAML that caused them.

| Gate | What it checks | When it runs |
|---|---|---|
| Compiler | YAML rules, keys, block placement, settings, and the IR grammar | Every validate, preview and publish |
| Verifier | Screens and handlers exist, screen options and paths, the hard limits | The checker, and the engine whenever it runs a version |
| Checker | Every path through the flow, against the safety rules and the tenant's policy | Publish, rollback, preview and startup |

An error at any gate stops a publish and a preview. A warning does not. The Admin UI shows both on the YAML tab and on the block they point at.

## File layout

A flow file is one YAML mapping. This is the shipped flow `partner-consent.yaml`:

```yaml
# Password sign in, and a consent screen for third party clients. First party clients
# (registered with metadata.first_party = true) are not asked.
flow: partner-consent
version: 1

login:
  steps:
    identify:
      block: identifier
    credential:
      block: password
    # Consent runs once the sign in is accepted, on the authorization server's consent challenge
    grant:
      if: { not: client.first_party }
      block: consent
```

### Top level keys

| Key | Required | Type | Meaning |
|---|---|---|---|
| `flow` | Yes | string matching `^[a-z][a-z0-9_-]*$` | The flow's id. |
| `version` | Yes | integer from 1 to 2147483647 | Your own number for this flow. It does not change how the flow runs. Faye addresses versions by the hash of the compiled flow, not by this number. |
| `hooks` | No | mapping of hook id to hook | The hooks that steps may run. See [Hooks](#hooks). |
| `login` | Yes | mapping with one key, `steps` | The steps of the sign in, then consent. See [Consent split](#consent-split). |
| `approve` | No | mapping with one key, `steps` | The phone's side of a QR code sign in. See [Approve section](#approve-section). |

The compiler refuses any other top level key:

- `consent:` is refused with `consent is a step of 'login' now: move these steps to the end of 'login.steps'` ([ADR 0026](../adr/0026-consent-as-a-step.md)).
- Any other key is refused with `unknown key '<key>'`.
- A missing key is reported as `missing 'flow'`, `missing 'version'` or `missing 'login'`.

`login` and `approve` take only `steps`. Another key under them is refused with `unknown key '<key>'`.

### What each section becomes

| Section | Becomes |
|---|---|
| `login`, up to the first consent step | the entry `login` of kind `authenticate` |
| `login`, from the first consent step on | the entry `consent` of kind `consent` |
| `approve` | the entry `approve` of kind `approve` |

A flow with an `approve` section is written in IR format 2. Every other flow is format 1. The compiler adds `(succeed)` at the end of each entry.

## Hooks

A hook is tenant JavaScript that a step runs in a sandbox. Declare each hook once under `hooks:`, then attach it to steps with `before`, `after` or `pick`. This is the declaration in the shipped flow `acme.yaml`:

```yaml
hooks:
  risk:
    file: risk.js
    capabilities: { http: [ip-reputation] }
    timeout_ms: 500
```

### Hook keys

| Key | Required | Value | Default | Meaning |
|---|---|---|---|---|
| `file` | One of `file` or `source` | file name matching `[a-z0-9][a-z0-9_-]*\.js` | | A JavaScript file next to the flow file. A draft sent from the Admin UI reads it next to the tenant's configured flow. |
| `source` | One of `file` or `source` | string | | The JavaScript itself, inline. |
| `capabilities` | No | mapping with one key, `http` | no egress | `http` lists the named egress targets the hook may call. Each name matches `[a-z][a-z0-9_-]*`. |
| `timeout_ms` | No | integer from 1 to 2000 | 200 | The wall clock deadline of one run, egress included. |

The hook id is the key under `hooks:`. It matches `^[a-z][a-z0-9_-]*$` and is at most 64 characters. The source is at most 64 KiB (65,536 bytes of UTF-8).

The compiler pins each hook by the SHA-256 of its source, egress targets and timeout. If you change any of the three, the flow's hash changes, so you publish a new version. The checker refuses a hook whose egress target the tenant does not grant.

### What a hook may return

A hook may only return one of these. Anything else, a throw, a timeout or a memory overrun fails the step with `hook_error`.

| Result | Effect |
|---|---|
| `{}` | Nothing. |
| `{set: {...}}` | Merges 1 to 16 names into `vars`. Each name matches `^[a-z][a-z0-9_]*$`. Each value is a string of up to 1,024 characters, an integer or a boolean. Not allowed for a `pick` hook. |
| `{fail: reason}` | Fails the step with that reason, an id of at most 64 characters. |
| `{pick: id}` | Only for a `pick` hook: runs that option. It must name an enabled option. |

A hook can never sign anyone in, bind a subject, write `auth` or show a screen. See [Hooks](../build/hooks.md) to write one.

## Steps and options

A step is one entry of a `steps:` mapping. An option is one entry of an `alternative:` mapping. Both have a body, and both bodies take the same keys.

The key of each entry is its id. It matches `^[a-z][a-z0-9_-]*$` and is at most 64 characters. Step ids are unique across the whole flow, `approve` included. Option ids are unique within their choice.

Steps run in the order the file lists them. There is no `next:` or `goto`: you change the order by moving steps.

### Body keys

This table lists every key a body may have, in the order its parts run.

| Key | Value | Taken by | Lowers to |
|---|---|---|---|
| `if` | a condition | every body except the Identifier | On a step: `(when condition body)` around the whole body, hooks included. On an option: the option's guard, which decides whether it is offered. |
| `before` | a hook id | every body | `(hook id hash)` before the rest of the body |
| `set` | mapping of name to literal | a set body | `(set name literal)` for each entry |
| `block` | a block id | a block body | the block's screen and handler call, in a `retry` when attempts are more than 1 |
| `with` | mapping of setting to value | a block body whose block has settings | changes the block's settings for this step |
| `path` | a URL path | a block body | the `path` option of the block's screen. See [Screen paths](#screen-paths). |
| `alternative` | mapping of option id to body, two or more | a choice body | `(choose (opt ...) ...)` |
| `pick` | a hook id | a choice body | the choice's pick hook |
| `steps` | mapping of step id to body | a group body | the nested steps, in order |
| `fail` | a reason id | a fail body | `(fail reason)` |
| `after` | a hook id | every body except a fail | `(hook id hash)` after the rest of the body |

A step that says `if:` lowers to `when` around its hooks too. So when the condition is false, the step's `before` and `after` hooks do not run either.

### The five kinds of body

Every body is exactly one kind. The first of these keys that the body has decides the kind: `block`, then `alternative`, then `steps`, then `set`, then `fail`.

| Kind | Decided by | Takes | Example |
|---|---|---|---|
| Block | `block` | `if`, `before`, `block`, `with`, `path`, `after` | `credential: { block: password }` |
| Choice | `alternative` | `if`, `before`, `alternative`, `pick`, `after` | `acme.yaml`, step `credential` |
| Group | `steps` | `if`, `before`, `steps`, `after` | a nested list of steps |
| Set | `set` | `if`, `before`, `set`, `after` | `mark: { set: { channel: web } }` |
| Fail | `fail` | `if`, `before`, `fail` | `blocked: { fail: blocked_account }` |

A body with none of the five keys is refused with `step needs one of 'block', 'alternative', 'steps', 'set' or 'fail'`. A key that the kind does not take is refused with the keys it does take:

```text
a Password block takes 'if', 'before', 'block', 'with', 'path', 'after', not 'set'
```

A block with no settings, such as Consent, does not take `with`. The Identifier does not take `if`, because it always runs.

### Placement rules

- The first step of `login`, and of `approve`, is the Identifier. It appears nowhere else.
- An option is one credential or action block, or a `fail`. It is never a group, a set, a choice, the Identifier, Consent or Approve QR code.
- A choice has two options or more. With one, use that block as the step.
- Consent is a top level step of `login`. It is never in a group, a choice or `approve`.
- Approve QR code is a top level step of `approve`, and only there.
- If the Identifier asks for the password, no Password block may appear anywhere in the same section.

### A body that uses every kind

This flow compiles. It is an example of syntax, not a recommended flow.

```yaml
flow: example
version: 3
hooks:
  route:
    source: "export default (ctx) => ({ pick: ctx.options[0] })"
  audit:
    source: "export default () => ({})"
    timeout_ms: 100
login:
  steps:
    identify:
      block: identifier
      with: { password: true, attempts: 5 }
    blocked:
      if: { eq: [host.login_hint, "blocked@example.com"] }
      fail: blocked_account
    mark:
      set: { channel: web, tries: 2, beta: false }
    second-factor:
      if: { or: [tenant.mfa_required, { has: [host.acr_values, "faye:2fa"] }] }
      before: audit
      pick: route
      alternative:
        totp:
          if: user.has_totp
          block: totp
        sms:
          if: user.has_phone
          block: sms-code
          with: { attempts: 2 }
        stop:
          fail: no_second_factor
    extras:
      after: audit
      steps:
        passkey:
          if: { not: user.has_passkey }
          block: add-passkey
          with: { required: true }
        phone:
          if: { exists: user.email }
          block: add-phone
```

### Keys that moved

Older flow files used keys that took a block apart. The compiler refuses them and says what to write instead ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)).

| Old key | Message |
|---|---|
| `use: password.verify` | `a step names its block, which brings its handler: write 'block: password'` |
| `screen:` | `a block shows its own screen; change what it offers under 'with'` |
| `retry: 5` | `attempts are a setting of a block: write 'with: { attempts: 5 }'` |
| `retry: 3` on a Password block | `a Password block makes 3 attempts by default: remove 'retry'` |
| `hook: slow` as a step | `a hook runs around a block: write 'before: slow' on the block it runs before, or 'after: slow' on the one it follows` |

## Blocks

A block is one built in step. It fixes the screen the person sees, the handler that checks the answer and the default attempts. A flow places blocks and changes only the settings each block declares.

| Block | Screen | Handler | `amr` it can prove | Settings and defaults |
|---|---|---|---|---|
| `identifier` | `identifier` | `identity.resolve` | none | `passkey: false`, `password: false`, `attempts: 3` |
| `password` | `password` | `password.verify` | `pwd` | `attempts: 3` |
| `passkey` | `passkey` | `webauthn.assert` | `hwk`, and `user` when the person was verified | `attempts: 1` |
| `add-passkey` | `passkey` with `register` | `webauthn.register` | none | `required: false` |
| `totp` | `otp` | `totp.verify` | `otp` | `attempts: 3` |
| `enroll-totp` | `otp_enroll` | `totp.enroll` | `otp` | `attempts: 3` |
| `email-code` | `code_sent` with channel `email` | `email_code.verify` | `email` | `attempts: 3` |
| `sms-code` | `code_sent` with channel `sms` | `sms_code.verify` | `sms` | `attempts: 3` |
| `add-phone` | `phone_enroll` | `phone.enroll` | none | `attempts: 3`, `required: false` |
| `qr` | `qr` | `qr.redeem` | what the phone proved: any of `pwd`, `otp`, `sms`, `email`, `hwk`, `user` | `attempts: 3` |
| `qr-approve` | `qr_approve` | `qr.approve` | none | none |
| `consent` | `consent` | `consent.record` | none | none |

`attempts` is a whole number from 1 to 10. A tenant may change a block's defaults in the Admin UI's General settings, or with `faye.tenants.<id>.blocks` in the server configuration, for example `{ password: { attempts: 5 } }`. A flow's `with` still wins. A changed default applies the next time a flow is compiled. The Identifier's `password` setting cannot be a tenant default: the flow says it.

The engine computes `acr` from the `amr` values that verified: two different factor classes give `faye:2fa`, one gives `faye:1fa`. `email` counts as a factor alone but never as a second one. See [Security](../understand/security.md).

Every block with a screen may also fail with `issuer_error` when the server data behind its screen cannot be issued, for example when a code cannot be sent.

### identifier

- **What the person sees:** the `identifier` screen, at `/login`, which asks who they are. It is prefilled from `host.login_hint`. With `passkey: true` it also offers passkey autofill. With `password: true` it asks for the password on the same screen. When the tenant offers "keep me signed in", the screen shows it.
- **Handler:** `identity.resolve`. It finds the account and fills `user.*`; see [User fields](#user-fields). It proves nothing.
- **Settings:** `passkey` (default `false`), `password` (default `false`), `attempts` (default 3). `attempts` is allowed only with `password: true`, because an identifier alone never fails.
- **With `password: true`:** the block also calls `password.verify` and proves `pwd`. It takes no other option: `passkey: true` is refused. No Password block may appear in the same section.
- **Placement:** the first step of `login` and of `approve`, and nowhere else. It takes no `if`.
- **Failure codes:** `wrong_account`, when a subject is already bound (a step up) and the person names another account. With `password: true`, also `invalid_credentials`.

This step asks for the identifier and the password on one screen, with five attempts:

```yaml
    identify:
      block: identifier
      with: { password: true, attempts: 5 }
```

It lowers to `(retry 5 (seq (screen identifier {:password true}) (call identity.resolve) (call password.verify)))`.

> [!NOTE]
> An unknown identifier resolves like a known account that has only a password. So identity first sign in does not reveal which accounts exist. Avoid showing different screens on `user.exists`, because that would reveal it.

### password

- **What the person sees:** the `password` screen, with the identifier shown so they can change it.
- **Handler:** `password.verify`. It binds the subject and proves `pwd`.
- **Settings:** `attempts` (default 3).
- **Placement:** any step after the Identifier, in a group, or as an option. Not allowed when the Identifier asks for the password.
- **Failure codes:** `invalid_credentials`.

### passkey

- **What the person sees:** the `passkey` screen, which asks the browser for a passkey.
- **Handler:** `webauthn.assert`. It binds the subject and proves `hwk`, plus `user` when the authenticator verified the person. A passkey that verified the person proves `faye:2fa` alone.
- **Settings:** `attempts` (default 1).
- **Placement:** any step after the Identifier, in a group, or as an option. If the Identifier offers autofill, a passkey chosen there answers the next Passkey screen, so that screen does not wait.
- **Failure codes:** `invalid_credentials`.

### add-passkey

- **What the person sees:** the `passkey` screen in its register form, which creates a passkey. The person may choose "Not now" unless `required` is true.
- **Handler:** `webauthn.register`. It proves nothing. It needs a subject that a credential verified earlier in the flow. The new passkey is stored only if the sign in succeeds.
- **Settings:** `required` (default `false`). It has no `attempts` setting, so it runs once.
- **Placement:** any step or option after a credential.
- **Failure codes:** `invalid_credentials`, `unauthenticated`.

### totp

- **What the person sees:** the `otp` screen, which asks for a code from an authenticator app.
- **Handler:** `totp.verify`. It binds the subject and proves `otp`. It needs a subject that a credential verified earlier, so it is only ever a second factor. Each code is accepted once.
- **Settings:** `attempts` (default 3).
- **Placement:** any step or option after a credential.
- **Failure codes:** `invalid_code`, `unauthenticated`.

### enroll-totp

- **What the person sees:** the `otp_enroll` screen, which shows a new secret as a QR code and as text, and asks for a code from the app. A retry keeps the same secret.
- **Handler:** `totp.enroll`. It binds the subject and proves `otp`. It needs a subject that a credential verified earlier. The secret is stored only if the sign in succeeds.
- **Settings:** `attempts` (default 3).
- **Placement:** any step or option after a credential.
- **Failure codes:** `invalid_code`, `unauthenticated`.

### email-code

- **What the person sees:** the `code_sent` screen. Faye emails a one time code to the account and shows where it went, masked. The person may ask for another code; that does not spend an attempt.
- **Handler:** `email_code.verify`. It binds the subject and proves `email`. It may be the first credential.
- **Settings:** `attempts` (default 3).
- **Placement:** any step after the Identifier, in a group, or as an option. The tenant must have email delivery, or the checker refuses the flow with `handler 'email_code.verify' is not enabled for this tenant`. `acme.yaml` offers it only `if: user.email_verified`.
- **Failure codes:** `invalid_code`, `code_expired`.

### sms-code

- **What the person sees:** the `code_sent` screen. Faye texts a one time code to the account's verified phone and shows the number, masked. The person may ask for another code; that does not spend an attempt.
- **Handler:** `sms_code.verify`. It binds the subject and proves `sms`. It may be the first credential.
- **Settings:** `attempts` (default 3).
- **Placement:** any step after the Identifier, in a group, or as an option. The tenant must have text message delivery. Guard it with `if: user.has_phone`, as `acme.yaml` does.
- **Failure codes:** `invalid_code`, `code_expired`.

### add-phone

- **What the person sees:** the `phone_enroll` screen. The person types a number, Faye texts a code to it, and the person enters the code. The person may choose "Not now" unless `required` is true.
- **Handler:** `phone.enroll`. It proves nothing. It needs a subject that a credential verified earlier. The number is stored, and phone codes turned on, only if the sign in succeeds.
- **Settings:** `attempts` (default 3), `required` (default `false`).
- **Placement:** any step or option after a credential. The tenant must have text message delivery.
- **Failure codes:** `invalid_code`, `code_expired`, `unauthenticated`.

### qr

- **What the person sees:** the `qr` screen, which shows a QR code and a number. The person scans the code with a phone, and the phone runs the flow's `approve` section. The screen waits until the phone approves, refuses, or the code expires after 2 minutes.
- **Handler:** `qr.redeem`. It binds the subject and proves the `amr` that the phone's `approve` entry proved, never more.
- **Settings:** `attempts` (default 3). After an expired code, the next attempt shows a new one.
- **Placement:** any step after the Identifier, in a group, or as an option, in `login` only. The flow must also have an `approve` section.
- **Failure codes:** `qr_expired`, `qr_not_approved`, `wrong_account` (the phone approved for another account than the one identified).

### qr-approve

- **What the person sees:** on the phone, the `qr_approve` screen. It shows the application, the waiting device's browser and three numbers. The person picks the number the other device shows.
- **Handler:** `qr.approve`. It proves nothing. It needs a subject that a credential verified earlier in `approve`. The approval is recorded only if the `approve` entry succeeds.
- **Settings:** none.
- **Placement:** a top level step of `approve`, and the last call on every success path.
- **Failure codes:** `wrong_number` (it also refuses the waiting sign in), `qr_expired`, `unauthenticated`.

### consent

- **What the person sees:** the `consent` screen, at `/consent`. It shows the application and the scopes it asks for, with allow and deny.
- **Handler:** `consent.record`. It proves nothing.
- **Settings:** none.
- **Placement:** a top level step of `login`. It starts the consent entry; see [Consent split](#consent-split).
- **Failure codes:** `consent_denied`.

## Conditions

`if:` takes a condition. A condition is a path, or a mapping with exactly one operator. It reads values from the flow's environment and never runs code.

### Path namespaces

A path is a namespace and one or more lowercase segments, matching `^(request|client|host|tenant|user|vars|auth)(\.[a-z][a-z0-9_]*)+$`. A missing value is null: false, equal to nothing, and absent for `exists`.

| Namespace | Holds | Written by |
|---|---|---|
| `request` | The request that started the flow. The server sets `request.ip` and `request.user_agent`. | The engine, at start. A preview scenario may override them. |
| `client` | The application: `client_id`, `client_name`, `first_party`, and the operator's own data under `client.metadata.<key>`. No other field. | The host, at start |
| `host` | What the authorization server relays. Only the fields of the host contract, listed in [Host fields](#host-fields). | The host, at start |
| `tenant` | `tenant.mfa_required`, `tenant.name` when set, and `tenant.remember_me` when the tenant offers "keep me signed in" | The engine, at start |
| `user` | What handlers learned about the person. See [User fields](#user-fields). | Handlers, when they succeed |
| `vars` | The flow's own values | `set`, and hooks |
| `auth` | `auth.subject`, `auth.amr` and `auth.acr` | The engine only, from credentials that verified |

### Host fields

`host.*` is closed: a flow reads only these fields, so it runs unchanged with any authorization server ([ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)). The checker refuses any other `host.<field>`, and any path under a field other than `context`.

| Field | Type | Means |
|---|---|---|
| `host.subject` | string, absent when none | The subject the host already knows: a remembered session, a step up, or the subject of a consent |
| `host.skip` | boolean | The host remembers this subject (login) or this grant (consent) |
| `host.acr_values` | list of strings | The assurance the client asked for |
| `host.requested_scope` | list of strings | The scopes the client asks for |
| `host.requested_access_token_audience` | list of strings | The audiences the client asks for |
| `host.login_hint` | string, absent when none | Who the client expects to sign in |
| `host.ui_locales` | list of strings | The person's preferred languages |
| `host.context.<key>` | any | The host's own data, carried from one request to the next |

`client.*` is closed the same way: `client.client_id`, `client.client_name`, `client.first_party` and `client.metadata.<key>`.

### User fields

`identity.resolve`, the Identifier's handler, replaces `user.*` with these fields:

| Field | Value |
|---|---|
| `user.identified_by` | `identifier` when the person typed it, `passkey` when they chose a passkey from autofill |
| `user.identifier` | What the person typed, normalized; or the account's identifier after autofill |
| `user.exists` | Whether the account exists |
| `user.has_password` | Whether the account has a password; `true` for an unknown account |
| `user.has_totp` | Whether the account has an authenticator app |
| `user.has_passkey` | Whether the account has a passkey |
| `user.has_phone` | Whether the account has a verified phone with phone codes turned on |
| `user.mfa_required` | Whether the account must use two factors |
| `user.email`, `user.email_verified`, `user.name` | Only for a known account; `name` only when it has one |

Later blocks add to `user.*`: Enroll TOTP sets `has_totp`, Add passkey sets `has_passkey` and Add phone sets `has_phone`, each to `true` once the person enrolls.

### Operators

| Operator | Holds when | Example |
|---|---|---|
| bare path | the value is truthy: not null, `false`, `""`, `0`, or an empty list or mapping | `if: user.has_totp` |
| `and` | every condition in the list holds | `if: { and: [user.has_passkey, { not: { has: [auth.amr, hwk] } }] }` |
| `or` | at least one condition in the list holds | `if: { or: [user.has_totp, user.mfa_required] }` |
| `not` | its condition does not hold | `if: { not: client.first_party }` |
| `exists` | the path is not null | `if: { exists: host.login_hint }` |
| `eq` | the path's value equals the literal | `if: { eq: [vars.risk, high] }` |
| `ne` | the path's value does not equal the literal | `if: { ne: [auth.acr, "faye:2fa"] }` |
| `gt` | the path's value is an integer greater than the integer literal | `if: { gt: [vars.score, 50] }` |
| `lt` | the path's value is an integer less than the integer literal | `if: { lt: [vars.score, 10] }` |
| `has` | the path's value is a list that contains the literal | `if: { has: [host.acr_values, "faye:2fa"] }` |

`and` and `or` take a list of one or more conditions. `eq`, `ne` and `has` take a path and a literal: a string, an integer or a boolean. `gt` and `lt` take a path and an integer. The path is always first. A literal string is at most 1,024 characters.

Quote a literal that YAML would read as another type, such as `"faye:2fa"` or `"10.0.0.1"`.

This condition from `passkey-totp.yaml` combines them:

```yaml
    second-factor:
      if:
        and:
          - { ne: [auth.acr, "faye:2fa"] }
          - or:
              - user.has_totp
              - user.mfa_required
              - { has: [host.acr_values, "faye:2fa"] }
```

### The `hydra.` alias

The server accepts `hydra.` as another spelling of `host.` in a path, so older flows still compile. The compiler rewrites it wherever a path appears: `{ exists: hydra.login_hint }` becomes `{ exists: host.login_hint }`. The alias comes from the Hydra host adapter, not from the language. Write `host.` in new flows.

### What the checker knows

The checker decides a condition when it reads only values known before the flow runs: `tenant.*`, `vars` written by `set`, and `auth.amr` and `auth.acr`. For any other condition, it checks both outcomes. So a step under `if: tenant.mfa_required` in a tenant without MFA gets the warning `never runs: its condition is false on every path that reaches it`.

## Choices

A choice offers its options and runs one of them. An option is enabled when it has no `if`, or its `if` holds. The engine picks the option to run in this order:

1. If no option is enabled, the choice fails with `no_method`.
2. If the choice has a `pick` hook, the hook runs with the enabled option ids in `ctx.options`. It returns `{pick: id}`, `{}` or `{fail: reason}`.
3. If the person already answered an option's first screen, that option runs. For example, a passkey chosen from autofill on the Identifier answers the Passkey option.
4. If only one option is enabled, it runs.
5. Otherwise the person sees the method picker screen, at `/challenge/<step>`, and chooses one of the enabled options.

A choice has at most 8 options. This choice, from a checker test, lets a hook decide between a password and an emailed code:

```yaml
flow: pick
version: 1
hooks:
  route: { source: "export default (ctx) => ({ pick: ctx.options[0] })" }
login:
  steps:
    identify: { block: identifier }
    credential:
      pick: route
      alternative:
        password: { block: password }
        email: { if: user.email_verified, block: email-code }
```

## Attempts and retries

A block with `attempts` above 1 runs again after a failure, up to that many times in total. One attempt means no retry.

- After a failed attempt, the block's screen shows again with the failure code and the attempts left.
- `vars`, `user` and `auth` keep what earlier attempts did.
- The block's `before` and `after` hooks are outside the retry, so they run once.
- A screen action, such as sending another code, does not spend an attempt.
- When the last attempt fails, the step fails with that code, and so does the sign in.
- `subject_mismatch` is never retried: a credential that proved someone other than the subject already bound ends the sign in.
- A `fail` body is never retried. A hook is outside every retry, so a hook that fails ends the sign in.

## Consent split

`login.steps` is one list, but the authorization server asks for the sign in and the consent in two separate challenges. The compiler splits the list in two:

- The steps before the first top level step that holds a Consent block lower to the `authenticate` entry, `login`.
- That step, and every step after it, lower to the `consent` entry, `consent`. They run on the consent challenge, after the sign in was accepted.

A flow with no Consent block has no consent entry. The authorization server's consent is then granted without a screen. A Consent step whose `if` is false grants consent without a screen too: `partner-consent.yaml` asks only third party applications.

The consent entry may not verify a credential. If you put a credential after Consent, the checker refuses it, for example: `password.verify verifies a credential; a consent entry only grants access to a subject its authenticate entry proved`.

## Approve section

The `approve:` section is the phone's side of a QR code sign in ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). When someone scans a `qr` screen's code, the phone opens the Login UI and runs this section. This is the section in `acme.yaml`:

```yaml
# The phone's side of a QR code sign in: who, a credential, then the number the other device shows
approve:
  steps:
    who:
      block: identifier
    prove:
      alternative:
        passkey:
          if: user.has_passkey
          block: passkey
        password:
          block: password
    confirm:
      block: qr-approve
```

The rules:

- The first step is the Identifier.
- A credential must verify the subject on every success path, and the tenant's MFA policy applies.
- Approve QR code is the last call on every success path, exactly once.
- Consent and the `qr` block are not allowed in `approve`.
- Step ids are unique across the flow, so the ids differ from the ones in `login`.
- A `qr` block in `login` needs this section. Without it, the checker says `screen 'qr' needs the flow to have a approve entry`.

## Screen paths

Each screen shows at a URL path in the Login UI ([ADR 0029](../adr/0029-screen-paths.md)). Without `path:`, a screen shows at its default:

| Screen | Default path |
|---|---|
| `identifier` | `/login` |
| `consent` | `/consent` |
| Every other screen, and the method picker | `/challenge/<step>`, where `<step>` is the id of the innermost step around the screen |

`path:` on a block body sets the path of that block's screen. It must start with the first segment of the default, `/login`, `/consent` or `/challenge`, and add at most three segments that match `[a-z0-9][a-z0-9_-]*`. The method picker always uses its default.

```yaml
    identify: { path: /login, block: identifier }
    credential:
      path: /challenge/password
      block: password
```

A path is a label for the address bar. It never selects a screen: the flow alone decides which screen comes next. A path that breaks the rule is refused, for example `screen 'password' takes a path of '/challenge' and up to three lowercase segments, such as '/challenge/name'`.

## YAML rules

The compiler reads YAML strictly, so a value means what it looks like.

| Rule | Message |
|---|---|
| No anchors (`&a`) or aliases (`*a`) | `anchors and aliases are not supported; write the value out` |
| No duplicate keys in a mapping | `duplicate key '<key>'` |
| A key is a single value, not a mapping or a list | `keys must be strings` |
| A boolean is `true` or `false`. `yes`, `no`, `on`, `off` and other spellings are refused | `write a boolean as true or false, or quote 'yes'` |
| An integer is decimal and fits in 64 bits. Hex (`0x1F`), octal (`010`) and other forms are refused | `write an integer in decimal within 64 bits, or quote '0x1F'` |
| A literal is a string, an integer or a boolean. A decimal number such as `1.5` is refused | for a `set` value: `value of <name>: number found, [string, integer, boolean] expected` |
| The file is at most 1,048,576 characters and nests at most 64 levels | refused before anything else |

To use one of these refused values as a string, quote it: `"yes"`, `"0x1F"`.

## Limits

Every flow stays under these limits ([ADR 0037](../adr/0037-hard-limits-on-flows-and-hooks.md)). Real flows are far below them.

| Limit | Value | Checked by |
|---|---|---|
| IR nodes in a flow, across all entries | 1,000 | Verifier |
| Steps in a flow, nested steps included | 64 | Verifier |
| Hook attachments in a flow: every `before`, `after` and `pick` | 16 | Verifier |
| Different hooks in a flow | 8 | Verifier |
| Options in one choice | 8 | Verifier |
| Attempts of one block | 1 to 10 | Compiler |
| Product of nested retries on any path | 10 | Verifier |
| Different names written by `set` in a flow | 32 | Verifier |
| Length of a string literal, in `set` or a condition | 1,024 characters | Verifier |
| Length of an id: step, option, hook, set name or fail reason | 64 characters | Verifier |
| Paths through one entry | 10,000 | Checker |
| Hook runs in one entry, over all its requests | 32 | Checker |
| Hook time in one entry: the sum of every attachment's `timeout_ms` | 5,000 ms | Checker |
| Hook source | 64 KiB | Compiler |
| Hook `timeout_ms` | 1 to 2,000 ms | Compiler |

In YAML, a hook is never inside a retry, so each attachment counts once toward the hook budgets. With the default timeout of 200 ms, 16 attachments use 3,200 ms, so only longer timeouts reach the 5,000 ms limit. For example, three attachments with `timeout_ms: 2000` take 6,000 ms and are refused.

The engine also stops a single request at 32 hook runs or 10,000 ms of hook time, `vars` at 64 names, and a saved flow at 256 KiB. One hook run makes at most 4 egress calls.

> [!TIP]
> Paths multiply with every choice, condition and attempt. If the checker reports more than 10,000 paths, lower the attempts of a block that does not need many, or remove an option that few people use. `acme.yaml` sets `with: { attempts: 1 }` on Add phone for this reason ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)).

## Messages

### Compiler messages

These are the messages you are most likely to see from the compiler. Placeholders are in angle brackets.

| Message | Meaning and fix |
|---|---|
| `'login' needs its first step, the Identifier ('block: identifier')` | `login.steps` is empty. Add the Identifier as the first step. |
| `the first step is the Identifier ('block: identifier'), not a Set block` | Something else comes first. Move the Identifier to the top. |
| `the Identifier is the first step and the only one; remove this block` | A second Identifier. Remove it. |
| `the Identifier always runs: it takes no 'if'` | Remove the `if` from the Identifier. |
| `the Identifier retries only when it asks for the password: an identifier alone never fails` | Remove `attempts`, or add `password: true`. |
| `the Identifier asks for the password with no other option, not 'passkey'` | Remove `passkey: true` when `password: true` is set. |
| `the identifier already asks for the password; remove this block, or ask for the identifier first` | Remove the Password block, or remove `password: true` from the Identifier. |
| `unknown block '<id>'; the blocks are ...` | A misspelled block id. Use one from the list. |
| `a <Block> block has no setting '<name>'; it has ...` | A setting the block does not declare. Use one from the list. |
| `'<setting>' is true or false` | Write a boolean. |
| `'<setting>' is a whole number of 1 or more` | Write a positive integer. |
| `attempts: must have a maximum value of 10` | Use 10 attempts or fewer. |
| `a <Kind> block takes ..., not '<key>'` | A key that this kind of body does not take. Move it to a body that takes it. |
| `step needs one of 'block', 'alternative', 'steps', 'set' or 'fail'` | An empty or unknown body. Give it one of these keys. |
| `unknown step key '<key>'` | A key that no body takes. Check the spelling. |
| `an option is one credential block or a 'fail', not a Group block` | Options hold one credential or action block, or a `fail`. |
| `a choice needs two options or more; with one, use that block as the step` | Replace the choice with its one block. |
| `Consent is a step of its own, not inside a group` | Make Consent a top level step of `login`. |
| `Consent belongs in 'login'; 'approve' asks for no consent` | Remove Consent from `approve`. |
| `the Approve QR code block approves a sign in on another device; it is a step of 'approve'` | Move Approve QR code to the `approve` section. |
| `unknown hook '<id>'; declare it under 'hooks'` | Declare the hook, or fix its id. |
| `hook file '<name>' not found` | Put the file next to the flow file, or use `source`. |
| `timeout_ms must be an integer from 1 to 2000` | Lower the timeout. |
| `unknown capability '<name>'; hooks may have 'http'` | `http` is the only capability. |
| `step id: does not match the regex pattern ^[a-z][a-z0-9_-]*$` | Use lowercase letters, digits, `_` and `-`, starting with a letter. |
| `if: does not match the regex pattern ^(request\|client\|host\|tenant\|user\|vars\|auth)...` | The path starts with an unknown namespace, or has an uppercase letter. |

### Checker errors

| Message | Meaning and fix |
|---|---|
| `a path succeeds without verifying a credential: <path>` | Some path signs the person in without a credential. The finding points at the last decision on that path. Add a credential on that branch, or remove the condition that skips it. |
| `the tenant requires MFA, but a path succeeds with faye:1fa: <path>` | The tenant requires two factors, and this path proves one. Add a second factor on that path. |
| `<handler> needs a subject a credential verified earlier in the flow, but runs first on the path <path>` | A block such as TOTP, Enroll TOTP, Add phone or Add passkey runs before any credential. Put a credential before it. |
| `screen 'passkey' acts for the signed in subject, which needs a subject a credential verified earlier in the flow, but shows first on a path` | Add passkey shows before any credential. Move it after one. |
| `<handler> verifies a credential; a consent entry only grants access to a subject its authenticate entry proved` | A credential comes after Consent. Move it before Consent. |
| `an approve entry ends with exactly one approval, after everything else; this path has <n>: <path>` | Approve QR code is missing, conditional, or not last on some path of `approve`. |
| `screen 'qr' needs the flow to have a approve entry` | The flow uses the QR code block without an `approve` section. Add one. |
| `handler '<name>' is not enabled for this tenant` | The tenant does not allow this handler, or has no delivery for this code channel. Change the tenant's settings or the flow. |
| `screen '<type>' is not enabled for this tenant` | The tenant does not allow this screen. |
| `'<path>' is not part of the host contract; a flow reads only [...], and below host.context` | The condition reads a `host.*` field that does not exist. Use one of the [host fields](#host-fields). |
| `'<path>' is not part of the client contract; a flow reads only [...], and below client.metadata` | The condition reads a `client.*` field that does not exist. |
| `hook '<id>' calls egress target '<target>', which is not granted to this tenant` | Grant the target to the tenant, or remove it from `capabilities.http`. |
| `entry '<id>' takes more than 5000 ms of hook time over its requests` | Lower the hooks' `timeout_ms`, or attach fewer hooks. |
| `entry '<id>' has more than 10000 paths or a path longer than 10000 steps; split it` | See the tip in [Limits](#limits). |
| `step id '<id>' is already used at <file:line:column>` | Step ids are unique across the flow, `approve` included. Rename one. |
| `screen '<type>' takes a path of '<prefix>' and up to three lowercase segments, such as '<prefix>/name'` | Fix the `path:`. See [Screen paths](#screen-paths). |
| `a flow has at most 64 steps`, and the other limit messages | The flow crosses a limit in [Limits](#limits). |

This is a real finding from the checker's tests. The flow skips the credential when the request comes from one address:

```yaml
flow: lax
version: 1
login:
  steps:
    identify:
      block: identifier
    credential:
      if: { not: { eq: [request.ip, "10.0.0.1"] } }
      alternative:
        password:
          block: password
        passkey:
          block: passkey
```

```text
tenant.yaml:8:11: a path succeeds without verifying a credential: identify > credential > (condition at 8 false) > succeed with no credential
```

The path lists the steps it passed, with `/option` after a choice. `(condition at 8 false)` names the condition on line 8 that the path assumed false.

### Checker warnings

Warnings do not stop a publish.

| Message | Meaning |
|---|---|
| `entry '<id>' never succeeds` | No path of the entry reaches the end successfully. |
| `no path reaches faye:2fa; clients that request it in acr_values are refused` | No path proves two factors. A client that asks for `faye:2fa` cannot sign in with this flow. |
| `never runs: its condition is false on every path that reaches it` | The step's condition is always false, usually because it reads `tenant.*` or `vars` that make it so. |
| `option '<id>' is never offered: its condition is false on every path` | The option's condition is always false. |

## Next steps

- Write a flow step by step in [Write your first flow](../build/first-flow.md).
- Copy common patterns from [Recipes](../build/recipes.md).
- Write the JavaScript behind a hook in [Hooks](../build/hooks.md).
- See what the YAML compiles to in [How the IR is designed](../understand/ir-design.md) and the [Core IR specification](../ir-spec.md).
