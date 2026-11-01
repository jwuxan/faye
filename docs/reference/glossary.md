---
description: Short definitions of every term the Faye docs use, in alphabetical order.
---

# Glossary

This page defines the words that the Faye docs use. Each word means one thing everywhere in the docs. For how the ideas fit together, read [Core concepts](../start/concepts.md).

## A

- **acr** (authentication context class reference): The overall level of a sign in: `faye:1fa` for one factor, or `faye:2fa` for two factors of different kinds. The engine computes it from `amr`, and an application asks for a level with the OpenID Connect parameter `acr_values`.
- **Admin UI**: The web console at port 3002 where operators edit, check, preview, publish and roll back flows. Also called the console. See [Admin UI overview](../console/overview.md).
- **ADR** (architecture decision record): A short document in `docs/adr/` that records one decision, the options considered and why. See [Decisions](../adr/README.md).
- **allowlist**: The handlers and screens a tenant may use. The checker refuses a flow that uses anything else. Set in [General settings](../console/tenant-settings.md).
- **alternative**: The YAML key of a choice. See *choice*.
- **amr** (authentication methods references): The list of methods that verified during a sign in. Only the engine writes it, with values such as `pwd` (password), `otp` (one time code from an app), `sms`, `email`, `hwk` (a hardware bound key, such as a passkey) and `user` (user verification, such as a fingerprint).
- **application**: A program that belongs to one tenant and signs people in through the authorization server. OAuth2 calls it a client.
- **approve**: The section of a flow file that runs on a phone during a QR code sign in. It becomes the flow's approve entry.
- **attempts**: How many times a person may try a block before the step fails. Set with `with: { attempts: N }`.
- **authorization server**: The server that speaks OAuth2 and OpenID Connect and issues tokens. Faye's first one is Ory Hydra.

## B

