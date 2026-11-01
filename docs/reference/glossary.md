---
description: Short definitions of every term the Faye docs use, in alphabetical order.
---

# Glossary

This page defines the words that the Faye docs use. Each word means one thing everywhere in the docs. For how the ideas fit together, read [Core concepts](../start/concepts.md).

## A

- **acr** (authentication context class reference): The overall level of a sign in. Faye uses `faye:1fa` for one factor and `faye:2fa` for two factors of different kinds. The engine computes it from `amr`. An application asks for a level with the OpenID Connect parameter `acr_values`.
- **Admin UI**: The web console at port 3002 where operators edit, check, preview, publish and roll back flows. Also called the console. See [Admin UI overview](../console/overview.md).
- **alternative**: The YAML key of a choice. See *choice*.
- **amr** (authentication methods references): The list of methods that verified during a sign in, such as `pwd` (password), `otp` (one time code from an app), `sms`, `email`, `hwk` (a hardware bound key, such as a passkey) and `user` (user verification, such as a fingerprint). Only the engine writes it.
- **application**: A program that signs people in through the authorization server. OAuth2 calls it a client. Each application belongs to one tenant.
- **approve**: The section of a flow file that runs on a phone during a QR code sign in. It becomes the flow's approve entry.
- **attempts**: How many times a person may try a block before the step fails. Set with `with: { attempts: N }`.
- **authorization server**: The server that speaks OAuth2 and OpenID Connect and issues tokens. Faye's first one is Ory Hydra.

## B

- **binder**: A random secret, kept in a cookie, that ties a sign in to the browser that started it. Another browser cannot read or answer that sign in.
- **block**: A built in piece of a sign in with a fixed screen and a fixed handler, such as `password` or `totp`. See [Flow DSL reference](dsl.md#blocks).

## C

- **checker**: The part of Faye that lists every path through a flow and refuses unsafe flows, before publish and before preview.
- **choice**: A step with several options, written with `alternative:`. A hook, an earlier answer, or the person picks one option.
- **condition**: A test written with `if:`. A step whose condition is false is skipped. An option whose condition is false is not offered.
- **consent**: The screen where a person allows a third party application to access their account. In a flow file, consent is a step of `login`; it runs when the authorization server asks for consent.
- **continuation**: The saved state of a sign in between two screens: where the flow is, what it knows, and what it waits for. It is plain data in the database.
- **credential**: A handler that verifies something about the person, such as a password or a passkey, and proves an `amr` value.

## D

- **demo client**: A small OpenID Connect application at port 3001 that comes with the demo stack. It starts sign ins and shows the resulting ID token.
- **draft**: The version of a tenant's flow that you are editing. It is saved as you type and is not live until you publish it.

## E

- **egress**: A network call that a hook makes. A hook can call only a named target that both the hook and the tenant allow.
- **engine**: The part of Faye that runs flows, one screen at a time.
- **entry**: A part of a flow that the host can start. Each entry has a kind: `authenticate` (sign in), `consent`, or `approve` (the phone's side of a QR code sign in).
- **enumeration**: Finding out which accounts exist by watching how a system answers. Faye answers the same way for accounts that exist and accounts that do not.

## F

- **factor**: A kind of proof. Faye counts a password as knowledge; app codes, text message codes and hardware keys as possession; and user verification, such as a fingerprint, as inherence. Two factors of different kinds give `faye:2fa`. An emailed code counts as one factor alone, never as a second.
- **first party**: An application that belongs to the same owner as the tenant. A first party application is registered with `metadata.first_party = true`. The shipped flows do not ask it for consent.
- **flow**: The recipe for a sign in: the steps, their order and their conditions.
- **flow file**: The YAML file that describes a flow.

## G

- **group**: A step that holds other steps, written with `steps:`. Its condition applies to all of them.

## H

- **handler**: Built in code that checks what a person entered, such as `password.verify`.
- **hook**: A small JavaScript function that a tenant writes. It can set values, fail a step, or pick an option. It can never sign anyone in. See [Hooks](../flows/hooks.md).
- **host**: The adapter between the engine and one authorization server. Faye ships a host for Hydra.
- **hosted answer**: An answer to a later screen given early. For example, a passkey chosen from the browser's autofill on the first screen also answers the passkey screen.

## I

- **identifier**: What a person types to say who they are, such as an email address. Also the name of the first block of every sign in.
- **IR** (intermediate representation): The small JSON form that a flow file compiles to. The checker and the engine read the IR. See [How the IR is designed](../understand/ir-design.md).
- **issuer**: Built in code that makes the server side data a screen needs, such as a passkey challenge or a QR code.

## L

- **live version**: The published version that new sign ins of a tenant use.
- **Login UI**: The web app at port 3000 that draws each screen of a sign in.

## M

- **method picker**: The screen that asks a person to choose how to continue, when a choice has several options and nothing else picks one.

## N

- **nonce**: A one use value that each screen carries. An answer with an old nonce is refused, so an answer cannot be replayed.

## O

- **option**: One branch of a choice. An option is one credential block, or a `fail`.

## P

- **path**: One way a sign in can go through a flow, from the first step to success or failure. The checker lists every path.
- **preview**: A run of a draft with a real application, before you publish it.
- **publish**: To check a draft and make it the live version.

## R

- **roll back**: To make an earlier version live again. The noun is rollback.

## S

- **screen**: One page of a sign in, of a fixed type, such as `password` or `consent`. See [Sign in screens](screens.md).
- **step**: One stage of a flow, with a name, such as `identify` or `second-factor`.
- **step up**: Asking a person who already signed in for a stronger factor, for example because an application asked for `faye:2fa`.
- **subject**: The account that a sign in proves, as an identifier that the authorization server puts in the tokens.

## T

- **tenant**: One customer or product line with its own flow, applications, users, branding and policy.
- **third party**: An application that does not belong to the tenant's owner. In the shipped flows, a third party application asks the person for consent.
- **trace**: The list of events that a preview run records: the start, steps, options, screens, calls, hooks, retries, commits and the end.

## U

- **user pool**: The accounts of one tenant. An account in one tenant's pool is unknown to other tenants' applications.

## V

- **version**: A published flow, addressed by the hash of its IR. A sign in keeps the version it started with.
