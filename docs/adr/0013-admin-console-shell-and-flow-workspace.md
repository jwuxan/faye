---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Admin console: sidebar shell, flow workspace and hooks section

## Context and Problem Statement

The M5 Admin UI ([ADR 0010](0010-m5-admin-ui.md)) is one screen per tenant with seven top level tabs: Blocks, YAML, Graph, Paths, Hooks, Versions and Settings. Flow editing, hooks, version history and tenant settings all sit in the same tab strip, so the console has nowhere to grow: every new capability (users, clients, operator settings; ADRs 0014 to 0016) would be another tab next to "YAML".

Inside the flow editor, the graph is a separate tab. An author who changes a block cannot see what the change did to the shape of the flow without switching tabs, and the YAML text, which ADR 0010 made the single source of truth, sits at the same level as the views built on it.

Most of the console's controls (switch, segmented control, select, dropdown menus, toasts, tooltips) are written by hand in `app/ui.tsx` and in each view, although D12 chose shadcn/ui so the three web apps share owned components.

The request this answers: a full console with a toggleable sidebar in which flow editing is one capability among several; the graph on the same screen as the blocks, updating with each edit; YAML as a view inside the flow editor rather than a top level tab; hooks as their own section where JavaScript is written directly in the console; and shadcn components instead of hand rolled ones.

## Decision Drivers

* Charter, "What Faye is not" 3: the author tooling exists to build, check and try flows. Everything in this decision does exactly that; capabilities that go further are separate decisions (ADRs 0014 to 0016).
* Charter principle 5: errors point at what the author wrote, and a flow can be tried before it is published.
* ADR 0010 principle: the YAML document is the single source of truth; block, YAML and graph views are projections of it. Safety checks run only in Java (D10).
* D12: shadcn/ui, Geist and Geist Mono, owned components shared across the web apps.
* Small: no new server API, no new rule in the browser, dependencies only where a shadcn component needs them.

## Considered Options

1. Navigation
   * A. Keep the tab strip and add tabs.
   * B. A collapsible left sidebar (shadcn `Sidebar`, `collapsible="icon"`, ⌘B or the header trigger, state kept in a cookie) with a tenant switcher in its header and nav groups per capability. Each capability is a route under `/tenants/{t}/`.
   * C. A top navigation bar with menus.
2. Flow editor layout
   * A. As is: Blocks and Graph as separate tabs.
   * B. One workspace in resizable panels: the editor (Blocks or YAML), the live graph, and a side panel (Block inspector, Paths, Preview). The graph rerenders from the same parsed document on every edit, highlights the selected block, and flashes the blocks an edit changed.
   * C. Replace the block tree with a node canvas that is both editor and graph.
3. Where YAML lives
   * A. A top level tab (as is).
   * B. A view switch inside the editor panel, Blocks or YAML, sharing the graph and side panel.
4. Hooks
   * A. A tab inside the flow editor (as is).
   * B. A section of its own in the sidebar, with the hook list, a CodeMirror JavaScript editor with `ctx` / `cap` completions, capabilities, and server side test runs. Hooks stay stored inline in the flow document (`hooks.<id>.source`, ADR 0010 5B), so they are still versioned and diffed with the flow they serve.
   * C. Hooks as separate stored resources, referenced from flows (Auth0 Actions style).
5. Draft state across sections
   * A. Each route loads and saves the draft itself.
   * B. A client provider in the tenant layout holds the draft, its validation, save state and undo history; the flow and hooks sections edit the same document, and moving between sections keeps unsaved state and undo.
6. Components
   * A. Keep the hand written controls.
   * B. shadcn components added to `@faye/ui` with the shadcn CLI (pinned `shadcn@4.21.5`): sidebar, breadcrumb, dropdown menu, tabs, resizable, select, switch, toggle group, badge, tooltip, popover, command, dialog, alert dialog, table, field, input group, empty, kbd, checkbox, slider, sonner, and what they depend on (sheet, skeleton, separator). The console keeps only layout helpers of its own.

## Decision Outcome

1B, 2B, 3B, 4B, 5B, 6B.

1C was rejected for the same reason as D11: the language is a tree, and a canvas admits shapes that cannot map back to it. 4C would change what a version hash covers (a flow could change behaviour without a new flow version) and needs its own decision; it is not part of this one.

