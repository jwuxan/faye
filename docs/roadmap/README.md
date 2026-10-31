# Faye roadmap

Faye is a configurable login flow middleware that sits in front of an OAuth2 / OIDC authorization server. Ory Hydra speaks the protocol; Faye owns everything the user sees and every decision about how they authenticate.

This directory is the plan for building Faye from scratch. It is written to be executed in order and to be argued with: every decision below has a reason, and the reasons are what to challenge.

## Goal

Validate, as fast as possible, that a login flow can be:

1. **Declared** as data (a YAML DSL compiled to an S expression IR),
2. **Proven safe** before it runs (static checks over the IR),
3. **Extended** with small JavaScript hooks that cannot break those guarantees,
4. **Composed** by a non engineer in a drag and drop admin UI,
5. **Tried out** immediately against a real OAuth client in a built in preview,
6. **Reused** across authorization servers by swapping one adapter.

The prototype succeeds when the six statements above are demonstrably true. Success criteria per milestone live in [milestones.md](milestones.md).

## Non goals (for the prototype)

1. Being an authorization server. Hydra issues tokens, manages clients and sessions.
2. A general purpose programming language. The DSL is closed and always terminates.
3. Free form UI. Screens are typed templates; tenants configure which screens appear, not how they are built.
4. Production hardening (HA, multi region, key rotation, rate limiting at scale). Designed for, not built.
5. Federation to external IdPs. The identity first design leaves room for it (see [login-experience.md](login-experience.md)).

## Documents

| Document | Contents |
|---|---|
| [architecture.md](architecture.md) | Components, boundaries, repository layout, tech stack |
| [dsl-and-ir.md](dsl-and-ir.md) | YAML surface syntax, S expression IR, special forms, static checks |
| [engine.md](engine.md) | Suspendable interpreter, continuations, versioning, conformance suite |
| [hooks.md](hooks.md) | JavaScript hook contract, sandbox, capabilities |
| [hydra-integration.md](hydra-integration.md) | Login, consent and logout challenges, amr / acr, the FlowHost interface |
| [login-experience.md](login-experience.md) | Identity first login, typed screen protocol, design system |
| [admin-ui.md](admin-ui.md) | Composable flow editor, validation, publishing |
| [preview-and-demo-client.md](preview-and-demo-client.md) | Demo OAuth client and the live preview loop |
| [milestones.md](milestones.md) | Ordered milestones with exit criteria |
| [decisions-and-risks.md](decisions-and-risks.md) | Decision log, open questions, risks |

## One paragraph summary

A tenant composes a flow in the Admin UI. The UI edits an IR tree; the server compiles, statically checks and publishes it as an immutable version identified by a content hash. When Hydra redirects a user with a `login_challenge`, the engine loads the tenant's current version, runs it until it reaches a screen, persists the continuation under a `flowId`, and returns a typed screen to the Login UI. Each user submission resumes the continuation. JavaScript hooks may annotate state or choose among declared options but can never complete authentication. On `succeed`, the engine computes `amr` and `acr` from the credentials that actually verified and accepts the challenge through a `FlowHost` adapter. A demo OAuth client, embedded in the Admin UI as a preview, lets the author run the flow they just published and inspect the resulting tokens.
