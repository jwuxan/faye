# Decisions and risks

## Decision log

| # | Decision | Alternatives considered | Reason |
|---|---|---|---|
| D1 | Delegate the protocol to Ory Hydra | Build an AS; Keycloak as AS | Hydra is headless by design and delegates all UI, which is exactly the seam Faye occupies |
| D2 | YAML surface, S expression IR as JSON | Code first flows; BPMN; string expressions | Data is diffable, checkable and editable by a UI; JSON arrays need no Lisp runtime |
| D3 | Closed language, always terminates | Embedded general purpose language | Enables path enumeration, static safety checks and generated tests |
| D4 | Suspendable small step interpreter with explicit continuations | Recursive interpreter with server side sessions | Continuations persist as data across requests and restarts |
| D5 | Pin flow version per flow instance | Always run latest | Publishing must never break users mid login |
| D6 | Typed screens, not free form UI | Server driven arbitrary forms | One renderer for all tenants; accessibility and security reviewed once |
| D7 | Identity first login | Combined identifier and password form | Per user adaptation, passkey friendliness, future home realm discovery |
| D8 | Hooks with closed output | Hooks with full control | Keeps static checks sound without analyzing JavaScript |
| D9 | Java 21 server, TypeScript web | All TypeScript | Team production language; sealed types give exhaustive checking |
| D10 | Safety checks only in Java; UI calls `/validate` | Duplicate checker in TS | One implementation of security rules |
| D11 | Tree block editor, graph view read only | Free node canvas | The language is a tree; canvases admit invalid shapes |
| D12 | shadcn/ui, Geist, Geist Mono | Component library dependency | Owned components, consistent look across three apps |
| D13 | `amr` / `acr` computed by the engine only | Configured per flow | Token assurance claims must reflect what actually happened |

## Open questions

1. ~~GraalVM edition and licensing for hook sandboxing.~~ Resolved by [ADR 0009](../adr/0009-m4-javascript-hooks-on-quickjs-wasm.md): hooks run on QuickJS compiled to WebAssembly, so no GraalVM edition is needed.
2. Tenant model: one Hydra per tenant, or shared Hydra with tenant in client metadata (prototype assumes shared).
3. Identity store ownership: Faye owned tables in the prototype; production may need to read an existing user directory through a handler interface.
4. Account recovery and password reset: out of scope for the prototype but must fit the same DSL (likely a separate flow kind).
5. ~~Where draft previews are allowed in production (likely never; preview tenant only).~~ Resolved by [ADR 0011](../adr/0011-m6-preview-loop.md): off unless `FAYE_PREVIEW_ENABLED=true`, only for clients registered with `metadata.preview = true`, and only drafts that pass the checker.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Sandbox escape or resource exhaustion in hooks | Critical | QuickJS in Wasm with a 16 MiB linear memory cap and a deadline per run, no imports beyond `cap`, egress checked per call, closed output enforced by the engine, adversarial tests ([ADR 0009](../adr/0009-m4-javascript-hooks-on-quickjs-wasm.md)) |
| Young hook runtime dependency (quickjs4j 0.1.x) | Medium | Pinned version, swappable behind `HookRuntime`, adversarial suite gates upgrades; GraalJS isolates as the fallback |
| Static checks give false confidence (a handler mislabeled as a credential) | High | Handler metadata reviewed in code, not config; conformance tests per handler |
| Account enumeration through identity first | Medium | Uniform responses and timing by default; explicit tenant opt out |
| Continuation references stale IR after deploys | Medium | Version pinning, retention while referenced, flows expire with Hydra challenges |
| Admin UI complexity grows faster than the language | Medium | Palette blocks map to fixed YAML fragments the one compiler reads; no feature in the UI without a form in the DSL ([ADR 0010](../adr/0010-m5-admin-ui.md)) |
| The admin token leaks through the Admin UI | High | The token stays on the Admin UI server; operators sign in through Hydra with two factors and an allowlist; state changing proxy calls need a custom header ([ADR 0010](../adr/0010-m5-admin-ui.md)). One token for all tenants: per tenant roles are later work |
| Embedding the preview breaks under third party cookie rules | Low | The Admin UI opens the preview in a popup window rather than embedding it ([ADR 0011](../adr/0011-m6-preview-loop.md)) |
| A preview leaks into production | High | Previews off by default; only preview clients run drafts, and only drafts that pass the checker; seeded accounts exist only for preview clients; only preview flows are traced ([ADR 0011](../adr/0011-m6-preview-loop.md)) |
| Scope creep toward a full IdP | High | Non goals in README; every new feature must be expressible as a handler, screen or form |
