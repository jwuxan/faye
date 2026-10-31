# Hydra integration

Ory Hydra (v2) is the authorization server: OAuth2 / OIDC endpoints, client registry, token issuance, sessions. It delegates login, consent and logout UI to an external app. Faye is that app.

## FlowHost

```java
interface FlowHost {
    HostRequest load(HostKind kind, String ref);              // login | consent | logout; ref = the challenge
    Completion accept(HostRequest req, Authenticated outcome); // subject, amr, acr
    Completion accept(HostRequest req, Consented outcome);     // grants, ID token claims
    Completion reject(HostRequest req, String error, String description);
}
```

The engine depends only on this interface ([ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)). Hydra returns `Redirect` for every completion. `HydraFlowHost.load` maps Hydra's request into `HostParams`, the host contract: `subject`, `skip`, `requested_scope`, `requested_access_token_audience`, and from `oidc_context` `acr_values`, `ui_locales` and `login_hint`; for a consent, the `context` our login accept set. The client's `metadata.tenant` and `metadata.preview: true` become the request's tenant and preview flag. On consent accept, the adapter turns `Consented.user` into ID token claims by granted scope (`email`, `email_verified` with email, `name` with profile, `faye_flow_id` for preview clients); the engine computes no claims. `HydraFlowHost.DSL_PATH_ALIASES` lets tenant YAML still say `hydra.` for `host.`; the app hands it to the compiler. `faye-host-hydra` implements it with the Hydra admin API; `faye-host-mock` implements it in memory for tests and the conformance suite.

## Login

1. `GET /login?login_challenge=…` → `load(LOGIN)`: client, `requested_scope`, `skip`, `subject` (if skip), `oidc_context` (`acr_values`, `login_hint`, `ui_locales`).
   A challenge Hydra answers with 400, 404 or 410 is the browser's: `404 flow_not_found`, not a 5xx. Every `redirect_to` Hydra returns is checked before it reaches a `Location` header or the Login UI: an absolute http(s) URL with a host, or `502 host_error` (ADR 0007).
2. Tenant resolution: the adapter reads the client's `metadata.tenant` into `HostRequest.tenant`, and `metadata.preview` into `HostRequest.preview`; no tenant means the default tenant.
3. **Skip handling** is part of the engine, not the DSL: if `skip` is true and the requested `acr_values` are satisfied by the remembered session, accept immediately with the existing subject. Otherwise run the flow (step up). This keeps a misconfigured flow from breaking SSO semantics.
4. `login_hint` prefills the identifier screen (identity first).
5. On `succeed`: accept with `subject`, `remember` (from the identifier screen's "keep me signed in", if the tenant enables it), `amr` and `acr` computed by the engine, and `context: { flowId, versionHash }`.

## Consent

1. `GET /consent?consent_challenge=…` → `load(CONSENT)`: `context.flowId` connects it to the login flow, so login and consent run from the same flow version.
2. The engine runs the flow's `consent` entry: the steps of `login` from the one that asks for consent on ([ADR 0026](../adr/0026-consent-as-a-step.md)). First party clients typically skip the screen via a predicate.
3. On `succeed`: accept with granted scopes and audience, and session claims. The adapter, not the engine, maps the `user` attributes the consent hands it (`email`, `email_verified`, `name`) to ID token claims by granted scope.
4. If Hydra reports `skip` for consent (previously granted), accept immediately.

## Logout

`GET /logout?logout_challenge=…`: a fixed confirmation screen in the prototype, no DSL. Accept revokes the Hydra session.

## amr and acr

| Handler | amr |
|---|---|
| `password.verify` | `pwd` |
| `totp.verify` | `otp` |
| `webauthn.assert` (user verified) | `hwk`, `user` |

`acr` mapping (tenant configurable, default):

| Condition | acr |
|---|---|
| one factor | `faye:1fa` |
| two distinct factor classes, or a user verified passkey | `faye:2fa` |

Requested `acr_values` that the completed flow does not satisfy cause a `reject` with `access_denied` rather than silently issuing a weaker token. The checker warns when a tenant's flow cannot reach an `acr` that its clients request.

## Local deployment

`deploy/compose.yaml`:

1. One SQLite database for Hydra's tables and Faye's ([ADR 0017](../adr/0017-one-sqlite-database.md)).
2. `hydra migrate sql` then `hydra serve all` with `urls.login`, `urls.consent`, `urls.logout` pointing at Faye, `urls.self.issuer` at the public port.
3. Faye server, Login UI, Admin UI, Demo client.
4. A bootstrap job that creates the demo client in Hydra (`authorization_code` + `refresh_token`, PKCE, redirect URI of the demo client) and seeds a demo tenant and users.
