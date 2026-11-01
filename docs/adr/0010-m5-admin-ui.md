---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# M5: Admin UI

## Context and Problem Statement

M5 adds the Admin UI: the admin API; a tree editor with palette, inspector and condition builder; a YAML tab with inline diagnostics, Graph and Paths tabs; a hooks editor with test runs; versions with diff, publish and rollback; tenant settings and branding.

Exit: the three M2 flows and the M4 risk flow are rebuilt entirely in the UI by someone who did not write them, with no YAML editing.

M3 left a token protected admin API that takes YAML ([ADR 0008](0008-m3-checks-versions-and-tenant-policy.md)), stored versions without their YAML, and tenant settings fixed in `application.yaml`. The plan had an editor over the IR, Monaco, React Flow and dnd-kit, and an Admin UI that signs in through Hydra with a fixed admin tenant flow.

## Decision Drivers

* Charter principle 1: safety checks run only in Java; the UI calls the server (AGENTS.md rule 5, D10).
* Charter, "The IR": the IR is optional for authors; "when the IR adds cost without serving interop or modularity, prefer the simpler path".
* Charter principle 5: errors point at what the author wrote.
* Charter principle 6: appearance is limited to branding the typed screens.
* Small: few dependencies, one compiler, no second implementation of any rule.

## Considered Options

1. What the editor edits
   * A. The IR, as planned; the server would need an IR to YAML decompiler for the YAML tab, hooks addressed by hash in a separate store, and source maps from editor node ids.
   * B. The YAML surface as data: the tree editor edits the same document the YAML tab shows as text, and the server compiles it with the existing compiler.
2. How the Admin UI authenticates
   * A. The server validates Hydra access tokens (introspection) on every admin call.
   * B. The Admin UI is a backend for frontend: it signs operators in through Hydra with a fixed admin tenant flow, keeps its own session, and calls the server with the bearer token from M3, which never reaches the browser.
   * C. Keep the bearer token only, typed into the UI.
3. Tenant settings
   * A. Fixed in `application.yaml` (status quo); the UI shows them.
   * B. Stored per tenant in Redis over the configured defaults; MFA policy, handler and screen allowlists and branding editable; egress grants stay operator config.
4. Editor dependencies
   * A. As planned: Monaco, React Flow, dnd-kit.
   * B. CodeMirror 6 for YAML and hook source, an SVG tree drawn by the app for the graph, native HTML drag and drop with keyboard alternatives, `yaml` for parsing with positions, `lucide-react` for icons.
5. Hook editing
   * A. Hooks as separate resources (`PUT /hooks/{h}` returning a hash), referenced by hash from the flow.
   * B. Hooks inline in the flow document (`hooks: {id: {source: ...}}`), as the YAML already allows; a test endpoint runs a definition without storing it.
6. Branding delivery
   * A. In the engine's `ScreenView`.
   * B. Added by the app's screen API from the flow's tenant; the engine is unchanged.

## Decision Outcome

1B, 2B, 3B, 4B, 5B, 6B. 2B was later superseded by [ADR 0018](0018-two-tenants-and-an-open-local-console.md), and the Redis storage of 3B and of versions by [ADR 0017](0017-one-sqlite-database.md); see "Changes since this decision" below. `acr` mapping (tenant settings) is not part of M5: renaming `faye:1fa`/`faye:2fa` per tenant touches `acr_values` enforcement in the engine and needs its own decision and conformance vectors.

**Document model (1B).** The flow is a YAML document. The block editor holds it as ordered data (sections, steps, options, bodies) and writes it back as YAML text; the YAML tab edits that text; both go to the server, which compiles it with `FlowCompiler`. Diagnostics carry the YAML line and column; the UI maps a position to the step or option whose range contains it. The IR stays the contract between compiler, checker and engine, and what versions are addressed by. The plan's principle 1 changes to "the YAML document is the single source of truth for the editor".

**Admin API** (`FAYE_ADMIN_TOKEN` as in M3; configured tenants only):

