# Admin UI

The Admin UI lets someone who is not an engineer compose a login flow from blocks, see immediately whether it is safe, publish it, and try it.

## Principles

1. **The YAML document is the single source of truth.** The block editor, YAML view and graph view are three projections of the same document; the server compiles it to IR with the one compiler ([ADR 0010](../adr/0010-m5-admin-ui.md)). Edits in any editable view rewrite it; the others rerender.
2. **A tree editor, not a free canvas.** The language is a tree (`seq`, `choose`, `when`). A node and edge canvas invites cycles and edges that cannot map back to the tree. The primary editor is nested, drag and drop blocks, similar in spirit to Keycloak's flow editor.
3. **Validation is continuous.** Every edit triggers a debounced server side `/validate`; the safety checks exist only in Java and are never reimplemented in the browser.
4. **Publishing is deliberate.** Draft, validate, diff, publish. A published version is immutable.

## Layout

```
┌──────────────┬───────────────────────────────────┬──────────────────────┐
│ Block        │  Flow: default-login   [Draft]     │ Inspector            │
│ palette      │                                   │                      │
│              │  ▸ login                           │ step: credential     │
│ Steps        │    ├ identify   identifier screen │ form: choose         │
│  Identifier  │    ├ risk       hook risk.js      │ options:             │
│  Password    │    ├ credential choose            │  passkey  if …       │
│  Passkey     │    │   ├ passkey                  │  password retry 3    │
│  TOTP        │    │   └ password                 │                      │
│  Consent     │    └ mfa        when …            │ Condition builder    │
│ Logic        │  ▸ consent                         │ [any ▾] of           │
│  Choose      │                                   │  vars.risk = high    │
│  When        │  ───────────────────────────────  │  acr_values has mfa  │
│  Retry       │  ✓ 7 success paths  min amr: pwd  │                      │
│  Hook        │  ⚠ 1 warning                       │ [Test in preview]    │
└──────────────┴───────────────────────────────────┴──────────────────────┘
          Tabs:  Blocks │ YAML │ Graph │ Paths │ Versions
```

## Features

1. **Block palette**: high level blocks ("Password", "Passkey", "TOTP") expand to IR fragments (`screen` + `call` + `retry`) with sensible defaults. Logic blocks expose `choose`, `when`, `retry`, `hook`.
2. **Tree editor** (native drag and drop, with keyboard moves): drag to reorder or nest, with drop targets only where the grammar allows. Each block shows its step id, a one line summary and validation badges.
3. **Inspector**: forms per block type; a condition builder for predicates (namespace aware path picker, operator dropdown, typed value); hook picker with capability summary.
4. **YAML tab** (CodeMirror 6, Geist Mono): editable; parse errors and check errors shown inline via source maps. Round trips to the same IR.
5. **Graph tab** (SVG drawn by the app, read only): visual overview of branching, highlighting the path currently selected in the Paths tab.
6. **Paths tab**: enumerated success and failure paths with the `amr` / `acr` each yields. Selecting a path highlights it in the tree and graph.
7. **Hooks**: list, CodeMirror editor with completions for `ctx` and `cap`, server side test run, capability grants.
8. **Versions**: history, side by side IR diff, publish, roll back (moves the current pointer to an older immutable version).
9. **Tenant settings**: enabled handlers and screens, MFA policy, branding. (`acr` mapping is later work; see ADR 0010.)

## Admin API (server)

As built in M5 ([ADR 0010](../adr/0010-m5-admin-ui.md)); one flow per tenant, every call with `Authorization: Bearer <FAYE_ADMIN_TOKEN>`:

```
GET  /admin/catalog                           handlers, screens and their options, egress targets
GET  /admin/tenants                           tenant ids with their current version
GET  /admin/tenants/{t}/draft                 { yaml, origin, files }
PUT  /admin/tenants/{t}/draft                 { yaml }
POST /admin/tenants/{t}/validate              YAML → errors, warnings, summary, paths
POST /admin/tenants/{t}/publish               YAML → versionHash
GET  /admin/tenants/{t}/versions              current, history
GET  /admin/tenants/{t}/versions/{hash}       { yaml, ir, current }
POST /admin/tenants/{t}/current               { versionHash }
POST /admin/tenants/{t}/hooks/test            { source, http, timeoutMs, ctx, options } → result, accepted, effect, logs
GET  /admin/tenants/{t}/settings              settings, configured, saved, egress
PUT  /admin/tenants/{t}/settings              { mfaRequired, handlers, screens, branding }
```

Admin authentication: the Admin UI signs operators in through Hydra with the fixed `admin` tenant flow (password plus TOTP; dogfooding), accepts only `acr` `faye:2fa` from subjects in `FAYE_ADMIN_SUBJECTS`, and calls the API from its own server with the token, which never reaches the browser.
