---
description: What Faye is designed to do, what it will not do, and the founding decisions and trade offs behind both.
---

# Design goals and non goals

This page lists what Faye is designed to be good at, what it refuses to become, and the price of each choice. Read it before you propose a large change: a change that breaks a goal or crosses a non goal needs a decision record first. The [charter](../../CHARTER.md) is the source of these rules.

## Goals

Each goal comes from a principle of the charter. The table says what the goal means in practice, and where the code holds Faye to it.

| Goal | In practice | Held by |
|---|---|---|
| **Secure by design** | Every flow is checked before it runs, preview included. A flow with a path to success that verifies no credential is refused. Safe behavior, such as not revealing which accounts exist, is the default. | The checker, the engine's final check at success, and the conformance and fuzz tests |
| **Closed extension** | Only built in handlers verify credentials, create accounts and name the person. Hooks can only set values, fail a step or pick an option. | The hook result contract and the block catalog |
| **Assurance from facts** | `amr` and `acr` come from the credentials that verified. They cannot be configured. | The engine computes them; nothing else can write them |
| **Host neutral** | The engine talks to the authorization server only through the `FlowHost` interface. A new authorization server needs a new adapter, not engine changes. | `BoundaryTest` keeps Hydra out of the engine |
| **Out of the box** | A new tenant gets a safe working flow. Trying Faye locally takes minutes. Defaults are the recommended setup. | The demo stack and the built in blocks |
| **Easy to use** | Common flows are a few lines. Errors point at the line the author wrote. A draft can be tried with a real application before it is published. | The DSL compiler, which keeps the YAML line of every node, and the preview |
| **Configurable within bounds** | Tenants decide what happens and in what order. Appearance is limited to branding the typed screens. Deep custom logic goes in hooks. | The typed screen protocol |
| **Small and modular** | Each part has one job and a written contract, and can be replaced alone. Fewer features, done completely. | The IR, the SPIs and the decision records |

## Non goals

These are things Faye does not do, however convenient they look. A feature that moves protocol or token work into Faye is out of scope.

### Not an OAuth2 or OpenID Connect provider

Faye serves no protocol endpoints, registers no clients and never talks to an application directly. The authorization server does all of that. This lets every hour of work go into the flow engine.

### Not a token service

Faye does not issue or sign tokens and does not own the authorization server's sessions. When a flow ends, Faye reports who signed in and how. The authorization server turns that into tokens and sessions.

### Not a general UI platform

Faye shows end users only the screens that sign them in, sign them up and ask for consent, plus the logout confirmation the authorization server hands off. Profile pages and account settings belong in your product. The Admin UI exists only to build, check and try flows.

### Not a general purpose language

The flow language is closed: it has a fixed set of forms, and every flow ends. There are no loops except a bounded number of attempts, and no way to call code except built in handlers and sandboxed hooks. This is what makes it possible to check every path.

## Trade offs we accept

Every goal has a cost. These are the costs we chose to pay.

| We chose | We gave up | Why |
|---|---|---|
| Typed screens | Free form pages per tenant | One renderer is reviewed once for accessibility and security, for every tenant. |
| A closed language | Arbitrary logic in a flow | The checker can list every path and prove the safety rules hold. |
| Hooks with a closed result | Hooks that can do anything | The checks stay true without reading the hook's JavaScript. |
| Blocks with fixed screens and handlers | Assembling any screen with any handler | A step reads as one idea, and a block cannot be wired up wrong ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)). |
| One SQLite database | Horizontal scale today | One file is simple to run, back up and reason about ([ADR 0017](../adr/0017-one-sqlite-database.md)). Scale out is future work. |
| An Admin UI without sign in | Use by a team over a network | It stays on one machine until operator accounts and roles exist ([ADR 0018](../adr/0018-two-tenants-and-an-open-local-console.md)). |

## Founding decisions

The first thirteen decisions shaped everything after them. They are recorded as D1 to D13 in [ADR 0036](../adr/0036-retire-the-roadmap.md#founding-decisions). In short:

| # | Decision | Instead of |
|---|---|---|
| D1 | Let Ory Hydra handle the protocol | Building an authorization server, or Keycloak |
| D2 | YAML for people, a small IR in JSON for machines | Flows as code, BPMN, or expressions in strings |
| D3 | A closed language in which every flow ends | An embedded general purpose language |
| D4 | A step by step interpreter whose state is plain data | A recursive interpreter with server sessions |
| D5 | Each sign in keeps the flow version it started with | Always running the newest version |
| D6 | Typed screens | Any form the server describes |
| D7 | Ask who you are first, by default | One combined form only |
| D8 | Hooks with a closed result | Hooks with full control |
| D9 | Java 21 on the server, TypeScript on the web | TypeScript everywhere |
| D10 | Safety checks only in Java; the web apps call the server | A second checker in TypeScript |
| D11 | A tree editor for blocks; the graph is read only | A free canvas, which allows shapes the language does not |
| D12 | Owned shadcn/ui components | A component library dependency |
| D13 | `amr` and `acr` computed by the engine only | Configured per flow |

## Change a goal or a decision

Decisions that change a boundary are recorded as architecture decision records in [`docs/adr/`](../adr/README.md). To change one, write a new record that supersedes it. Only a person, through such a record, changes the charter.

## Next steps

- See how the goals shape the system: [Architecture](architecture.md).
- See the guarantees one by one: [Security model](security.md).
