# Architecture

## Components

```
                ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
                │   Admin UI   │   │   Login UI   │   │ Demo client  │
                │ compose flow │   │ typed screens│   │  OIDC RP     │
                └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
                       │ edit / validate  │ screen protocol  │ authorize / token
                       │ publish          │                  ▼
┌──────────────────────┴──────────────────┴────────┐   ┌──────────────┐
│                 Faye server (Java)               │◀─▶│  Ory Hydra   │
│  ┌───────────────────┐   ┌─────────────────────┐ │   │  OAuth2/OIDC │
│  │   Control plane   │   │      Runtime        │ │   └──────────────┘
│  │ YAML ⇄ IR compile │──▶│ suspendable engine  │ │
│  │ static checks     │   │ hook sandbox        │ │
│  │ versioned publish │   │ FlowHost adapter    │ │
│  └─────────┬─────────┘   └──────────┬──────────┘ │
└────────────┼────────────────────────┼────────────┘
             ▼                        ▼
      Tenant config            Identity store      Flow state
   (flow versions, hooks)   (users, credentials)  (continuations)
```

The control plane and the runtime share exactly one thing: **an immutable flow version** (IR plus pinned hook hashes) addressed by its content hash. All safety guarantees are established in the control plane at publish time; the runtime trusts only published versions, never raw configuration.

## Boundaries that make Faye reusable

1. **FlowHost**: the engine never imports Hydra. It talks to the authorization server through `load`, `accept`, `reject`. Hydra is the first implementation; a mock host and later a Keycloak host prove the boundary.
2. **Typed screen protocol**: the Login UI knows a fixed set of screen types and capabilities, not flows. One frontend serves every tenant and every flow.
3. **Closed hook output**: hooks may only `set` variables, `fail`, or `pick` among declared options. Static checks therefore remain valid at runtime.
4. **IR as JSON with a written spec and a conformance suite**: the engine can be reimplemented in another language and verified against the same test vectors.

## Tech stack

| Area | Choice | Reason |
|---|---|---|
| Server | Java 21, Spring Boot 3, Gradle | Team production language; sealed interfaces and records model the IR with exhaustive pattern matching |
| YAML | Jackson YAML | Parse to records; structural validation via JSON Schema |
| IR contract | JSON Schema in `schema/` | Single source of truth; TS types generated for the web apps |
| Hydra client | Ory Hydra Java SDK | Official admin API client |
| Hooks | QuickJS on WebAssembly (quickjs4j) behind a `HookRuntime` interface | Same language as authors expect; hard memory cap, pure Java; swappable (see [hooks.md](hooks.md), [ADR 0009](../adr/0009-m4-javascript-hooks-on-quickjs-wasm.md)) |
| WebAuthn | webauthn4j | Mature, used by Spring Security |
| Passwords / OTP | Argon2 (Spring Security), a TOTP library | Standard |
| Flow state | SQLite in the demo and local stacks, one database with every other store ([ADR 0017](../adr/0017-one-sqlite-database.md)); in memory map for engine tests; a production deployment plugs its own database behind `FlowStore` | Expiry aligned to challenge lifetime |
| Identity / config store | SQLite in the same file, behind `UserStore`, `FlowVersions`, `HookStore`; demo only. Faye verifies passwords but never writes one; the identity module saves only passkey enrollments and TOTP state | One database to run locally |
| Web apps | TypeScript, Next.js (App Router), pnpm workspace | Shared UI package across three apps |
| UI kit | shadcn/ui on Tailwind CSS | Owned components, consistent design system |
| Typography | System sans (UI) and system monospace (IR, YAML, tokens, codes); Geist Serif for the Faye wordmark via the `geist` package | Native UI text; mono for anything machine shaped |
| Editor | Native drag and drop (tree editor), CodeMirror 6 (YAML and hooks), an SVG tree (read only graph), lucide icons | See [admin-ui.md](admin-ui.md), [ADR 0010](../adr/0010-m5-admin-ui.md) |
| Local env | Docker Compose | Hydra and Faye on one SQLite file, Faye server, three web apps |

## Repository layout

```
faye/
├── docs/roadmap/                 this plan
├── schema/
│   ├── ir.schema.json            IR node grammar
│   ├── flow.schema.json          YAML surface grammar
│   └── screen.schema.json        typed screen protocol
├── conformance/                  engine test vectors (IR + events → expected trace)
├── server/                       Gradle multi module
│   ├── faye-ir/                  sealed Node types, (de)serialization, hashing
│   ├── faye-dsl/                 YAML → IR compiler, source maps
│   ├── faye-check/               static checks, path enumeration
│   ├── faye-engine/              interpreter, continuation model, handler registry
│   ├── faye-hooks/               HookRuntime interface + QuickJS on Wasm implementation, egress
│   ├── faye-identity/            users, password, TOTP, WebAuthn handlers
│   ├── faye-host-hydra/          FlowHost for Hydra
│   ├── faye-host-mock/           FlowHost for tests
│   └── faye-app/                 Spring Boot: screen API, admin API, wiring
├── web/                          pnpm workspace
│   ├── apps/login/               typed screen renderer
│   ├── apps/admin/               flow composer, publisher, preview host
│   ├── apps/demo-client/         OIDC relying party for previews
│   └── packages/
│       ├── ui/                   shadcn components, Geist tokens, theme
│       └── contract/             TS types generated from schema/
├── Dockerfile                    one image, every process under s6-overlay (ADR 0023):
│                                 server build, jlink JRE, web standalone, s6 runtime
└── deploy/
    ├── image/                      s6-rc service tree, scripts, stage2-hook, healthcheck
    └── compose.yaml                one faye service on the root image
```

## Request lifecycle (login)

1. Demo client redirects the browser to Hydra `/oauth2/auth`.
2. Hydra redirects to Faye `/login?login_challenge=…`.
3. Faye `FlowHost.load` fetches the login request (client, scopes, `skip`, `acr_values`), resolves the tenant, creates a `flowId`, starts the engine.
4. Engine runs until a `screen`; continuation is persisted; browser is sent to Login UI `/flow/{flowId}`.
5. Login UI fetches the screen, renders it, posts submissions back. Each post resumes the engine.
6. On `succeed`, `FlowHost.accept` sends `subject`, `amr`, `acr`, `remember`, and `context.flowId` to Hydra and returns its redirect URL.
7. Hydra redirects to `/consent?consent_challenge=…`; Faye runs the consent entry of the flow version the login ran (see [hydra-integration.md](hydra-integration.md)).
8. Hydra issues tokens to the demo client.
