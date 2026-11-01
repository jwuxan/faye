---
description: What Faye is designed to do, what it does not do, and the founding decisions and trade offs behind both.
---

# Design goals and non goals

This page lists what Faye is designed to be good at, what it refuses to become, and the price of each choice. Read this page before you propose a large change. A change that breaks a goal or crosses a non goal needs a decision record first. The [charter](../../CHARTER.md) is the source of these rules.

## Goals

Each goal comes from a principle of the charter. The table says what each goal means in practice, and which part of the code enforces it.

| Goal | In practice | Enforced by |
|---|---|---|
| **Secure by design** | The checker checks every flow before it runs, also before a preview. The checker refuses a flow with a path to success that verifies no credential. Safe behavior, such as not revealing which accounts exist, is the default. | The checker, the engine's final check at success, and the conformance and fuzz tests |
| **Closed extension** | Only Faye's own built in handlers check credentials and decide which account is signing in. Sign up, when it exists, follows the same rule. Hooks can only set values, fail a step or pick an option. | The fixed list of hook results, and the fixed list of built in blocks |
| **Assurance from facts** | The authentication methods references (`amr`) and the authentication context class reference (`acr`) come from the credentials that verified. They cannot be configured. | The engine computes them. Nothing else can write them |
| **Host neutral** | The engine talks to the authorization server only through the `FlowHost` interface. A new authorization server needs a new adapter, not engine changes. | A build test, `BoundaryTest`, fails if the engine names Hydra |
| **Out of the box** | A new tenant gets a safe working flow. Trying Faye locally takes minutes. Defaults are the recommended setup. | The demo stack and the built in blocks |
| **Easy to use** | Common flows are a few lines. Errors point at the line the author wrote. You can try a draft with a real application before you publish it. | The compiler for the flow domain specific language (DSL), which keeps the YAML line of every node, and the preview |
| **Configurable within bounds** | Tenants decide what happens and in what order. Appearance is limited to branding the typed screens. Deep custom logic goes in hooks. | The typed screen protocol |
| **Small and modular** | Each part has one job and a written contract, and can be replaced alone. Fewer features, done completely. | The intermediate representation (IR), the service provider interfaces (SPIs) and the decision records |

## Non goals

Faye does not do these things, even when they look convenient. Faye does not take over work that the authorization server does, such as protocol endpoints, tokens or sessions.

### Not an OAuth2 or OpenID Connect provider

Faye serves no protocol endpoints, registers no clients and never talks to an application directly. The authorization server does all of that. So all of the work can go into the flow engine.

### Not a token service

Faye does not issue or sign tokens and does not own the authorization server's sessions. When a flow ends, Faye reports who signed in and how. The authorization server turns that result into tokens and sessions.

### Not a general UI platform

Faye shows end users only the screens that sign them in and ask for consent, and later the screens that sign them up. The charter also allows a logout confirmation that the authorization server hands off. That screen is planned and not built yet. [ADR 0036](../adr/0036-retire-the-roadmap.md#open-gaps) lists it as gap R2. That record numbers the open gaps R1 to R12. Profile pages and account settings belong in your product. The Admin UI exists only to build, check and try flows.

### Not a general purpose language

The flow language is closed. It has a fixed set of forms, and every flow ends. The only loop is a bounded number of attempts. The only way to call code is through built in handlers and sandboxed hooks. These limits make it possible to check every path.

## Trade offs we accept

Every goal has a cost. These are the costs that we chose to accept.

| We chose | We gave up | Why |
|---|---|---|
| Typed screens | Free form pages per tenant | One renderer serves every tenant, so each accessibility or security fix is made once. |
| A closed language | Arbitrary logic in a flow | The checker can list every path and prove the safety rules hold. |
| Hooks with a closed result | Hooks that can do anything | The checks stay true without reading the hook's JavaScript. |
| Blocks with fixed screens and handlers | Assembling any screen with any handler | A step reads as one idea, and a block cannot be connected the wrong way ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)). |
| One SQLite database | Horizontal scale today | One file is simple to run, back up and understand ([ADR 0017](../adr/0017-one-sqlite-database.md)). Scale out is not built yet ([ADR 0036](../adr/0036-retire-the-roadmap.md#open-gaps), R1). |
| An Admin UI without sign in | Use by a team over a network | The Admin UI stays on one machine until operator accounts and roles exist ([ADR 0018](../adr/0018-two-tenants-and-an-open-local-console.md)). |

## Founding decisions

The first thirteen decisions shaped everything after them. They are recorded as D1 to D13 in [ADR 0036](../adr/0036-retire-the-roadmap.md#founding-decisions). In short:

| # | Decision | Instead of |
|---|---|---|
| D1 | Let Ory Hydra handle the protocol | Building an authorization server, or Keycloak |
| D2 | YAML for people, a small IR in JSON for machines | Flows as code, BPMN, or expressions in strings |
| D3 | A closed language in which every flow ends | An embedded general purpose language |
| D4 | A step by step interpreter whose state is plain data | An interpreter that keeps each sign in in server memory |
| D5 | Each sign in keeps the flow version it started with | Always running the newest version |
| D6 | Typed screens | Any form the server describes |
| D7 | Ask who you are first, by default | One combined form only |
| D8 | Hooks with a closed result | Hooks with full control |
| D9 | Java 21 on the server, TypeScript on the web | TypeScript everywhere |
| D10 | Safety checks only in Java; the web apps call the server | A second checker in TypeScript |
| D11 | A tree editor for blocks; the graph is read only | A free drawing canvas, where you could draw flows that the language cannot express |
| D12 | Owned shadcn/ui components | A component library dependency |
| D13 | `amr` and `acr` computed by the engine only | Configured per flow |

## Change a goal or a decision

Faye records each decision that changes a boundary as an architecture decision record (ADR) in [`docs/adr/`](../adr/README.md). To change a decision, write a new record that supersedes the old record. Only a person changes the charter, through such a record.

## Next steps

- See how the goals shape the system: [Architecture](architecture.md).
- See the guarantees one by one: [Security model](security.md).
- Try Faye on your own machine: [Quickstart](../start/quickstart.md).
- See how other products handle the same problem: [How Faye compares](compare.md).
