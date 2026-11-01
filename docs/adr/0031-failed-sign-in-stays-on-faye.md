---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# A failed sign in stays on Faye

## Context and Problem Statement

When a sign in fails (the password attempts are used up, a hook fails the step), the engine tells the host and returns the host's completion. For Hydra that is a redirect: Hydra rejects the login request and sends the browser on to the application's redirect URI with `error=access_denied`. The Login UI cannot tell this redirect from the one after a successful sign in, so it follows it, and the person lands on the application's error page. In the demo that is the demo client (`:3001`), which can only offer to start again.

A person who failed to sign in should hear about it on the sign in screens, which Faye owns (charter, mission), and be offered a new attempt there. The redirect after a failure must still never be confused with one after a success.

## Decision Drivers

* Charter: the authorization server keeps the protocol; Faye owns every screen a person sees. Faye must still tell the host the outcome.
* Rule 1 of AGENTS.md: the screen protocol changes in `schema/` first.
* The browser must learn nothing it did not already learn: the failure reason already rides on Hydra's redirect, but the Login UI shows only a generic message (login-experience.md: unknown and known accounts fail alike).
* No loops: a refusal that would fail again the same way must not restart itself.

## Considered Options

1. **A. Mark the redirect of a failed sign in (`failed: true` on the redirect response); the Login UI shows `/error?reason=failed` instead of following it, and moves on to `/login` after 5 seconds when `/login` can start a sign in.**
2. **B. Show `/error` for 5 seconds, then follow the redirect to the application.**
3. **C. Change only the demo client: its error page sends the person back to Faye's `/login` after 5 seconds.**

## Decision Outcome

Chosen option: **A**.

* **Engine.** `Redirect` gains `failed`. Hosts build redirects as before (`new Redirect(url)`, not failed); the engine marks the host's redirect failed when a login entry that ran fails. A refused consent is not marked: declining an application goes back to it, as OAuth expects. A request refused before any flow runs (`flow_unavailable`, `preview_unavailable`) is not marked either, since starting again would fail the same way.
* **Screen protocol.** `RedirectResponse.failed` is optional and only ever `true`. `redirectTo` stays required: the host was told, and a client that ignores `failed` behaves as before.
* **App.** `GET /login` that fails before its first screen redirects to the Login UI's `/error?reason=failed`; a submit that fails answers with `failed: true`.
* **Login UI.** On `failed` it goes to `/error?reason=failed`, which says "We couldn't sign you in." With `DEMO_SIGN_IN_URL` set (the only case where `/login` without a flow starts a sign in), every `/error` page counts down 5 seconds and goes to `/login`, with a "Start now" button. Without it `/error` stays, because `/login` would only show `/error` again.

### Consequences

* Good: the person stays on the sign in screens and gets a clear way to try again.
* Good: the browser learns no reason; the message is the same for every failure.
* Bad: the application never receives `access_denied` for a failed sign in. Its authorization request simply never completes, and Hydra's login request is already rejected. An application that counts failures from the callback loses that signal; Faye's trace and logs keep it.
* Bad: a person who wants to give up has no link back to the application from `/error` when the demo is off. Unverified whether this matters for real deployments; revisit with a per-client "back to the app" URL if it does.

### Confirmation

* `./gradlew build`: `ConformanceTest` checks on every vector that a login that ran and failed has its redirect marked failed, and that successes, consent outcomes and refusals before a run are not; `FlowApiTest.unknownUserGetsThePasswordScreenAndTheSameFailure` checks `failed: true` on the wire, and `FlowApiFuzzTest` checks every redirect response against the schema.
* `pnpm typecheck && pnpm lint && pnpm build` in `web/`, and the generated contract types match the schema.

* `deploy/smoke.sh` passes against a fresh stack of this change (it does not exercise a failed sign in in the browser).

Open gaps: no automated browser test of the Login UI's `/error` countdown; it was checked by hand with a headless browser (three wrong passwords, `/error?reason=failed`, then a new sign in at `/login`).

## Pros and Cons of the Options

### A. Mark the failed redirect, stay on Faye

* Good: Faye owns the failure screen; works for any host that redirects.
* Bad: changes the screen protocol and the engine's `Redirect`.

### B. Interstitial, then the application

* Good: the application still learns the outcome.
* Bad: the person still ends on the application's error page, which is what this record sets out to avoid.

### C. Demo client only

* Good: no protocol change.
* Bad: the error is still shown by the application, not by Faye, and only the demo gets the behaviour.