**Routes.**

```
/                                   the tenant list ("Tenants"); no sign in (ADR 0018)
/tenants/{t}                        redirects to flow
/tenants/{t}/flow                   flow workspace
/tenants/{t}/hooks                  hooks
/tenants/{t}/versions               versions: diff against the draft, roll back, load into draft
/tenants/{t}/settings               general: MFA policy, handler and screen allowlists, egress (read only)
/tenants/{t}/settings/branding      branding of the login screens, with a live preview
/tenants/{t}/applications           the tenant's OAuth clients, read only, each with Try sign in (ADRs 0015, 0018)
/tenants/{t}/users                  the tenant's user pool, read only (ADR 0018)
```

Sidebar groups: **Build** (Login flow, Hooks, Versions), **Directory** (Applications and Users, read only; ADRs 0015 and 0018) and **Settings** (General, Branding). The header is the Faye mark with a tenant switcher. There is no footer: the operator menu (subject, sign out) was dropped when [ADR 0018](0018-two-tenants-and-an-open-local-console.md) removed the console's sign in. Operator password changes stay out of Faye ([ADR 0016](0016-operator-account-and-console-preferences.md) 1C).

**Flow workspace.** Three resizable panels, sizes remembered per browser: editor | graph | side panel, then a status bar (errors and warnings, which open a problems list; path counts; the checker's summary). The editor panel switches between the block tree and the YAML text; "Add block" opens a searchable command list of palette blocks, inserted after the selected block or at the end of the selected list. Blocks are reordered by drag and drop or ⌘↑ / ⌘↓ as before. Clicking a node in the graph selects the block in the tree and scrolls it into view, and the reverse. Paths and the preview trace highlight in both. The side panel's Block tab is the inspector, or the flow's own properties (id, version, description) when nothing is selected.

**What is dropped.** Dragging blocks from a palette column into the tree: the palette moves into the command list, which adds on click or Enter, as the M5 exit runs already did.

### Consequences

* Good, because the flow's shape is visible while editing it, from either the blocks or the YAML.
* Good, because new capabilities get a sidebar entry and a route rather than another tab.
* Good, because the console uses the same owned components as any other shadcn app, with keyboard and screen reader behaviour from Radix rather than our own.
* Bad, because `@faye/ui` gains dependencies: `radix-ui` 1.8.1, `cmdk` 1.1.1, `react-resizable-panels` 4.15.2 and `sonner` 2.0.8 (all exact pins). `@radix-ui/react-slot` is replaced by `radix-ui`. shadcn's `sonner` wrapper is changed to follow the OS theme instead of `next-themes`, which is not added.
* Bad, because three panels plus the sidebar are tight below about 1280 px wide. The sidebar collapses to icons, and every panel can be resized.
* Neutral: no server change. Every call goes through the existing admin API and proxy.

### Confirmation

* `cd web && pnpm typecheck && pnpm lint && pnpm test && pnpm build`.
* `deploy/smoke.sh` step 10: `/` answers without sign in with the "Tenants" page listing `acme` and `bank`, and `/signin` is 404; the proxy checks are unchanged.
* By hand against the compose stack: change a block and see the graph change in the same view; switch to YAML, edit, and see the graph and blocks follow; collapse and reopen the sidebar with ⌘B; write a hook in the Hooks section, test run it, and find it in the flow's draft.

Run on 2026-10-31: the `pnpm` checks above pass (12 tests). Headless Chrome against `bootRun` with the mock host (light and dark, 1440 and 1280 px wide) showed every route. Adding a block with `/` updated the tree, the graph, the inspector and the path counts together. Selecting a block in the tree highlighted it in the graph; a path selected in Paths lit up in both. ⌘B collapsed the sidebar to icons. A test run of the `risk` hook in the Hooks section returned `set vars.risk = high` with its log line, since no ip-reputation target was configured. `/` still contains `>Tenants<`, which `smoke.sh` step 10 greps for.

Run again on 2026-10-31, after [ADR 0018](0018-two-tenants-and-an-open-local-console.md): the `pnpm` checks pass (12 tests), and `deploy/smoke.sh` passes all 13 steps on a fresh compose stack, step 10 included. Not yet done: a review by a person.
