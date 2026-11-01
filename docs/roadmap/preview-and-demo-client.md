# Preview and demo client

The fastest way to know a flow works is to sign in with it. Every edit in the Admin UI should be one click away from a real OAuth authorization code flow against Hydra.

## Demo client

`web/apps/demo-client` is a minimal OIDC relying party, registered in Hydra by the bootstrap job.

1. Authorization code with PKCE, `openid profile email offline_access`.
2. A launch panel to vary the request: `prompt` (`login`, `consent`, none), `acr_values`, `login_hint`, `max_age`, `ui_locales`. This is how step up and SSO skip are exercised.
3. After callback, a result view: decoded ID token claims (highlighting `sub`, `amr`, `acr`, `auth_time`), access token introspection, userinfo response, and the Faye trace for that `flowId`.
4. Sign out via Hydra's end session endpoint.
5. Same design system as the other apps (shadcn, Geist, Geist Mono for tokens).

## Preview in the Admin UI

1. **Preview pane**: the Admin UI embeds the demo client in a side panel (or opens it in a new window when browser cookie rules block the embed). It targets a dedicated preview client in Hydra.
2. **Draft previews without publishing**: the preview client carries `metadata.preview = true`. For such clients the server runs the tenant's **draft**, but only if it passes validation, pinned as an ephemeral version hash. Production clients only ever see published versions.
3. **Scenario presets**: buttons such as "New user", "User with passkey", "High risk IP", "Client requests MFA" set the launch parameters and seed test users or hook inputs (preview tenant only).
4. **Live trace**: while the preview runs, the tree editor highlights the current step and the path taken, fed by a server sent events stream of engine events for the preview `flowId`.
5. **Expectation check**: the result view compares the issued `amr` / `acr` with what the Paths tab predicted for the path taken, and flags a mismatch.

As built ([ADR 0011](../adr/0011-m6-preview-loop.md)): one preview client per tenant (`preview-<tenant>`); the preview opens in a popup window rather than an embed; the scenario is an account tab (no account, New user or User with TOTP: preview only accounts, which no production client can sign in with, passed as `login_hint`) and two switches, High risk IP (a `request.ip` override only preview flows see) and Client requests MFA; the Preview panel has no raw `login_hint` input. A passkey preset is not offered, since a passkey needs the operator's authenticator; register one during a preview instead. Previews are off unless the server runs with `FAYE_PREVIEW_ENABLED=true`. The demo client's launch panel has no `login_hint` input either (the route still passes one through), and a link that names a client (`/?client=<id>`) goes straight to `/login`, so the browser lands on the login app's `/login?flow=…`. Once signed in, the demo client shows no launch panel: only the identity (`sub`, `name`, `email`, `amr`, `acr`, `auth_time`), the ID token (raw and decoded) and a Sign out button, plus the trace and the expectation check for a preview client's sign in. Sign out forgets the ID token in the demo client only; Hydra's session stays until Faye has a logout screen. Access token introspection and userinfo are not shown yet.

## Why this matters for validation

The preview closes the loop between "what I configured" and "what token was issued". It is also the demo for stakeholders: compose a flow, click preview, sign in, show the token.
