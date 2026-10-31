# Hydra integration

Ory Hydra (v2) is the authorization server: OAuth2 / OIDC endpoints, client registry, token issuance, sessions. It delegates login, consent and logout UI to an external app. Faye is that app.

## FlowHost

```java
interface FlowHost {
    HostRequest load(HostKind kind, String challenge);   // login | consent | logout
    Redirect accept(HostRequest req, Outcome outcome);  // subject, amr, acr, remember, grants
    Redirect reject(HostRequest req, String error, String description);
}
```

The engine depends only on this interface. `faye-host-hydra` implements it with the Hydra admin API; `faye-host-mock` implements it in memory for tests and the conformance suite.

## Login

1. `GET /login?login_challenge=…` → `load(LOGIN)`: client, `requested_scope`, `skip`, `subject` (if skip), `oidc_context` (`acr_values`, `login_hint`, `ui_locales`).
2. Tenant resolution: from client metadata (`metadata.tenant`), falling back to a default tenant.
3. **Skip handling** is part of the engine, not the DSL: if `skip` is true and the requested `acr_values` are satisfied by the remembered session, accept immediately with the existing subject. Otherwise run the flow (step up). This keeps a misconfigured flow from breaking SSO semantics.
4. `login_hint` prefills the identifier screen (identity first).
5. On `succeed`: accept with `subject`, `remember` (from the identifier screen's "keep me signed in", if the tenant enables it), `amr` and `acr` computed by the engine, and `context: { flowId, versionHash }`.

## Consent

1. `GET /consent?consent_challenge=…` → `load(CONSENT)`: `context.flowId` connects it to the login flow, so login and consent run from the same flow version.
2. The engine runs the `consent` section. First party clients typically skip the screen via a predicate.
3. On `succeed`: accept with granted scopes and audience, and session claims. ID token claims are populated from the identity store (`email`, `email_verified`, `name`) filtered by granted scope.
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
