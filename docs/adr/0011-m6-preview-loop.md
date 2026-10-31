---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# M6: Preview loop

## Context and Problem Statement

M6 ([milestones.md](../roadmap/milestones.md)) closes the loop between "what I configured" and "what token was issued" ([preview-and-demo-client.md](../roadmap/preview-and-demo-client.md)): a preview client that runs the tenant's draft without publishing it, scenario presets, a live trace of the run highlighted in the block tree, and a result view in the demo client that checks the issued `amr` / `acr` against what path enumeration predicted.

Exit: compose, preview and inspect the token in under a minute per iteration, without publishing.

Until M5 a login always ran the tenant's current version, the engine reported nothing about a run but its outcome, and the demo client showed only the ID token. Open question 5 in [decisions-and-risks.md](../roadmap/decisions-and-risks.md) asked where draft previews are allowed in production.

## Decision Drivers

* Charter principle 1: "Faye checks every flow before it runs anywhere, preview included."
* Charter principle 5: "A flow can be tried against a real client before it is published."
* Charter principle 3 and AGENTS.md rule 3: the engine depends on `FlowHost`, never on Hydra.
* Charter, what Faye is not: no token service. Hydra issues tokens; Faye reports outcomes.
* Production clients only ever see published versions (preview-and-demo-client.md).
* A trace must not become a log of every user's sign in.

## Considered Options

1. How a preview runs the draft
   * A. Publish the draft to a shadow tenant (`<tenant>:preview`) and point the preview client there.
   * B. The engine asks a `Previews` lookup for the version when the client's metadata says `preview: true`; the app compiles the draft, checks it under the tenant's policy, stores it by hash without adding it to any history, and returns it. A draft that fails the checker is not run.
2. Which clients are preview clients
   * A. One shared preview client; the tenant comes from a request parameter.
   * B. One preview client per tenant (`preview-<tenant>`), registered like the demo clients with `metadata.tenant` and `metadata.preview = true`.
3. Where the trace comes from
   * A. Reconstruct it in the app from successive `FlowState`s.
   * B. The engine emits typed trace events (step entered, option chosen and by whom, screen shown, call and its outcome, hook outcome, retry, end) for preview flows only, after the state they belong to is saved.
4. How the live trace reaches the browser
   * A. Redis pub/sub and a listener container per server.
   * B. The app appends events to Redis lists (per flow, and a capped per tenant feed with a sequence number); the SSE endpoint polls the tenant feed every 250 ms and forwards new events.
   * C. In process only.
5. How the demo client finds the trace of the token it got
   * A. Correlate by subject and time.
   * B. For preview clients only, the consent accept adds the login's flow id as an ID token claim `faye_flow_id`; the demo client fetches `/api/preview/flows/{flowId}`.
6. How a result is checked against the prediction
   * A. In the browser, matching the trace against the paths the Paths tab already has.
   * B. In Java: the server reduces the trace and every enumerated path of the version the flow ran to the same sequence (steps, options, calls with what they proved, failed hooks) and returns the matching paths' `amr` / `acr`.
7. Scenario presets
   * A. Presets only set launch parameters.
   * B. Presets also seed preview only test accounts (fresh, or with TOTP enrolled) and set `request.*` overrides (a high risk IP) that only preview flows see.
8. Previews in production (open question 5)
   * A. Always on.
   * B. Off unless `FAYE_PREVIEW_ENABLED=true`; compose turns it on. When off, a preview client's sign in is refused.

## Decision Outcome

1B, 2B, 3B, 4B, 5B, 6B, 7B, 8B. The storage of 4B moved from Redis lists to the `faye_trace` table of the one SQLite database ([ADR 0017](0017-one-sqlite-database.md)): events are numbered per tenant, the latest 200 make the feed, and the feed still polls every 250 ms (`PreviewFeed`).

**Engine.** `Previews` (in `faye-engine`) has three methods: `version(tenant)` (empty refuses the sign in with `preview_unavailable`), `request(tenant)` (overrides of `request.*`), and `trace(state, event)`. A client is a preview client when its `metadata.preview` is `true`; that is client metadata from `FlowHost`, like `metadata.tenant`, so nothing Hydra specific enters the engine. Only preview flows are traced; the events of a run are emitted after its state is saved, so a submit that loses a race traces nothing. `FlowVersions.store` keeps a version without publishing it, so a preview flow can pin its draft and the consent that follows runs the same version. Conformance vectors gain `preview`, `draft`, `scenario` and `expect_trace`.

**Trace events** (JSON, also the conformance format): `{event, id?, outcome?, amr?, acr?}` with `event` one of `start` (`id` version hash, `outcome` section), `step`, `option` (`outcome` `only`, `user` or `hook`), `screen`, `call` (`outcome` `ok` or the error; `amr` what it proved), `hook` (`outcome` `ok` or the failure), `retry` (`outcome` the error being retried), `end` (`outcome` `succeed` or the reason; `amr`, `acr` for a login). They carry ids and codes, never what the person typed.