```
GET  /admin/catalog                           handlers (amr, binds subject), screens and their options, egress targets
GET  /admin/tenants                           tenant ids with their current version
GET  /admin/tenants/{t}/draft                 { yaml }; the current version's YAML when there is no draft
PUT  /admin/tenants/{t}/draft                 { yaml }; stored as is, unchecked
POST /admin/tenants/{t}/validate              YAML → errors, warnings, summary, paths (each with the steps and options it visits)
POST /admin/tenants/{t}/publish               YAML → versionHash; stores the YAML with the version
GET  /admin/tenants/{t}/versions              current, history
GET  /admin/tenants/{t}/versions/{hash}       { yaml, ir }; yaml empty for a version published before M5
POST /admin/tenants/{t}/current               { versionHash } rolls back
POST /admin/tenants/{t}/hooks/test            { source, capabilities, timeout_ms, ctx, pick } → result, accepted, logs
GET  /admin/tenants/{t}/settings              settings, and which come from config
PUT  /admin/tenants/{t}/settings              refused if the current version fails the checker under them
```

The engine, the publisher and the hook runner read tenant policy through a lookup (`TenantPolicies`) instead of a map fixed at startup, so a saved setting applies to the next flow that starts; a flow in progress keeps the settings it started with, as in M3.

**Admin sign in (2B; superseded by [ADR 0018](0018-two-tenants-and-an-open-local-console.md) 4A, which removed the sign in).** `web/apps/admin` (`:3002`) is an OIDC client of Hydra (`faye-admin`, tenant `admin`, first party). The admin tenant's flow is password plus TOTP and the tenant requires MFA; the app also sends `acr_values=faye:2fa` and accepts only an ID token with `acr` `faye:2fa` whose `sub` is in `FAYE_ADMIN_SUBJECTS`. It verifies the ID token's RS256 signature against Hydra's JWKS, keeps an HMAC signed session cookie (`ADMIN_SESSION_SECRET`), and proxies `/api/admin/*` to the server with the bearer token. State changing calls must carry `x-faye-admin: 1`, a header a cross site form cannot send and a cross site fetch cannot send without a CORS preflight the app never answers.

**Tenant settings (3B).** `tenant:{id}:settings` (now the `settings` column of `faye_tenant`, [ADR 0017](0017-one-sqlite-database.md)) holds JSON over the `faye.tenants.<id>` defaults: `mfaRequired`, `handlers`, `screens` (null for all), and `branding` (`name`; `logoUrl`, https only; `primaryColor`, `#rrggbb`; `radius`, 0 to 24 px). Egress grants decide which network services tenant code reaches, so they stay in operator config and are read only in the UI. A save runs the checker on the tenant's current version under the new settings and is refused if it fails; the configured flow at startup and every current version are checked under the effective settings.

**Editor (4B).** CodeMirror 6 bundles as plain ES modules under Next.js; Monaco needs web workers and global CSS from `node_modules`, or a CDN loader at run time. The hook editor offers completions for `ctx` and `cap` from a hand written description of the hook contract rather than full TypeScript checking. The graph is a read only tree, so a layout is a few lines; React Flow would also need a layout library. Native drag and drop covers palette to tree and moves within the tree; every drag has a keyboard alternative (arrows select, ⌘ arrows move, ⌫ deletes; the palette adds on click). Icons are `lucide-react` (pinned 1.57.0), the icon set shadcn/ui uses, so blocks, tabs and actions share one visual language; it tree shakes to the icons used. The visual design (one accent, rails rather than nested cards, property panel inspector, status bar) follows a researched spec drawn from the Vercel Geist guidelines and Linear's redesign notes.

