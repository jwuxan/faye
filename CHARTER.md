# Faye

This charter says what Faye is for, what it will not become, and the principles that settle hard tradeoffs. The [roadmap](docs/roadmap/README.md) says how and when; [ADRs](docs/adr/README.md) record individual decisions. Both must stay consistent with this charter.

## Mission

Faye is a sign in and sign up flow engine that runs as middleware between the browser and an authorization server. It is secure by design, works out of the box, and lets each tenant shape how people get into their accounts without writing a login app.

The authorization server keeps the protocol. Faye owns the rest of the sign in: every screen a person sees and every decision about how they prove who they are.

## What Faye is

1. A flow engine. It runs sign in, sign up and consent flows to completion, one screen at a time, without losing a person's progress.
2. A YAML DSL for the skeleton. Tenants declare the steps, branches and credentials of a flow as data that people can read, diff and review.
3. Sandboxed JavaScript hooks for depth. Tenant code plugs in at declared points to score risk, route, or enrich. It can steer within the skeleton but cannot weaken its guarantees.
4. The UI, included. Faye renders every screen of a flow from a fixed set of typed screen templates, accessible and branded per tenant.
5. Middleware for any authorization server. It serves any authorization server that delegates its login and consent UI. Ory Hydra is the first and reference host.

## What Faye is not

1. Not an OAuth2 or OIDC provider. Faye does not implement protocol endpoints, register clients, or talk to relying parties.
2. Not a token service. Faye does not issue or sign tokens, and does not own the authorization server's sessions. It reports the outcome of a flow; the authorization server turns that into tokens and sessions.
3. Not a general UI platform. The only screens Faye shows end users are the ones that get them signed in, signed up, and through consent, plus the logout confirmation the authorization server hands off. Account settings, profile pages and other product UI belong elsewhere. Faye's author tooling exists only to build, check and try those flows.
4. Not a general purpose language. The DSL is closed and every flow terminates. Hooks extend decisions inside a flow; they do not replace the engine.

Ory Hydra handles the protocol so that all of our effort goes into the flow engine. A feature that would move protocol or token work into Faye is out of scope, however convenient.

## Principles

When principles conflict, the earlier one wins.

1. Secure by design. Safety is a property of the system, not of each tenant's configuration. Faye checks every flow before it runs anywhere, preview included, and refuses any sign in or sign up flow with a path to success that does not verify or enroll a credential for the subject. Reusing an existing session is allowed only when it satisfies the assurance the client asked for. Assurance claims come from what actually happened: `amr` is computed by the engine, and `acr` is derived from it, never asserted. Safe behavior, such as resisting account enumeration, is the default; a tenant may relax a default only explicitly and visibly, and nothing bypasses the checks.
2. Closed extension. Only built in, reviewed handlers verify credentials, create accounts, and bind a subject. Flows arrange those handlers; hooks may only set values, fail a step, or pick among declared options. No hook or setting can mark a person authenticated or skip a check. These limits keep the static guarantees true even though Faye cannot see inside tenant code.
3. Host neutral. The engine depends on an interface to the authorization server, never on a specific one. Supporting a new authorization server means writing an adapter, not changing the engine or the flows.
4. Out of the box. A new tenant gets a working, secure sign in flow with no flow configuration, and trying Faye locally takes minutes. Defaults are the recommended setup, and a tenant should not need to change them.
5. Easy to use. Common flows (password, passkey, one time code, second factor, sign up, consent) are short to write and easy to read. Errors point at what the author wrote. A flow can be tried against a real client before it is published.
6. Configurable within bounds. Tenants configure what happens and in what order, not how screens are built. Deep customization lives in hooks; appearance is limited to branding the typed screens.
7. Small and modular. Each part has one job and a written contract, and can be replaced without touching the others. Prefer fewer features done completely to more features done partially.

## The IR

Flows compile to an intermediate representation with a written contract. The IR exists for interop and modularity: it lets the DSL, the checker, the editor and the engine evolve separately, and lets another engine implementation prove it behaves the same.

The IR is important but optional for authors. No one needs to read or write it to build a flow; the DSL and the editor are how people do that. When the IR adds cost without serving interop or modularity, prefer the simpler path.

## Success

Faye succeeds when:

1. A team can deploy Faye with Hydra and have secure sign in and sign up working the same day, without writing code.
2. A tenant can express its real flows (identity first, passkeys, step up, MFA policy, sign up, consent) in the DSL and hooks, without forking the engine.
3. No flow or hook, however written, can produce a successful sign in unless a built in handler verified or enrolled a credential for that subject, in this flow or in the session it reuses. The design rules it out and adversarial tests check it.
4. The same flow runs unchanged with a second authorization server.

## Changing this charter

The charter changes rarely and only by a human decision. Propose a change as an ADR that names the section it changes and why. Until a human accepts that ADR, the current charter stands.
