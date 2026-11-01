---
description: How to write a flow file in YAML, from steps and blocks to conditions, choices, hooks, consent and QR code sign in, and what the checker refuses.
---

# Write flows in YAML

This guide shows how to write a flow file: the YAML that says how people sign in to a tenant's applications. This guide is for flow authors who edit the YAML tab of the Admin UI, or who keep flow files in a repository. For the complete rules, see the [flow DSL reference](../reference/dsl.md). For a guided first flow, see [Write your first flow](first-flow.md).

## The shape of a flow file

A flow file names the flow, then lists the steps of a sign in. This is the smallest useful flow, with a comment on each line:

```yaml
# Password only: identity first, then a password.
flow: my-flow            # the flow's id
version: 1               # your own number for this flow

login:                   # the sign in
  steps:                 # the steps, in the order they run
    identify:            # a step id that you choose
      block: identifier  # the first step is always the Identifier block
    credential:
      block: password    # the Password block: its screen and its handler
      with: { attempts: 5 }   # a setting: 5 tries instead of the default 3
```

The comment at the top of the file is the flow's description. The console shows it in the **Settings** tab.

A flow file has these top level keys. The compiler refuses any other key with `unknown key`.

| Key | Required | What it holds |
|---|---|---|
| `flow` | yes | The flow's id: a lowercase letter, then lowercase letters, digits, `-` or `_`. |
| `version` | yes | Your own version number, a whole number from 1. Faye identifies each published version by a hash of its content, not by this number. |
| `hooks` | no | The hooks that steps may run. See [Hooks](#hooks). |
| `login` | yes | The steps of a sign in, and of consent. It holds one key, `steps`. |
| `approve` | no | The steps a phone runs to approve a QR code sign in. See [The approve section](#the-approve-section). |

## Steps run in order

`login.steps` is a mapping from step id to step. The steps run from top to bottom, one after another. There are no jumps and no loops, so every flow ends.

- A step id is lowercase letters, digits, `-` and `_`, and starts with a letter. Each step id is unique in the whole flow, including the `approve` section.
- The first step of `login` is always the Identifier block. It takes no condition.
- When the last step finishes, the compiler adds success. The engine accepts that success only if a credential verified for the person. You never write "sign in" yourself.

Each step has one kind. One key decides the step's kind. The kind decides which other keys the step takes:

| Kind | Key | Also takes |
|---|---|---|
| Block | `block` | `if`, `before`, `with`, `path`, `after` |
| Choice | `alternative` | `if`, `before`, `pick`, `after` |
| Group | `steps` | `if`, `before`, `after` |
| Set | `set` | `if`, `before`, `after` |
| Fail | `fail` | `if`, `before` |

The Identifier block takes no `if`. A block takes `with` only when it has settings. The compiler refuses a key that the kind does not take. Its message lists the keys that the kind takes.

Inside a step, the parts run in this order: the `before` hook, then the block, choice, group, set or fail, then the `after` hook. If the step has a condition, the condition decides whether any of these parts run.

## Blocks

A block is one step with a fixed screen and a fixed handler. You choose which blocks run and in what order. You cannot change how a screen works. You can change only the settings that the block offers under `with`. The last column of the table shows the authentication methods references (amr): the methods that the block proves when it verifies.

| Block | What it does | amr when it verifies |
|---|---|---|
| `identifier` | Asks for the email address and looks up the account. Always the first step. | none |
| `password` | Asks for the password and verifies it. | `pwd` |
| `passkey` | Signs in with a passkey. | `hwk`, `user` |
| `totp` | Checks a code from an authenticator app. Needs an account that another credential verified earlier. | `otp` |
| `enroll-totp` | Sets up an authenticator app, then checks its first code. Needs a verified account. | `otp` |
| `email-code` | Sends a code to the account's verified email address, then checks it. | `email` |
| `sms-code` | Texts a code to the account's phone, then checks it. | `sms` |
| `qr` | Shows a QR code that a phone approves. Needs an `approve` section. | what the phone proved |
| `add-passkey` | Registers a passkey for the signed in account. | none |
| `add-phone` | Adds and verifies a phone number, and turns on texted codes. | none |
| `qr-approve` | On the phone, approves the other device. Only in `approve`, as its last step. | none |
| `consent` | Asks the person to allow the application access. | none |

An emailed code counts as one factor alone, but never as a second factor. A password and an emailed code give `faye:1fa`. A password and a texted code give `faye:2fa`.

Settings go under `with`. A setting that you leave out keeps the block's default.

| Setting | Blocks | Default | What it does |
|---|---|---|---|
| `attempts` | `password`, `totp`, `enroll-totp`, `email-code`, `sms-code`, `qr`, `add-phone` | 3 | How many times the person may try. |
| `attempts` | `passkey` | 1 | The same, for a passkey. |
| `attempts` | `identifier` | 3 | Only with `password: true`. |
| `passkey` | `identifier` | `false` | Offers passkey autofill on the identifier screen. |
| `password` | `identifier` | `false` | Asks for the password on the identifier screen. No `password` block may follow. |
| `required` | `add-passkey`, `add-phone` | `false` | Removes "Not now", so the person must enroll ([ADR 0038](../adr/0038-required-enrollment.md)). |

A tenant can change a block's defaults in **General settings**. A setting in the flow takes priority over the tenant's default. The [flow DSL reference](../reference/dsl.md) has the full rules for each block.

## Conditions with `if`

An `if` makes a step run only when its condition is true. If the condition is false, the flow skips the step and continues. On an option of a choice, an `if` decides whether the option is offered.

A condition reads a path, such as `user.has_totp`. A path with no operator is true when its value is set and is not false, zero or empty. These namespaces exist:

| Namespace | What it holds | Examples |
|---|---|---|
| `user` | The account that the Identifier block found. | `user.has_totp`, `user.has_passkey`, `user.has_phone`, `user.email_verified`, `user.mfa_required`, `user.identified_by` |
| `auth` | What verified so far in this flow. | `auth.amr`, `auth.acr`, `auth.subject` |
| `client` | The application that asked for the sign in. | `client.first_party`, `client.client_id`, `client.metadata.*` |
| `host` | What the authorization server passed on. | `host.acr_values`, `host.login_hint`, `host.ui_locales`, `host.context.*` |
| `request` | The browser's request. | `request.ip`, `request.user_agent` |
| `tenant` | The tenant's settings. | `tenant.mfa_required` |
| `vars` | Values that `set` steps and hooks wrote. | `vars.risk` |

For a test other than "is set", write a mapping with one operator:

| Operator | True when | Example |
|---|---|---|
| `eq`, `ne` | The value equals, or does not equal, the literal. | `{ eq: [vars.risk, high] }` |
| `gt`, `lt` | The value is a whole number greater, or less, than the literal. | `{ gt: [vars.score, 50] }` |
| `has` | The value is a list that contains the literal. | `{ has: [host.acr_values, "faye:2fa"] }` |
| `exists` | The value is not null. | `{ exists: host.login_hint }` |
| `not` | Its condition is false. | `{ not: client.first_party }` |
| `and`, `or` | `and`: every condition in the list is true. `or`: at least one condition is true. | `{ and: [user.has_totp, { not: user.has_passkey }] }` |

Conditions nest. This one, from [`passkey-totp.yaml`](../../apps/server/faye-app/src/main/resources/flows/passkey-totp.yaml), asks for a second factor unless the person has one already:

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

Quote a literal that contains a colon, such as `"faye:2fa"`. The checker refuses a `host.*` or `client.*` path that the host and client contracts do not list. The prototype also accepts `hydra.` as another name for `host.`.

## Choices with `alternative`

A choice offers two or more options. Each option is one block, or a `fail`. This example, from [`acme.yaml`](../../apps/server/faye-app/src/main/resources/flows/acme.yaml), offers a password, an emailed code, a texted code or a QR code:

```yaml
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
    qr:
      block: qr
```

The engine decides which option runs, in this order:

1. An option with an `if` is enabled only while its condition is true. An option without an `if` is always enabled.
2. If the choice has a `pick` hook and an option is enabled, the hook may pick one.
3. If the person already answered an option's first screen, that option runs. A passkey chosen from autofill on the identifier screen works this way.
4. If exactly one option is enabled, that option runs. The person sees no choice.
5. Otherwise, the person chooses on the method picker screen, "Choose how to continue".

If no option is enabled, the step fails with `no_method`. A choice has at most 8 options. The compiler refuses a choice with one option. Use that block as the step instead.

## Groups with `steps`

A group runs several steps under one condition. The nested steps run in order, as top level steps do.

```yaml
high-risk:
  if: { eq: [vars.risk, high] }
  steps:
    note:
      set: { reason: high_risk }
    second-factor:
      alternative:
        totp:
          if: user.has_totp
          block: totp
        refuse:
          if: { not: user.has_totp }
          fail: no_second_factor
```

A group cannot hold the Identifier, consent or QR approval blocks. Each of these blocks is a step of its own.

## Set and fail

A `set` step writes values into `vars`, where later conditions and hooks read them. A name is lowercase letters, digits and `_`, and starts with a letter. A value is a string, a whole number, or `true` or `false`.

```yaml
note:
  set: { reason: high_risk, attempts_left: 2 }
```

A `fail` step ends the sign in with a reason that you name. The engine never retries a `fail` step.

```yaml
blocked:
  if: { eq: [vars.risk, blocked] }
  fail: risk_blocked
```

## Attempts

A block that checks what the person types lets the person try again when the answer is wrong. `with: { attempts: N }` sets how many times, from 1 to 10.

```yaml
credential:
  block: password
  with: { attempts: 5 }
```

The attempts repeat only the block's screen and handler. A `before` or `after` hook on the same step runs once. When the person has no attempts left, the sign in fails.

## Hooks

A hook is JavaScript code that a tenant writes. It runs in a sandbox, at a point that the flow declares. A hook can set values in `vars`, fail a step, or pick an option. It can never sign anyone in. Declare each hook under `hooks`, then attach it to a step:

```yaml
hooks:
  risk:
    file: risk.js                           # next to the flow file; or source: with inline code
    capabilities: { http: [ip-reputation] } # egress targets the tenant grants
    timeout_ms: 500                         # 1 to 2000; 200 by default

login:
  steps:
    identify:
      block: identifier
      after: risk                           # runs after the Identifier block
```

- `before: <hook>` runs the hook before the step's block. There, a hook can fail the step, for example before Faye sends an emailed code.
- `after: <hook>` runs the hook after the step's block. A `fail` step takes no `after`.
- `pick: <hook>` on a choice lets the hook pick an enabled option.

```yaml
hooks:
  route:
    source: |
      export default function (ctx) {
        return { pick: ctx.options.includes('email') ? 'email' : 'password' }
      }

login:
  steps:
    identify:
      block: identifier
    credential:
      pick: route
      alternative:
        password:
          block: password
        email:
          if: user.email_verified
          block: email-code
```

[Hooks](hooks.md) explains what a hook receives, what it may return, egress, and its limits.

## Consent

Consent is a step like any other. It shows the consent screen, where the person allows the application access. Put the consent step last. Skip it for your own applications:

```yaml
grant:
  if: { not: client.first_party }
  block: consent
```

`client.first_party` is true for an application registered with `metadata.first_party = true`.

The consent step and every step after it run later than the sign in. They run when the authorization server asks for consent, after the authorization server accepts the sign in. So the Admin UI shows these steps in a **Consent** part after **Sign in**. The IR has two entries. A flow with no consent step has no consent part, and the authorization server grants consent without a screen.

Two rules follow from this split:

- Consent is a top level step. It cannot be inside a group or a choice.
- A step that acts for the signed in person, such as `add-phone` or `add-passkey`, must come before consent. No credential verifies in the consent part. So the checker refuses such a step after consent: `screen 'phone_enroll' acts for the signed in subject, which needs a subject a credential verified earlier in the flow, but shows first on a path`.

The compiler refuses a top level `consent:` section. Its message tells you to move the section's steps to the end of `login.steps`. See [ADR 0026](../adr/0026-consent-as-a-step.md).

## The approve section

QR code sign in has two sides. The device that wants to sign in shows a QR code with the `qr` block. The person scans it with a phone. The phone opens the Login UI and runs the flow's `approve` section. This section, from [`acme.yaml`](../../apps/server/faye-app/src/main/resources/flows/acme.yaml), proves who the person is on the phone, then approves:

```yaml
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

- The first step is the Identifier block, as in `login`.
- A credential must verify on every path, as in `login`. The tenant's multi factor authentication (MFA) setting applies here too.
- The last step is `qr-approve`, once. This block shows three numbers. The person picks the number that the other device shows.
- The section has no consent step and no `qr` block.
- The section's step ids differ from the step ids in `login`, because step ids are unique in the flow.

A flow that uses the `qr` block needs this section. The waiting device gets the `amr` that the phone proved. See [ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md).

## Screen paths

Each screen appears at a path of the Login UI. By default, the identifier screen is at `/login`, consent at `/consent`, and other screens at `/challenge/<step id>`. To change a screen's path, set `path` on the block's step:

```yaml
credential:
  block: password
  path: /challenge/secret
```

A path keeps the first segment of its default and adds up to three lowercase segments. The path is a label that the address bar shows. This label helps analytics and support. The path never selects the screen. See [ADR 0029](../adr/0029-screen-paths.md).

## What the compiler and the checker refuse

Faye reads a flow in two stages. The compiler turns the YAML into the intermediate representation (IR). It refuses what does not fit the language. The checker then verifies the IR against the catalogs and limits, follows every path through the flow, and refuses what is unsafe. The [Flow DSL reference](../reference/dsl.md) splits the checker's work into two gates: the verifier and the path checker. A flow with any error cannot be previewed or published.

Every error points to the YAML that caused it, by line and column. In the Admin UI, the status bar counts errors and warnings. Click the count to open the Problems list, and click a problem to jump to its line. Outside the console, an error reads `acme.yaml:9:11: <message>`.

Common errors, and how to fix them:

| Error | Fix |
|---|---|
| `the first step is the Identifier ('block: identifier'), not a Password block` | Make the Identifier block the first step of `login` and of `approve`. |
| `a choice needs two options or more; with one, use that block as the step` | Add an option, or replace the choice with its one block. |
| `an option is one credential block or a 'fail', not a Group block` | Make each option a single block, or a `fail`. |
| `a Password block takes 'if', 'before', 'block', 'with', 'path', 'after', not 'set'` | Move the key to a step of its own kind. |
| `attempts are a setting of a block: write 'with: { attempts: 5 }'` | Write attempts under `with`. |
| `unknown block 'pasword'; the blocks are ...` | Use a block id from the list. |
| `unknown hook 'nope'; declare it under 'hooks'` | Declare the hook, or fix its name. |
| `write a boolean as true or false, or quote 'yes'` | Write `true` or `false`. YAML's `yes` and `no` are refused. |
| `a path succeeds without verifying a credential: identify > credential > (condition at 9 false) > succeed with no credential` | Make sure every path to success passes through a credential block. |
| `totp.verify needs a subject a credential verified earlier in the flow, but runs first on the path ...` | Put a password, passkey or delivered code before the time based one time password (TOTP) block. |
| `the tenant requires MFA, but a path succeeds with faye:1fa: ...` | Add a second factor on that path, or turn off **Require MFA** for the tenant. |
| `'host.prompt' is not part of the host contract; a flow reads only [...]` | Read only the fields that the message lists. |

The checker also gives warnings, which do not block publish. For example, it warns `no path reaches faye:2fa; clients that request it in acr_values are refused`. It also warns about a step whose condition is false on every path that reaches it.

The YAML itself must be plain. The compiler refuses anchors, aliases, duplicate keys and hidden line breaks. The checker refuses an entry with more than 10,000 paths, and a flow has at most 64 steps. [ADR 0037](../adr/0037-hard-limits-on-flows-and-hooks.md) lists every limit.

## From YAML to IR

The compiler turns a flow file into the IR, a small language of JSON arrays. The engine runs the IR, and the checker reads it. You never write IR yourself. Here is [`password-only.yaml`](../../apps/server/faye-app/src/main/resources/flows/password-only.yaml) as IR. It is written as an S expression, so that people can read it:

```text
(flow 1 password-only 1
  (entry login authenticate
    (seq
      (step identify (seq (screen identifier) (call identity.resolve)))
      (step credential (retry 3 (seq (screen password) (call password.verify))))
      (succeed))))
```

Each block becomes a `screen` and a `call` of its handler. The password's 3 attempts become a `retry`. The compiler adds `succeed` at the end. A flow with an `approve` section is IR format 2. Every other flow is format 1. The JSON form is:

```json
["flow",1,"password-only",1,["entry","login","authenticate",["seq",["step","identify",["seq",["screen","identifier"],["call","identity.resolve"]]],["step","credential",["retry",3,["seq",["screen","password"],["call","password.verify"]]]],["succeed"]]]]
```

[The IR design](../understand/ir-design.md) explains why the IR exists. The [Core IR specification](../ir-spec.md) defines every form.

## Next steps

- [Flow recipes](recipes.md): copyable flows for common goals.
- [Hooks](hooks.md): write the JavaScript that a step runs.
- [Flow DSL reference](../reference/dsl.md): the full rules for every key and block.
- [The flow editor](../console/flow-editor.md): build the same flow without YAML.
