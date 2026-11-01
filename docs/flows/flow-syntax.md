---
description: How to write a flow file in YAML, task by task, from steps and blocks to conditions, choices, hooks, consent and QR code sign in.
---

# Write flows in YAML

This guide shows how to write a flow file: the YAML that says how people sign in to a tenant's applications. Each section answers one task, such as "how do I make a step conditional" or "how do I add consent", with a short example. This guide is for flow authors who edit the YAML tab of the Admin UI, or who keep flow files in a repository. For a guided first flow, see [Write your first flow](first-flow.md). The [Flow DSL reference](../reference/dsl.md) is the one home for every key, block, setting, operator, limit and message.

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

Besides `flow`, `version` and `login`, a file may hold `hooks` and `approve`. The reference lists each [top level key](../reference/dsl.md#top-level-keys).

## Put steps in order

The steps under `login.steps` run from top to bottom, one after another. There are no jumps and no loops, so every flow ends. To change the order, move the steps.

- The first step of `login` is always the Identifier block.
- Each step id is unique in the whole flow, including the `approve` section.
- You never write "sign in" yourself. When the last step finishes, the engine signs the person in, but only if a credential verified for that person.

Each step has one kind: a block, a choice, a group, a set or a fail. One key decides the kind, and the kind decides which other keys the step takes. The reference lists [the five kinds of step](../reference/dsl.md#the-five-kinds-of-body) and their keys.

Inside a step, the parts run in this order: the `before` hook, then the block, choice, group, set or fail, then the `after` hook. If the step has a condition, the condition decides whether any of these parts run.

## Add a block

A block is one step with a fixed screen and a fixed handler, such as `password` or `totp`. You choose which blocks run and in what order. You cannot change how a screen works. You can change only the settings that the block offers under `with`:

```yaml
identify:
  block: identifier
  with: { passkey: true }   # offer passkey autofill on the first screen
```

A setting that you leave out keeps the tenant's default, or else the block's own default. The reference lists [every block](../reference/dsl.md#blocks), with its screen, its settings and the `amr` that it proves.

## Make a step conditional

Add `if` to a step to run it only when a condition is true. If the condition is false, the flow skips the step and its hooks, and continues. This step asks for an authenticator app code only from people who set one up:

```yaml
second-factor:
  if: user.has_totp
  block: totp
```

A condition reads a path, such as `user.has_totp`. A path alone is true when its value is set and is not false, zero or empty. For any other test, write a mapping with one operator. Conditions nest. This one, from [`passkey-totp.yaml`](../../apps/server/faye-app/src/main/resources/flows/passkey-totp.yaml), asks for a second factor unless the person has one already:

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

Quote a literal that contains a colon, such as `"faye:2fa"`. The reference lists the [paths that a condition can read](../reference/dsl.md#path-namespaces) and [every operator](../reference/dsl.md#operators).

## Offer a choice

Write `alternative` to offer two or more options. Each option is one block, or a `fail`. An `if` on an option decides whether the option is offered. This example, from [`acme.yaml`](../../apps/server/faye-app/src/main/resources/flows/acme.yaml), offers a password, an emailed code, a texted code or a QR code:

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

If only one option is enabled, that option runs and the person sees no choice. If several are enabled, the person picks one on the method picker screen, unless a `pick` hook picks first. If none is enabled, the step fails. The reference explains [how a choice decides](../reference/dsl.md#choices), step by step.

## Run several steps under one condition

Write `steps` to make a group. The nested steps run in order, and the group's condition applies to all of them:

```yaml
high-risk:
  if: { eq: [vars.risk, high] }
  steps:
    note:
      set: { reason: high_risk }
    second-factor:
      block: totp
```

A group cannot hold the Identifier, consent or QR approval blocks. Each of these blocks is a step of its own.

## Set a value or end the sign in

A `set` step writes values into `vars`, where later conditions and hooks read them:

```yaml
note:
  set: { reason: high_risk, attempts_left: 2 }
```

A `fail` step ends the sign in with a reason that you name:

```yaml
blocked:
  if: { eq: [vars.risk, blocked] }
  fail: risk_blocked
```

## Let the person try again

A block that checks what the person types lets the person try again after a wrong answer. Set how many tries in total, from 1 to 10, with `with: { attempts: N }`:

```yaml
credential:
  block: password
  with: { attempts: 5 }
```

The attempts repeat only the block's screen and handler. A hook on the same step runs once. The reference has the [rules for attempts](../reference/dsl.md#attempts-and-retries).

## Add a hook

A hook is JavaScript code that a tenant writes. It can set values in `vars`, fail a step, or pick an option of a choice. It can never sign anyone in. Declare each hook under `hooks`, then attach it to a step with one of three keys:

- `before: <hook>` runs the hook before the step's block. There, a hook can fail the step, for example before Faye sends an emailed code.
- `after: <hook>` runs the hook after the step's block. A `fail` step takes no `after`.
- `pick: <hook>` on a choice lets the hook pick an enabled option.

This example scores risk after the Identifier, and lets a second hook route the choice:

```yaml
hooks:
  risk:
    file: risk.js                           # next to the flow file
    capabilities: { http: [ip-reputation] } # egress targets that the tenant grants
    timeout_ms: 500
  route:
    source: |
      export default function (ctx) {
        return { pick: ctx.options.includes('email') ? 'email' : 'password' }
      }

login:
  steps:
    identify:
      block: identifier
      after: risk
    credential:
      pick: route
      alternative:
        password:
          block: password
        email:
          if: user.email_verified
          block: email-code
```

[Hooks](hooks.md) explains what a hook receives, what it may return, egress and its limits. The reference lists the [hook keys](../reference/dsl.md#hook-keys).

## Add consent

Consent is a step like any other. It shows the consent screen, where the person allows the application access. Put the consent step last. To skip it for your own applications, add a condition:

```yaml
grant:
  if: { not: client.first_party }
  block: consent
```

`client.first_party` is true for an application registered with `metadata.first_party = true`.

The consent step and every step after it run later than the sign in. They run when the authorization server asks for consent, after it accepts the sign in. So the Admin UI shows these steps in a **Consent** part after **Sign in**. Two rules follow:

- Consent is a top level step. It cannot be inside a group or a choice.
- A step that verifies a credential, or that acts for the signed in person, such as `add-phone`, must come before consent.

A flow with no consent step shows no consent screen. The reference explains the [consent split](../reference/dsl.md#consent-split). See [ADR 0026](../adr/0026-consent-as-a-step.md).

## Add QR code sign in

QR code sign in has two sides. The device that wants to sign in shows a QR code with the `qr` block. The person scans the code with a phone. The phone runs the flow's `approve` section, which proves who the person is, then approves. This section is from [`acme.yaml`](../../apps/server/faye-app/src/main/resources/flows/acme.yaml):

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

The section starts with the Identifier, verifies a credential, and ends with `qr-approve`. A flow that uses the `qr` block needs this section. The waiting device gets the `amr` that the phone proved. The reference lists the [rules of the approve section](../reference/dsl.md#approve-section). See [ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md).

## Change a screen's path

Each screen appears at a path of the Login UI, such as `/login` or `/challenge/credential`. To change the path that the address bar shows, set `path` on the block's step:

```yaml
credential:
  block: password
  path: /challenge/secret
```

The path is a label for analytics and support. It never selects the screen. The reference lists the [default paths and the rule for a path](../reference/dsl.md#screen-paths).

## Fix what the compiler and the checker refuse

Faye reads a flow in two stages:

1. **The compiler** turns the YAML into the intermediate representation (IR), and checks the IR against its schema. It refuses what does not fit the language.
2. **The checker** reads the IR. It first verifies the IR: every screen and handler is in the catalogs, and the flow stays within the hard limits. Then it follows every path through the flow and refuses what is unsafe or breaks the tenant's policy.

A flow with any error cannot be previewed or published. A warning does not block a publish.

Every error points to the YAML that caused it, by line and column. In the Admin UI, the status bar counts errors and warnings. Click the count to open the Problems list, and click a problem to jump to its line. Outside the console, an error reads `acme.yaml:9:11: <message>`.

The reference lists every [compiler message](../reference/dsl.md#compiler-messages), [checker error](../reference/dsl.md#checker-errors) and [checker warning](../reference/dsl.md#checker-warnings), each with its fix. It also lists the [YAML rules](../reference/dsl.md#yaml-rules), such as no anchors and no `yes` for true, and the [limits](../reference/dsl.md#limits) of a flow.

## From YAML to IR

You never write IR yourself. The compiler writes it, the checker reads it, and the engine runs it. Here is [`password-only.yaml`](../../apps/server/faye-app/src/main/resources/flows/password-only.yaml) as IR, written as an S expression so that people can read it:

```text
(flow 1 password-only 1
  (entry login authenticate
    (seq
      (step identify (seq (screen identifier) (call identity.resolve)))
      (step credential (retry 3 (seq (screen password) (call password.verify))))
      (succeed))))
```

Each block becomes a `screen` and a `call` of its handler. The password's 3 attempts become a `retry`. The compiler adds `succeed` at the end. [How the IR is designed](../understand/ir-design.md) explains why the IR exists. The [Core IR specification](../ir-spec.md) defines every form.

## Next steps

- [Flow recipes](recipes.md): copyable flows for common goals.
- [Hooks](hooks.md): write the JavaScript that a step runs.
- [Flow DSL reference](../reference/dsl.md): the full rules for every key and block.
- [Edit and publish a flow](../console/flow-editor.md): build the same flow without YAML.
