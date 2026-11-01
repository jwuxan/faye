---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# A URL path for every screen, set in the flow

## Context and Problem Statement

Before this record the Login UI showed every screen of a flow at one URL, `/flow/{flowId}`, and swapped screens in place. Tenants want the address bar to say where a person is: `/login` for who, then `/challenge/password` or `/challenge/mfa`, then `/consent`. That helps analytics, support ("which page were you on?") and operators who put rules in front of the Login UI by path. Each tenant should be able to choose the path of each step, but every flow should still read the same way.

The URL must never become a way to steer the flow. The engine decides which screen a flow waits on, and an answer is checked against that screen and its single use nonce (ir-spec.md, Suspension and resumption). A path that could pick the screen would let a person skip a step.

Where does the path live, and does the IR have to change?

## Decision Drivers

* Charter principle 1: the flow, not the browser, decides what happens next.
* Charter principle 6: tenants configure what happens and in what order; the Login UI keeps a fixed set of typed screens. A path is a label, not layout.
* ADR 0024: the Core IR is a narrow waist. New capability arrives through the catalogs, not as a form, and a change to what the IR means bumps its format.
* Consistency across tenants: every flow's paths start the same way.
* Several tabs can sign in at once, each bound to its own flow by the cookie `faye_flow_{flowId}` (ADR 0024).

## Considered Options

1. **A. A `path` screen option, which every screen takes; the server sends the path with each screen; the Login UI routes on it, with the flow id in the query.**
2. **B. Derive the path from the step id** (`/flow/{id}/{step}`); nothing to configure.
3. **C. An options object on the `step` form** (`[step, id, body, {path}]`).
4. **D. Let the URL choose the screen**, as a router would.

## Decision Outcome

Chosen: **A.**

```yaml
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }        # /login
    credential:
      retry: 3
      screen: password
      path: /challenge/password
      use: password.verify
    second-factor:
      alternative:                                                  # picker: /challenge/second-factor
        totp: { screen: otp, path: /challenge/mfa, use: totp.verify }
        enroll: { screen: otp_enroll, path: /challenge/mfa/enroll, use: totp.enroll }
    grant: { screen: consent, use: consent.record }                 # /consent
```

* **YAML.** `path:` on a step or option body is the option of the screen that body shows; `screen: { type: t, path: ... }` says the same. A `path` with no `screen` in its body, or a second path for the same screen, is a compile error.
* **IR.** `(screen password {path "/challenge/password"})`. The grammar already allows it: options are `id` keys with literal values. No form changes and the IR format stays 1. The path is in the IR, so it is covered by the version hash, reviewed and published like the rest of the flow.
* **Catalog.** Every screen takes `path`. Each screen spec gives its default path (`ScreenSpec.path(step)`): `/login` for `identifier`, `/consent` for `consent`, `/error` for `error`, and `/challenge/{step}` for every other screen, including the engine's method picker. A path the flow sets must start with the first segment of the screen's default and add at most three lowercase segments (`[a-z0-9][a-z0-9_-]*`). The verifier (L1) refuses anything else, so a flow that breaks the rule never publishes, and an engine without this rule refuses a flow that uses it.
* **Engine.** It works out the path when it shows the screen (`ScreenPaths.of`): the option of the screen node the flow waits on, else the default. Nothing new is stored with a waiting flow, so the state format does not change, and flows published before this record get default paths.
* **Screen protocol.** `ScreenResponse.path` is required. Its schema pattern is `^/(login|challenge|consent|error)(/[a-z0-9][a-z0-9_-]*){0,3}$`.
* **Entry.** The host's `/login` and `/consent` redirect to `{loginUiUrl}{path}?flow={flowId}` instead of `{loginUiUrl}/flow/{flowId}`.
* **Login UI.** It has four routes, `/login`, `/challenge`, `/consent` and `/error`, each with any path under it, and every one renders the screen the flow waits on. After each screen it replaces the URL with that screen's path, so the address bar follows the flow and never leads it. It replaces rather than pushes: an answered screen's nonce is spent, so Back has nothing to return to. A flow that cannot be shown (no or a bad `?flow=`, unknown, expired or finished) is shown at `/error`.

### Consequences

* Good: the address bar names the step, with paths a tenant chooses and every tenant's paths shaped alike.
* Good: no IR form, no IR format bump, no state format change; old versions run unchanged with default paths.
* Good: a wrong path is an error at publish time that points at the screen.
* Neutral: the path says which screen is shown; so did the screen type and `step` already, so it reveals nothing new (account enumeration included).
* Bad: the Login UI URL `/flow/{flowId}` is gone; a bookmarked or in flight link to it is a 404. Flows live minutes, so no redirect is kept.
* Bad: the method picker has no node in the IR for an author to set its path, so its path is its step's default, `/challenge/{step}`. Naming the step names the path. Setting it directly would need a `path` on the `choose` options object, a grammar change left for later.
* Bad: behind one origin, the server's `/login` and `/consent` entry points and the Login UI's routes of the same names collide; route them by host or port, as compose does.

### Confirmation

* `conformance/vectors/screen-paths*.yaml` (`expect_path`): default and set paths, a retry keeping its path, a nested path, the picker's default, consent.
* `CheckerTest.screenPathsKeepTheirFirstSegment`, `FlowCompilerTest.pathIsTheScreensOption`, `FlowCompilerTest.pathNeedsAScreenAndOnlyOne`.
* `FlowApiTest.signsInThroughTheScreenProtocol`: the redirect is `/login?flow=f_...`, and each response carries its path.
* `EngineFuzzTest` gives screens valid and invalid paths and checks every screen response against the schema, `path` included.
* `web/apps/admin/lib/flow.test.ts`: the editor keeps a step's `path`.
* `deploy/smoke.sh` follows the new redirect.

Open gaps: the Login UI routing (replace on each screen, `/error` on failure) has no automated browser test, only `pnpm build` and the smoke run's API calls; `conformance/generated/` does not record paths, only the hand written vectors do.

## Pros and Cons of the Options

### A. A `path` screen option, routed by the Login UI

* Good: configurable per step, checked at publish, carried in the IR without changing the grammar.
* Good: the path rides with the screen it labels, so a step with two alternatives gives each its own path.
* Bad: a new required field in the screen protocol, and a new Login UI URL.

### B. The path from the step id

* Good: nothing to configure or check.
* Bad: one segment, chosen by the step id; no `/challenge/password` unless the step is called that, and no `/login` at all.

### C. An options object on `step`

* Good: reads as "the step's path".
* Bad: changes a form's grammar, against ADR 0024, and still needs a rule for a step whose alternatives show different screens.

### D. The URL chooses the screen

* Bad: the browser would steer the flow, so a person could skip a step. Ruled out by charter principle 1.
