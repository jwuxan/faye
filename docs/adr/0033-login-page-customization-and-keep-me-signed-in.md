---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Login page customization, and "keep me signed in"

## Context and Problem Statement

The Login UI was redesigned (a white page, one column, tall fields, one strong action; light only). The owner asked for a dedicated login customization page in the Admin UI that holds the tenant's branding and adds three choices: a button that shows a typed password, a "Remember me" checkbox, and a sign up button (a placeholder: sign up is not supported yet). Every login screen also gets a footer, "© {name}. All rights reserved.", and the identifier screen drops its "Sign in" heading.

"Remember me" was chosen to mean what a large consumer login means by it (Coupang's 로그인 상태 유지): keep me signed in on this browser, so the next sign in from it is skipped. That is real behavior, not appearance. Faye does not own the authorization server's session (charter, non goal 2): it can only report that the person asked, and the host decides what that means.

## Decision Drivers

* Charter, non goal 2: Faye reports the outcome of a flow; the authorization server turns it into sessions. Principle 1: reusing a session is allowed only when it satisfies the assurance the client asked for.
* Charter guarantee 3: no flow or hook can produce a sign in without a verified credential. Hooks may only `set`, `fail` or `pick` (AGENTS.md rule 4).
* AGENTS.md rule 1: the screen protocol changes in `schema/` first; rule 2: engine behavior needs a conformance vector and a fuzz harness.
* PRODUCT.md: tenant branding was limited to logo, name, primary color and radius, and layout is not tenant configurable.
* A setting an admin turns on should apply without republishing the flow.

## Considered Options

1. **A. A tenant setting (`rememberMe`, read by flows as `tenant.remember_me`); the identifier screen offers `remember` when it is on; the person's boolean answer rides with the identifier answer, is recorded on the flow state, and reaches the host as `Authenticated.remember`.**
2. **B. A block setting of the identifier (`with: { remember: true }`), like passkey autofill.**
3. **C. Remember the email only: the Login UI saves the identifier in the browser and prefills it; nothing reaches the server.**
4. **D. Let a flow or hook decide (`set vars.remember`).**

## Decision Outcome

Chosen option: **A**, because it is the person's choice carried to the party that owns sessions, applies as soon as it is saved, and cannot be produced by a flow or a hook.

* **Screen protocol.** `IdentifierScreen.remember` (const `true`) is present when the tenant offers it. `IdentifierPayload`, `IdentifierPasswordPayload` and `PasskeyPayload` take an optional boolean `remember`. `Branding` gains `showPassword` and `signUp` (const `true`, absent when off); `name` is now also shown in the footer.
* **Engine.** `ScreenSpec.offersRemember(data)` (default false). On a submit, when the awaited screen offers it and the payload carries `remember`, the engine requires a boolean, records it on `FlowState.remember` and removes the field before the screen judges the answer; on a screen that does not offer it, the field makes the answer malformed (`invalid_submission`). `Authenticated` gains `remember`; the engine sets it from the flow state when a login succeeds. A remembered session that skips the flow is accepted with `remember` false (nothing asked). `TenantPolicy.rememberMe` puts `remember_me: true` in `tenant.*` only when on, so a tenant without it reads exactly as before. `FlowState.remember` is absent from older states and reads as false; the frames do not change, so the state format stays 1.
* **Hosts.** Hydra: `remember` is passed on login accept, with `remember_for` 30 days when true (a constant). The in memory host records the outcome as is.
* **App.** `TenantSettings.rememberMe`, `Branding.showPassword` and `Branding.signUp`, saved with the tenant's settings (absent in older rows: off). `faye.tenants.<id>.name` gives a tenant a display name until an admin saves branding (the demo tenants are Acme and Bank).
* **Login UI.** A "Keep me signed in" checkbox on the identifier screen when offered, always sending its value; a show/hide button on password fields with `showPassword`; with `signUp`, a "Sign up" button that opens a dialog saying sign up is not supported yet; the footer on every screen that knows the tenant's name.
* **Admin UI.** A "Login page" section (Settings) holds branding, the three choices and a live preview; Branding moves into it.

### Consequences

* Good: the person decides; no flow, hook or setting can ask the host to remember on their behalf.
* Good: no IR change: a flow reads the same; `tenant.remember_me` is an extra key a flow may read.
* Good: the remembered sign in is reused under the existing rule (one factor, only if it meets the client's `acr_values` and the tenant's policy).
* Bad: the sign up button is a placeholder until sign up flows exist; it says so rather than doing nothing.
* Bad: 30 days is fixed in the Hydra host. Unverified whether tenants need it configurable; add a setting only when one asks.
* Bad: PRODUCT.md's "branding is limited to logo, name, primary color and radius" no longer holds; it is updated with this record.

### Confirmation

* `./gradlew build`: the vectors `remember-me`, `remember-me-declined`, `remember-me-refused` and `remember-me-malformed` (`ConformanceTest` checks `expect_remember` and `remember` in `expect_result`); `EngineFuzzTest` answers identifier screens with remember absent, true or false and checks that the host is asked to remember only when the browser asked, never for a remembered session; `HydraFlowHostTest.acceptsLoginThePersonAskedToRemember`; `MilestoneFiveTest.loginCustomizationReachesTheScreensAndTheHost` checks the settings reach the screen, the schema, and the host.
* `pnpm typecheck && pnpm lint && pnpm test && pnpm build` in `web/`, and the generated contract types match the schema.
* `pnpm e2e` in `web/` (after the build; `pnpm e2e:install` once): `apps/login/e2e/login-page.spec.ts` runs the Login UI's production build in Chromium against a stub of Faye's screen API (`e2e/stub-faye.ts`) that records what the browser submits. It checks that the checkbox is offered unticked only when the screen offers it, and that its value (true or false) is sent with the identifier, and with the identifier and password asked together, and never sent when not offered; that the password button shows and hides what was typed, only with `showPassword`; that the sign up button, only with `signUp`, opens the dialog saying sign up is not available yet and sends nothing; that the footer names the tenant on the first screen and the next, and is absent without a name; and that the identifier screen's heading is for screen readers only. Run in CI's web job.
* `deploy/smoke.sh` passes against a fresh stack of this change; its step 14 turns the setting on for `acme`, signs in with the box ticked, and checks that the next sign in from the same browser reaches the client with no screen (same `sub`, `acr` `faye:1fa`), and that after a sign in with it unticked the identifier screen comes back. Passed on 2026-11-01.

Checked by hand on 2026-11-01 against the compose stack (real Hydra) with a headless browser: with the box ticked, a second sign in through the demo client (after its local sign out) skipped the login screens; unticked, the login screen came back.

No open gaps: `smoke.sh` covers the server side of a remembered sign in against real Hydra, and `pnpm e2e` the Login UI's rendering and what it sends.

## Pros and Cons of the Options

### A. Tenant setting, person's answer, host told

* Good: real "keep me signed in", owned by the host; applies without republishing.
* Bad: touches the screen protocol, the engine's flow state and `Authenticated`, and the Hydra host.

### B. Identifier block setting

* Good: follows passkey autofill.
* Bad: a block default applies only when a flow is compiled, so the toggle would wait for the next publish; and it puts a session preference in the flow, which is about proof.

### C. Remember the email only

* Good: Login UI only, no protocol or engine change.
* Bad: not what the owner asked for; nothing about the session changes.

### D. A flow or hook decides

* Bad: a hook could ask the host to keep a session the person never asked for. Ruled out by the drivers.