**Admin API** (admin token as before):

```
PUT  /admin/tenants/{t}/preview/scenario      { request: { ip?, user_agent? } } for the tenant's next preview runs
POST /admin/tenants/{t}/preview/users         { totp } → a fresh preview only account: identifier, password, totpSecret
GET  /admin/tenants/{t}/preview/events        text/event-stream of the tenant's preview trace events
GET  /admin/tenants/{t}/preview/flows/{id}    a preview run: events, visits, result, expected paths
GET  /api/preview/flows/{id}                  the same, without the token; the flow id is the capability
```

**Preview only accounts.** `User.preview` marks seeded accounts. `identity.resolve` and every credential handler treat them as unknown unless the client is a preview client, so an account an operator seeded can never sign in to a production client. Since [ADR 0018](0018-two-tenants-and-an-open-local-console.md) a seeded account also carries the tenant it was seeded for, and is unknown to other tenants' clients, preview clients included.

**Web.** The Admin UI's Preview panel replaces the inspector beside the block tree: presets (New user, User with TOTP, High risk IP, Client requests MFA), launch parameters, a TOTP code for the seeded account, and the live trace; the tree highlights the blocks the run visited and the one waiting on a screen. Launch opens the demo client in a popup window (embedding would need passkey permissions and third party cookie care for little gain). The demo client gains the preview clients, `max_age` and `ui_locales`, and a result view with the trace and the expectation check.

### Consequences

* Good, because no draft runs without passing the checker, and only clients an operator registered as preview clients ever run one.
* Good, because the engine change is host neutral, and the trace and draft selection are pinned by conformance vectors any engine must pass.
* Good, because the expectation check reuses path enumeration, so a mismatch is a real disagreement between checker and engine.
* Bad, because preview clients get one extra ID token claim, `faye_flow_id`. Production clients' tokens are unchanged.
* Bad, because every previewed draft stays in the version store (content addressed, a few KiB each), like published versions.
* Bad, because the SSE feed polls the trace store (SQLite since [ADR 0017](0017-one-sqlite-database.md)); at 250 ms per open editor this is cheap at prototype scale but not free.
* Bad, because a "user with passkey" preset is not offered: a passkey needs an authenticator in the operator's browser. Passkey paths are previewed by registering one during a preview run.
* Neutral: one scenario per tenant, last write wins, like drafts.
* Known limits, from an independent review, not fixed in M6:
  * A preview only account is refused by every handler for production clients, but the engine's remembered session shortcut (`skip`) does not consult the user store. That is safe only while the host never remembers sessions (`HydraFlowHost` sends `remember: false`).
  * ~~Seeded accounts are global, so they work with every tenant's preview client, not only the tenant they were seeded for.~~ Fixed by [ADR 0018](0018-two-tenants-and-an-open-local-console.md): a seeded account carries its tenant, and `IdentityHandlers` treats it as unknown to other tenants' clients.
  * The feed's single polling thread writes to every follower. A client that stops reading can stall the others until the servlet write timeout.
  * Expected paths are enumerated under the tenant's settings when the run is read, not when it ran. A settings change in between can show a correct run as a mismatch.
  * The checker models `subject_mismatch` as a retryable failure, while the engine ends the section. A run that hits it on an attempt other than the last matches no path.

### Confirmation

* `cd server && ./gradlew build`: conformance vectors `preview-*.yaml`; `MilestoneSixTest` (draft runs for the preview client and not for the production client, a draft failing the checker is refused, scenario overrides, preview only accounts, trace, run summary with expected paths, SSE feed); `PreviewsOffTest` (with previews off, a preview client's sign in is refused and the preview API is absent); `IdentityFlowTest` for preview only accounts.
* `cd web && pnpm test`: the TOTP helper against RFC 6238, and trace visit keys.
* `deploy/smoke.sh` step 11 (tenant `acme`, client `preview-acme`): through the Admin UI's proxy, save a draft, set a scenario, seed an account, sign in with the tenant's preview client, read the token and the expectation check from the demo client, see the events on the SSE feed, and confirm the tenant's current version did not change; the step fails if one iteration takes a minute or more. Then the seeded account fails on the tenant's production application.

Run on 2026-10-31, after [ADR 0017](0017-one-sqlite-database.md) and [ADR 0018](0018-two-tenants-and-an-open-local-console.md): `./gradlew build` passes (`MilestoneSixTest` 6, `PreviewsOffTest` 1, `IdentityFlowTest` 8, and the 9 `preview-*.yaml` vectors among 280 conformance cases); `pnpm test` passes (12 tests); `deploy/smoke.sh` passes all 13 steps on a fresh compose stack, and step 11's iteration took 2 s.