- **binder**: A random secret, kept in a cookie, that ties a sign in to the browser that started it. Another browser cannot read or answer that sign in.
- **block**: A built in piece of a sign in with a fixed screen and a fixed handler, such as `password` or `totp`. See [Flow DSL reference](dsl.md#blocks).
- **block defaults**: A tenant's own default settings for blocks, such as the number of password attempts. A flow's `with:` still takes priority.

## C

- **challenge**: The opaque id that the authorization server gives Faye for one login or consent request, such as Hydra's `login_challenge`.
- **charter**: The project's founding document, [CHARTER.md](../../CHARTER.md). It sets Faye's mission, non goals and principles, and it wins over any other doc or decision.
- **checker**: The part of Faye that lists every path through a flow and refuses unsafe flows, before publish and before preview.
- **choice**: A step with several options, written with `alternative:`. A hook, an earlier answer, or the person picks one option.
- **Choose**: The Admin UI's name for a *choice*, in the Logic group of **Add block**.
- **client**: OAuth2's word for an *application*. The docs use "application", except in fixed names such as *demo client* and *preview client*.
- **Code by**: A ready made choice in the Logic group of the Admin UI's **Add block**. It offers each one time code that the account can use: the authenticator app if enrolled, a texted code if phone codes are on, and an emailed code if the email is verified. If more than one is offered, the person picks.
- **compare and set**: Saving a sign in's state only if no other request saved it first. This makes a double submit safe.
- **condition**: A test written with `if:`. If the condition is false, the engine skips the step, or does not offer the option.
- **conformance vector**: A test, written as data, that says how any engine must run a given IR. The vectors are in `src/conformance/`.
- **consent**: The screen where a person allows a third party application to access their account. In a flow file, consent is a step of `login` that runs when the authorization server asks for consent.
- **continuation**: The saved state of a sign in between two screens: where the flow is, what it knows, and what it waits for. It is plain data in the database.
- **credential**: The proof that a person gives to show who they are, such as a password, a passkey or a one time code.
- **credential handler**: A built in handler that verifies a credential, such as `password.verify` or `webauthn.assert`, and proves an `amr` value. The checker requires one on every path of a sign in that succeeds.

## D

- **delivery channel**: One of the two ways that Faye sends a one time code: `email` or `sms`. A channel is on when the tenant has a webhook for it, or when the demo's outbox folder is set. When a channel is off, its code blocks are off.
- **demo client**: A small OpenID Connect application at port 3001 that comes with the demo stack. It starts sign ins and shows the resulting ID token.
- **draft**: The version of a tenant's flow that you are editing. The console saves it as you type, and it is not live until you publish it.
- **DSL** (domain specific language): The YAML language for flow files. See [Flow DSL reference](dsl.md).

## E

- **effect**: A lasting change that a handler asks for, such as enrolling a phone. The engine applies it only when the entry succeeds.
- **egress**: A network call that a hook makes. A hook can call only a named target that both the hook and the tenant allow.
- **engine**: The part of Faye that runs flows, one screen at a time.
- **entry**: A part of a flow that the host can start. Each entry has a kind: `authenticate` (sign in), `consent`, or `approve` (the phone's side of a QR code sign in).
- **enumeration**: Finding out which accounts exist by watching how a system answers. Faye answers the same way for accounts that exist and accounts that do not.

## F

- **factor**: A kind of proof: a password is knowledge; app codes, text message codes and hardware keys are possession; user verification, such as a fingerprint, is inherence. Two factors of different kinds give `faye:2fa`, but an emailed code counts as one factor alone, never as a second.
- **fail closed**: When something is unclear or broken, refuse rather than continue. For example, a saved state that cannot be read ends the sign in.
- **first party**: An application that belongs to the same owner as the tenant, registered with `metadata.first_party = true`. The shipped flows do not ask a first party application for consent.
- **flow**: The recipe for a sign in: the steps, their order and their conditions.
- **flow file**: The YAML file that describes a flow.

## G

- **group**: A step that holds other steps, written with `steps:`. Its condition applies to all of them.

## H

- **handler**: Built in code that checks what a person entered, such as `password.verify`.
- **hook**: A small JavaScript function that a tenant writes. It can set values, fail a step or pick an option, but it can never sign anyone in. See [Hooks](../flows/hooks.md).
- **host**: The adapter between the engine and one authorization server. Faye ships a host for Hydra.
- **hosted answer**: An answer to a later screen given early. For example, a passkey chosen from the browser's autofill on the first screen also answers the passkey screen.

## I

- **identifier**: What a person types to say who they are, such as an email address. Also the name of the first block of every sign in.
- **idle timeout**: How long a sign in may wait for the next answer before it ends. Set per entry kind, in a flow's Settings tab.
- **IR** (intermediate representation): The small JSON form that a flow file compiles to. The checker and the engine read the IR. See [How the IR is designed](../understand/ir-design.md).
- **issuer** (credential issuer): Built in code that makes the server side data a screen needs, such as a passkey challenge or a QR code.

## K

- **keep me signed in**: A checkbox on the first screen. If the person checks it, the authorization server remembers the sign in for 30 days. Next time, Faye skips the screens if one factor is enough for the application.

## L

- **live version**: The published version that new sign ins of a tenant use.
- **Login UI**: The web app at port 3000 that draws each screen of a sign in.
- **lower**: To compile a part of the flow file into IR. For example, a `block` lowers to a `screen` and a `call`.

## M

- **method picker**: The screen that asks a person to choose how to continue. It appears when a choice has several options and nothing else picks one.
- **MFA** (multi factor authentication): Signing in with two factors of different kinds. A tenant can require it for every sign in.

## N

- **nonce**: A single use value that each screen carries. Faye refuses an answer with an old nonce, so nobody can send the same answer twice.

## O

- **one time code**: A short code that works once: from an authenticator app (TOTP), or sent by email or text message.
- **option**: One branch of a choice. An option is one credential block, or a `fail`.
- **outbox**: The database table of one time codes that wait to be emailed or texted. A worker in the server delivers them through each channel's webhook, or writes them to the *outbox folder*.
- **outbox folder**: The folder that `FAYE_DEMO_OUTBOX` names. For a channel with no webhook, Faye writes each code to a file there instead of sending it, as the demo does.

## P

- **passkey**: A credential that the browser or phone keeps, unlocked by a fingerprint, a face or a PIN. It uses the WebAuthn standard.
- **path**: One way a sign in can go through a flow, from the first step to success or failure. The checker lists every path.
- **preview**: A run of a draft with a real application, before you publish it.
- **preview client**: An application registered with `metadata.preview = true`. It runs the tenant's draft instead of the live version.
- **publish**: To check a draft and make it the live version.

## Q

- **QR code sign in**: Signing in on one device by scanning a QR code with a phone where you sign in and approve. The phone runs the flow's approve section.

## R

- **relying party**: OpenID Connect's word for an *application*.
- **roll back**: To make an earlier version live again. The noun is rollback.

## S

- **scenario**: The settings of a preview run: which account, which `acr_values`, which IP address.
- **screen**: One page of a sign in, of a fixed type, such as `password` or `consent`. See [Sign in screens](screens.md).
- **SPI** (service provider interface): The Java interfaces that a host implements to run Faye. See [Embed Faye: the host SPI](../understand/host-spi.md).
- **step**: One stage of a flow, with a name, such as `identify` or `second-factor`.
- **step up**: Asking a person who already signed in for a stronger factor, for example because an application asked for `faye:2fa`.
- **subject**: The account that a sign in proves, as an identifier that the authorization server puts in the tokens.

## T

- **tenant**: One customer or product line with its own flow, applications, users, branding and policy.
- **third party**: An application that does not belong to the tenant's owner. In the shipped flows, a third party application asks the person for consent.
- **time limit**: How long a sign in may take in total before it ends. Set per entry kind, in a flow's Settings tab.
- **TOTP** (time based one time password): A 6 digit code from an authenticator app that changes every 30 seconds.
- **trace**: The list of events that a preview run records: the start, steps, options, screens, calls, hooks, retries, commits and the end.

## U

- **user pool**: The accounts of one tenant. An account in one tenant's pool is unknown to other tenants' applications. A shared account has no tenant, so every tenant's applications can see it, and the **Users** page shows its pool as **Shared**.

## V

- **vars**: Values that a flow writes with `set:` or that a hook sets, for later conditions to test.
- **verifier**: The first part of the checker, which checks a flow against the catalogs of handlers and screens and against the hard limits. The engine also runs the verifier whenever it runs a version.
- **version**: A published flow, addressed by the hash of its IR. A sign in keeps the version it started with.
