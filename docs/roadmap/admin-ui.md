# Admin UI

The Admin UI lets someone who is not an engineer compose a login flow from blocks, see immediately whether it is safe, publish it, and try it.

## Principles

1. **The YAML document is the single source of truth.** The block editor, YAML view and graph view are three projections of the same document; the server compiles it to IR with the one compiler ([ADR 0010](../adr/0010-m5-admin-ui.md)). Edits in any editable view rewrite it; the others rerender.
2. **A tree editor, not a free canvas.** The language is a tree (`seq`, `choose`, `when`). A node and edge canvas invites cycles and edges that cannot map back to the tree. The primary editor is nested, drag and drop blocks, similar in spirit to Keycloak's flow editor.
3. **Validation is continuous.** Every edit triggers a debounced server side `/validate`; the safety checks exist only in Java and are never reimplemented in the browser.
4. **Publishing is deliberate.** Draft, validate, diff, publish. A published version is immutable.

## Layout

A console with a collapsible sidebar (⌘B), a command palette (⌘K: pages, tenants, console commands) and a shortcut sheet shown while ⌘ is held; dense panels carry a (?) whose tooltip explains them. Each capability is a route under `/tenants/{t}/` ([ADR 0013](../adr/0013-admin-console-shell-and-flow-workspace.md)).

```
┌────────────┬──────────────────────────────────────────────────────────────────────┐
│ f Faye     │ ☰ │ risk › Login flow           ● Draft saved  live 6a4cb91c  ▷ Preview  Publish │
│   risk   ⇅ ├──────────────────────┬──────────────────────┬────────────────────────┤
│            │ [Blocks|YAML]  + Add │ Graph  live          │ Block │ Paths │ Preview │
│ Build      │                      │                      │                        │
│ ▸ Login flow│ ▸ identify          │  [identify]          │ second-factor          │
│   Hooks    │   risk   hook risk   │  [risk]              │ Condition              │
│   Versions │   credential         │  [credential]        │  all of vars.risk = …  │
│            │   second-factor      │  [second-factor]─┬─[totp]                     │
│ Settings   │     ├ totp           │                  └─[enroll]                   │
│   General  │     └ enroll         │  (signed in)         │ Interaction · Logic    │
│   Branding │                      │              − 91% + ⤢                        │
│            ├──────────────────────┴──────────────────────┴────────────────────────┤
│            │ 0 errors · 0 warnings   33 paths · 21 succeed · 12 fail                │
└────────────┴──────────────────────────────────────────────────────────────────────┘
```

The flow workspace keeps the blocks (or the YAML) and the graph of the same document side by side: every edit redraws the graph, flashes the blocks it changed, and is checked by the server. Selecting a block in either selects it in both. Hooks, Versions, General and Login page (branding and the login screens' options, [ADR 0033](../adr/0033-login-page-customization-and-keep-me-signed-in.md)) are their own sections, and share the tenant's draft with the flow workspace.

## Features

1. **Block palette** (the "Add block" command list, `/`): the built in blocks ("Identifier", "Password", "Passkey", "TOTP", ...), each a step whose screen and handler are fixed ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)), and the logic blocks Choose, Group, Set and Fail. A block that cannot go where it would be added is shown disabled with why ("Start with the Identifier", "An option is one credential block, or Fail").
2. **Tree editor** (native drag and drop, with keyboard moves): drag to reorder or nest; a drop or move that would put a block where the compiler refuses it (the Identifier anywhere but first, Consent inside a group, a Group as an option) does nothing. Each block shows its step id, a one line summary and validation badges.
3. **Inspector**: only the fields of the selected kind of block, never a screen or handler. The Identifier: its view (identifier first, or with the password on the same screen, which offers to remove a Password step that would ask again), passkey autofill, attempts and path. A credential block: attempts (its default unless changed) and path. A choice: who picks. Set: vars. Fail: the reason. General settings hold the tenant's block defaults (attempts per block, passkey autofill), which the inspector shows as "tenant default". Every block: a condition (not the Identifier, which always runs) and the hooks that run before and after it. Keys hand written YAML gave a block that it does not take are listed with why and a Remove. Removing one of a choice's two options makes the other the step. YAML from before blocks opens as blocks under a banner whose "Upgrade to blocks" rewrites it, as one undoable edit; until then the server checks it as written.
4. **YAML view** (CodeMirror 6, system monospace; a switch in the editor panel): editable; parse errors and check errors shown inline via source maps. Round trips to the same IR.
5. **Graph** (SVG drawn by the app, read only, beside the blocks): branching, the selected block, the highlighted path or preview run, and the blocks the last edit changed; zoom and fit.
6. **Paths** (side panel): enumerated success and failure paths with the `amr` / `acr` each yields. Selecting a path highlights it in the tree and graph.
7. **Hooks** (own section): list, CodeMirror editor with completions for `ctx` and `cap`, server side test run, capability grants.
8. **Versions**: history, side by side IR diff, publish, roll back (moves the current pointer to an older immutable version).
9. **Tenant settings**: enabled handlers and screens, MFA policy, branding. (`acr` mapping is later work; see ADR 0010.)
10. **Applications and Users** (read only): the tenant's OAuth clients from the authorization server, and the accounts in the user directory with the credentials each has enrolled. Both are managed outside Faye ([ADR 0015](../adr/0015-oauth2-client-management.md), [ADR 0014](../adr/0014-tenant-user-directory.md), [ADR 0018](../adr/0018-two-tenants-and-an-open-local-console.md)). The console never creates or edits either; that would cross the charter.

## Admin API (server)

As built in M5 ([ADR 0010](../adr/0010-m5-admin-ui.md)); one flow per tenant, every call with `Authorization: Bearer <FAYE_ADMIN_TOKEN>`, over HTTP on the Unix domain socket `FAYE_ADMIN_SOCKET` and never on the TCP port ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)):

```
GET  /admin/catalog                           handlers, screens and their options, egress targets
GET  /admin/tenants                           tenant ids with their current version
GET  /admin/tenants/{t}/draft                 { yaml, origin, files }
PUT  /admin/tenants/{t}/draft                 { yaml }
POST /admin/tenants/{t}/validate              YAML → errors, warnings, summary, paths
POST /admin/tenants/{t}/publish               YAML → versionHash
GET  /admin/tenants/{t}/versions              current, history, published (time of each publish, by hash)
GET  /admin/tenants/{t}/versions/{hash}       { yaml, ir, current }
POST /admin/tenants/{t}/current               { versionHash }
POST /admin/tenants/{t}/hooks/test            { source, http, timeoutMs, ctx, options } → result, accepted, effect, logs
GET  /admin/tenants/{t}/settings              settings, configured, saved, egress
PUT  /admin/tenants/{t}/settings              { mfaRequired, handlers, screens, branding }
GET  /admin/tenants/{t}/clients               the tenant's OAuth clients, read only, no secrets
GET  /admin/users                             accounts and which credentials they have, read only
```

Admin authentication: none, for local use only ([ADR 0018](../adr/0018-two-tenants-and-an-open-local-console.md)); the Admin UI listens on localhost and calls the API from its own server, through the socket, with the token, which never reaches the browser.
