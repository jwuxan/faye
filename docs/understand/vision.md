---
description: Why Faye exists, the outcome it aims for, how we will know it worked, and how far the prototype has come.
---

# Vision and north star

This page explains why Faye exists and where it is going. It is for anyone who wants to decide whether Faye fits their problem, or who wants to help build it. The source of truth for every statement here is the [charter](../../CHARTER.md). If this page and the charter disagree, the charter wins.

## The north star

> A team deploys Faye next to its authorization server and has secure sign in and sign up working the same day, without writing code. Then it shapes every sign in to its needs, and no flow, however it is written, can let a person in without a verified credential.

Two promises sit in that sentence. The first is speed: sign in should take an afternoon, not a project. The second is safety: the speed must never cost security. Faye keeps both by moving sign in logic out of code and into checked data.

## Why Faye exists

Most authorization servers fall into one of two groups.

- **All in one identity products** ship their own login screens. They are fast to start, but the sign in is shaped by the product. Changing it often means themes, plugins or forks.
- **Headless authorization servers**, such as Ory Hydra, speak OAuth2 and OpenID Connect and ship no screens at all. They delegate the login and consent pages to an app that you write.

The headless model gives full control, but every team then writes the same hard app again: screens, password checks, passkeys, one time codes, second factor rules, consent and risk checks. Each copy can contain the one branch that skips a password.

Faye fills that gap. It is the login and consent app for a headless authorization server, built once, with the rules that keep it safe built in. A tenant changes what happens and in what order. A tenant does not write the code that verifies a credential.

## Who Faye is for

| Reader | What they need from Faye |
|---|---|
| The **integrating engineer** | Deploys Faye next to the authorization server and owns sign in for a product. Needs a secure default on day one, readable flows and errors that point at what they wrote. |
| The **flow author** | Builds and changes flows in the Admin UI, and may not be an engineer. Needs blocks, a live graph, a preview and a clear "why not" when the checker refuses. |
| The **person signing in** | Sees only the sign in screens. Needs them clear, fast, accessible and honest about errors, without leaking which accounts exist. |

## How we measure success

The charter defines success in four tests. Each one is concrete enough to check.

1. **Same day.** A team deploys Faye with Hydra and has secure sign in and sign up working the same day, without writing code.
2. **Real flows.** A tenant expresses its real flows, such as identity first sign in, passkeys, step up, a second factor policy, sign up and consent, in the flow language and hooks, without forking the engine.
3. **No way around a credential.** No flow or hook can produce a successful sign in unless a built in handler verified or enrolled a credential for that subject. The design rules it out, and adversarial tests and fuzzing check it.
4. **Another authorization server.** The same flow runs unchanged with a second authorization server.

## The principles behind the choices

When two goals conflict, Faye follows the charter's principles in order. The earlier principle wins.

1. **Secure by design.** Safety is a property of the system, not of each tenant's settings.
2. **Closed extension.** Only built in, reviewed handlers verify credentials. Flows arrange them; hooks may only set, fail or pick.
3. **Host neutral.** The engine depends on an interface to the authorization server, never on one product.
4. **Out of the box.** A new tenant gets a working, secure flow with no configuration.
5. **Easy to use.** Common flows are short to write and easy to read.
6. **Configurable within bounds.** Tenants configure what happens and in what order, not how screens are built.
7. **Small and modular.** Each part has one job and a written contract.

[Design goals and non goals](design-goals.md) explains what each principle means in practice.

## Where Faye is today

Faye is a prototype that runs end to end. The milestones that built it, M0 to M6, are closed, and their exit tests pass ([ADR 0036](../adr/0036-retire-the-roadmap.md)).

What works today:

- Sign in with password, passkey, authenticator app codes, codes sent by email or text message, and QR code sign in from a phone.
- Second factor rules, step up when an application asks for `faye:2fa`, and enrollment of an authenticator app, a passkey or a phone.
- Consent for third party applications.
- Risk hooks in a sandbox, with network access only to targets the tenant grants.
- The Admin UI: blocks and YAML editors, a live graph, path lists, preview with a real application, publish and roll back.
- One container with Hydra, Faye and the web apps, on one SQLite database.

What is planned but not built yet. Each gap is tracked in [ADR 0036](../adr/0036-retire-the-roadmap.md#open-gaps):

| Gap | Today |
|---|---|
| A second real authorization server (success test 4) | Hydra is the only real host. Tests drive flows through other hosts. |
| Sign up and account recovery | Not built. The Sign up button on the login page shows a notice. |
| Logout through Faye | Hydra's logout request is not routed to Faye yet. |
| Translated screens | The Login UI's text is English only. |
| An automated accessibility check | WCAG 2.1 AA is the target. No automated check runs yet. |
| A console for a shared deployment | The Admin UI has no sign in, so it must stay on one machine. |
| Production hardening | High availability, several regions, key rotation and rate limiting at scale are not built. |

## Next steps

- Read the trade offs behind the design: [Design goals and non goals](design-goals.md).
- See the parts and how they connect: [Architecture](architecture.md).
