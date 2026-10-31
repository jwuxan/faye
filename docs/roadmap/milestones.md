# Milestones

Each milestone ends with something runnable and a short demo. Order is chosen so the riskiest assumptions are tested earliest: protocol integration first, then the engine model, then the safety story, then the UI.

## M0: Walking skeleton

End to end sign in through Hydra with a hardcoded flow.

1. Repository layout, Gradle multi module, pnpm workspace, CI (build, unit tests, lint).
2. `deploy/compose.yaml`: Postgres, Redis, Hydra, Faye server, Login UI, Demo client, bootstrap job.
3. `web/packages/ui`: shadcn setup, Geist and Geist Mono, light / dark theme tokens.
4. Faye handles `login_challenge` with a hardcoded identifier then password screen; accepts with `amr: [pwd]`; consent auto accepted.
5. Demo client signs in and displays decoded ID token.

**Exit**: `docker compose up`, sign in as a seeded user from the demo client, see the ID token.

## M1: IR, engine, continuations

1. `schema/ir.schema.json`, `faye-ir` sealed types, canonical JSON and hashing.
2. `faye-dsl`: YAML to IR with source maps.
3. `faye-engine`: small step interpreter for `seq`, `step`, `screen`, `call`, `succeed`, `fail`; continuation persisted in Redis; nonce and double submit protection.
4. Screen protocol v1 (`schema/screen.schema.json`) with generated TS types; Login UI renders `identifier` and `password` from it.
5. `faye-host-mock` and the first conformance vectors.

**Exit**: the M0 flow is now defined in YAML; conformance suite green; killing and restarting the server mid flow does not lose the user's progress.

## M2: Identity first and real credentials

1. `identity.resolve`, `password.verify` (Argon2), `totp.verify`, `totp.enroll`, `webauthn.register`, `webauthn.assert`.
2. Forms: `choose`, `when`, `retry`, `set`; predicates.
3. Screens: `method_picker`, `passkey`, `otp`, `otp_enroll`, `consent`, `error`; passkey conditional UI on `identifier`.
4. `amr` / `acr` computation; `skip` and step up handling; `acr_values` enforcement.
5. Consent section linked through `context.flowId`.
6. Enumeration safe defaults.

**Exit**: three flows run from YAML with no code change: password only; passkey or password plus TOTP; consent for third party clients. `acr_values=faye:2fa` forces step up and the ID token shows the correct `amr`.

## M3: Safety and versioning

1. `faye-check`: all checks in [dsl-and-ir.md](dsl-and-ir.md), path enumeration, `acr` reachability.
2. Immutable versions, current pointer, pinning of in flight flows, rollback.
3. Tenant allowlists and MFA policy.
4. Conformance vectors generated from path enumeration.

**Exit**: a flow missing a credential on one branch is rejected with a pointer to the YAML line; publishing a new version while a user is mid login does not affect that user.

## M4: JavaScript hooks

1. `HookRuntime` interface; GraalJS implementation with deadlines, memory limits, no host access. (Built on QuickJS in WebAssembly instead: [ADR 0009](../adr/0009-m4-javascript-hooks-on-quickjs-wasm.md).)
2. Hook store by hash, capability grants, named HTTP egress targets (a fake IP reputation service in compose).
3. `pick` semantics in `choose`.
4. Licensing decision on GraalVM edition recorded in [decisions-and-risks.md](decisions-and-risks.md); Wasm fallback spiked if needed.

**Exit**: a risk hook routes high risk sign ins to TOTP; a malicious hook (infinite loop, attempt to return `{ succeed: true }`, attempt to read `auth`) fails safely and is covered by tests.

## M5: Admin UI

1. Admin API.
2. Tree editor with palette, inspector, condition builder. (Editor model, dependencies and admin sign in: [ADR 0010](../adr/0010-m5-admin-ui.md).)
3. YAML tab with inline diagnostics; Graph and Paths tabs.
4. Hooks editor and test run.
5. Versions: diff, publish, rollback. Tenant settings and branding.

**Exit**: the three M2 flows and the M4 risk flow are rebuilt entirely in the UI by someone who did not write them, with no YAML editing.

## M6: Preview loop

1. Preview client, draft previews, scenario presets.
2. Live trace over SSE with tree highlighting.
3. Demo client launch panel and result view with `amr` / `acr` expectation check.

**Exit**: compose, preview and inspect the token in under a minute per iteration, without publishing.

Built as recorded in [ADR 0011](../adr/0011-m6-preview-loop.md): a preview client per tenant runs its draft once it passes the checker; the engine traces preview flows only; the Admin UI opens the demo client in a popup rather than embedding it. `deploy/smoke.sh` step 11 times one iteration through the Admin UI's proxy and the demo client.

## M7: Reuse proof

1. Second `FlowHost`: a Keycloak adapter (or, at minimum, a second protocol host such as a generic redirect based host) with **no changes** to `faye-engine`.
2. Write up results against the six goals in [README.md](README.md); decide go / no go for production hardening.

**Exit**: same published flow drives sign in through a second host; engine module diff is empty.