**Hooks (5B).** `hooks.<id>.source` holds the JavaScript inline, so a hook is versioned and diffed with its flow. The test endpoint runs a definition through the same `QuickJsRuntime` and egress checks a published hook gets (the tenant's grants), and reports the raw result, whether the engine would accept it (`HookOutput`, made public for this), and the hook's log lines.

**Versions.** `version:{hash}:source` (now the `faye_version_source` table, [ADR 0017](0017-one-sqlite-database.md)) keeps the YAML of the first publish of each hash. The Versions tab diffs YAML (a line diff in the browser), falling back to the canonical IR for versions without source.

**Branding (6B).** `ScreenResponse` gains an optional `branding` (schema first, then Java, then generated TS); the screen API adds it from the flow's tenant. The Login UI shows the name and logo, and maps the color and radius onto the theme tokens.

### Consequences

* Good, because there is one compiler and one checker, and every diagnostic points at a YAML line the author can see in the YAML tab and at the block it came from.
* Good, because the bearer token never reaches a browser. (The admin sign in also exercised Faye's own MFA flow until [ADR 0018](0018-two-tenants-and-an-open-local-console.md) removed it; the console is now reachable only on localhost.)
* Good, because the dependencies added are `yaml`, the CodeMirror packages and `lucide-react`; nothing needs a worker, a CDN or a native binary.
* Bad, because block edits rewrite the YAML text: comments inside the flow are lost on the first block edit (the leading comment is kept). Edits in the YAML tab keep them.
* Bad, because the admin app is one more place that holds the admin token, and every operator who can reach it can edit every tenant. Per tenant admin roles are later work.
* Bad, because settings saved at run time can drift from `application.yaml`; the UI marks which values come from config and which are saved.
* Bad, because the hook editor does not type check hook code. Mistakes show in the test run.
* Neutral: drafts are unchecked and unversioned; one draft per tenant, last write wins.
* Exit run, 2026-10-31: agents that had not seen the flows or the UI code drove the Admin UI through headless Chrome (clicks and typing only; no YAML tab edits, no admin API writes). A first run rebuilt passkey-totp and risk-totp and reported usability problems, which were fixed: adding a block after a choose or into a list (select the list label), selection no longer toggles, findings no longer shift the inspector, Hook blocks start on a declared hook, `vars.*` and known values are suggested, condition paths show in full, the version field is visible, the flow description is editable, and block edits undo with ⌘Z. A second run cleared each tenant's flow in the UI and rebuilt all four (password-only, partner-consent, passkey-totp, and risk-totp with its hook in the `risk` tenant, which grants `ip-reputation`), then published each: every published version hash equalled the shipped flow's (`23638712…`, `cca48b87…`, `a7e04843…`, `6a4cb91c…`) on the first publish. A run by a person is still worth doing.
* Neutral: a hook that calls an egress target it did not declare passes the checker (the checker cannot see into JavaScript) and fails at run time; the hook test run shows it.

### Confirmation

* `cd server && ./gradlew build`: `MilestoneFiveTest` (drafts, versions with source, catalog, settings refused when the current version would fail, branding on screens, hook test runs for good and malicious hooks).
* `cd web && pnpm test`: `apps/admin` builds the password only, passkey plus TOTP, partner consent and risk flows from palette blocks and inspector edits alone, and each serializes to a document equal to the shipped YAML.
* `deploy/smoke.sh` step 10 against the compose stack: the Admin UI answers without sign in ([ADR 0018](0018-two-tenants-and-an-open-local-console.md)), refuses a write without `x-faye-admin`, then saves a draft, validates, publishes, rolls back and brands a tenant through its proxy, and the next screen carries the branding.

Run on 2026-10-31, after [ADR 0017](0017-one-sqlite-database.md) and [ADR 0018](0018-two-tenants-and-an-open-local-console.md): `./gradlew build` passes (`MilestoneFiveTest` 8 tests); `pnpm test` passes (12 tests, the four rebuilt flows among them); `deploy/smoke.sh` passes all 13 steps on a fresh compose stack, step 10 included.

## More Information

**Changes since this decision.**

* Admin sign in (2B) is gone: [ADR 0018](0018-two-tenants-and-an-open-local-console.md) removed the `admin` tenant, its flow, the `faye-admin` client, `FAYE_ADMIN_SUBJECTS` and `ADMIN_SESSION_SECRET`. The console listens on 127.0.0.1 only; the proxy still keeps the token server side and still requires `x-faye-admin: 1` on writes.
* Drafts, settings, scenarios and version sources moved from Redis to SQLite (`faye_tenant`, `faye_version_source`; [ADR 0017](0017-one-sqlite-database.md)). The API and behaviour are unchanged.
* The palette column and dragging from it were replaced by a searchable "Add block" command list, and the tabs by a sidebar console with a flow workspace ([ADR 0013](0013-admin-console-shell-and-flow-workspace.md)). Moving blocks by drag and drop and by keyboard is unchanged.
* The admin API gained read only `GET /admin/tenants/{t}/clients` and `GET /admin/tenants/{t}/users` ([ADR 0015](0015-oauth2-client-management.md), [ADR 0018](0018-two-tenants-and-an-open-local-console.md)) and the preview endpoints ([ADR 0011](0011-m6-preview-loop.md)).
* The configured tenants are now `acme` and `bank` ([ADR 0018](0018-two-tenants-and-an-open-local-console.md)). The exit run above used the five earlier tenants (among them `risk`); the four flow files it rebuilt still ship in `server/faye-app/src/main/resources/flows/` and the web test still rebuilds them.
