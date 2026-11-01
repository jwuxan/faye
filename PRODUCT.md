# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary: the **integrating engineer** — the person on an internal team who deploys Faye next to Hydra, configures tenants, hooks and branding, and owns sign-in for their product. Design work serves this user first.

Confirmed secondary audiences:

- The **flow author**, possibly a non-engineer, composing flows in the Admin UI block editor (a goal of the prototype). The Admin UI must stay usable without engineering depth, but the engineer is the gatekeeper who gets a tenant to it.
- The **end user signing in**, who sees only the typed login screens. For them, security clarity and accessibility lead.

## Product Purpose

Faye is a sign-in and sign-up flow engine that runs as middleware between the browser and an authorization server (Ory Hydra is the first host). The authorization server keeps the OAuth2/OIDC protocol; Faye owns every screen the user sees and every decision about how they prove who they are.

Destination: **internal platform** — real teams and tenants inside the organization are expected to run their sign-in on it. The current stage is prototype validation, with production hardening designed-for but not yet built.

Success (from CHARTER.md): a team deploys Faye + Hydra and has secure sign-in working the same day without writing code; a tenant expresses its real flows in the DSL and hooks without forking the engine; no flow or hook can produce a successful sign-in unless a built-in handler verified or enrolled a credential; the same flow runs unchanged on a second authorization server.

## Positioning

A tenant gets production-grade login UX without building a login app, and without giving up control: flows are reviewable data (YAML → checked IR), extension happens in sandboxed hooks that provably cannot weaken the skeleton, and assurance (`amr`/`acr`) is computed from what actually verified — never asserted.

The claim a neighbor cannot truthfully copy: every published flow is statically proven safe before it runs — there is no path to success that skips credential verification — and that property holds even over tenant JavaScript, because hooks may only `set`, `fail`, or `pick` among declared options. The conformance suite and fuzz harnesses exist to keep that promise testable.

## Operating Context

- Ships as one Docker image (s6-overlay) holding Hydra, Faye, login, admin and demo processes; `make up` gives a full sandbox in minutes. `deploy/dev.sh` runs the same stack without Docker.
- Two demo tenants (`acme`, `bank`) with their own flows, user pools and applications make multi-tenancy concrete; a demo OIDC client shows the resulting token and trace.
- The Admin UI is an open local console: localhost-only, no sign-in, proxying an admin API that lives on a Unix domain socket.
- Working agreements are unusually codified: CHARTER.md (mission, non-goals, principles), `docs/adr/` (MADR 4 decision records), `src/conformance/` (language-independent engine vectors). Design changes that touch a boundary are expected to respect — and where needed update — those documents.

## Capabilities and Constraints

Binding non-goals (CHARTER.md): not an OAuth2/OIDC provider, not a token service, not a general UI platform (the only end-user screens are sign-in, sign-up, consent, logout confirmation), not a general-purpose language (the DSL is closed and every flow terminates).

- Hooks may only `set`, `fail`, or `pick` among declared options; they can never complete authentication. `amr`/`acr` are computed by the engine, never configured.
- End-user screens are typed templates from `src/schema/screen.schema.json` — never free-form UI. New surface needs go through the protocol, not around it.
- Tenant login customization is limited to logo, name (also the footer), primary color, radius, and three switches: show password, "keep me signed in", and a sign up button (a placeholder until sign up exists; ADR 0033). Layout is not tenant-configurable.
- Anti-enumeration is the default posture; a tenant may relax it only explicitly and visibly.
- All state (Faye's and Hydra's in the compose stack) lives in one SQLite file.
- Applications and Users sections of the Admin UI are read-only by design; neither OAuth clients nor directory accounts are managed inside Faye.
- Explicitly undecided / later: `acr` mapping per tenant, federation to external IdPs, production hardening (HA, multi-region, key rotation, rate limiting at scale).

## Brand Commitments

- Name: **Faye**. Wordmark: Geist Serif.
- Voice in docs: plain, precise, no marketing register. Errors point at what the author wrote.
- Binding UI constraints from the design system doc: system UI font and system monospace for all text (identifiers, OTP digits, YAML, JSON); no web fonts, no external font requests. Theme tokens throughout; the Admin UI has light and dark modes, the sign in screens are light only.

## Evidence on Hand

- A working end-to-end sandbox: two tenants, 1000 seeded accounts (Argon2 hashes; passwords only inside the container at `/var/lib/faye/demo/`), ten registered applications, a demo OIDC client that displays the decoded token and preview trace.
- `src/conformance/` engine vectors and generated path enumerations; `deploy/smoke.sh` as the scripted proof of the whole loop.
- No testimonials, named customers, benchmarks, or external claims exist — future work must not fabricate any.

## Product Principles

1. **Secure by design beats configurable.** Safety is a property of the system; the checker and the closed extension model are the feature, not a limit.
2. **Serve the integrating engineer.** Fast time-to-first-sign-in, readable YAML, errors that name what was written, and a console an engineer can hand to a non-engineer.
3. **One source of truth, several projections.** Blocks, YAML, graph and paths are views of one document; the server holds every rule.
4. **Prove it, don't promise it.** Claims (safety, assurance, path coverage) are computed and shown — in paths panels, traces, and `amr`/`acr` — never asserted in copy.
5. **Small and complete over broad and partial.** Each surface does its one job fully; charter non-goals are enforced, not negotiated.

## Accessibility & Inclusion

WCAG 2.1 AA baseline for the typed login screens: keyboard-complete, correct `autocomplete` attributes (`username webauthn`, `current-password`, `one-time-code`), focus moves to the first field on each screen, errors announced via live region.
