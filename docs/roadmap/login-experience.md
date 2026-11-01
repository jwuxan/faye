# Login experience

## Identity first

The user is asked **who** before **how**. The first screen collects only an identifier (email, username or phone). The engine then resolves the account and decides which credential paths to offer.

Why identity first:

1. The flow can adapt per user: offer passkey only if enrolled, require enrollment of a second factor when policy demands it, route federated domains later.
2. One screen at a time keeps every screen simple and every typed screen reusable.
3. Passkeys fit naturally: the identifier field supports WebAuthn conditional UI (autofill), so a user with a passkey can sign in without typing anything.

`identity.resolve` populates `user.*`: `exists`, `has_password`, `has_passkey`, `has_totp`, `mfa_required`, and in future `home_realm`.

### Account enumeration

Identity first can leak whether an account exists. Defaults:

1. Unknown identifiers proceed to the password screen exactly like known ones; the password check fails with the same error and timing.
2. Options that depend on enrollment (passkey button) are only shown after successful verification or via conditional UI, which does not reveal enrollment.
3. Tenants may relax this explicitly (for example internal workforce tenants), and the checker labels such flows.

## Typed screen protocol

The Login UI is a renderer for a fixed set of screen types. The server tells it which type to render, with which capabilities; the UI never sees the flow.

```
GET  /api/flows/{flowId}           → Screen
POST /api/flows/{flowId}/submit    { nonce, action, payload } → Screen | Redirect
```

```json
{
  "flowId": "f_9a…",
  "nonce": "n_3c…",
  "step": "credential",
  "screen": {
    "type": "password",
    "identifier": "alice@example.com",
    "capabilities": { "passkeyAutofill": true, "forgotPassword": false, "switchMethod": ["passkey"] },
    "errors": [{ "code": "invalid_credentials" }],
    "attemptsLeft": 2
  },
  "branding": { "tenantName": "Acme", "logo": "…" }
}
```

Initial screen types:

| Type | Purpose |
|---|---|
| `identifier` | Email / username, remember me, passkey conditional UI |
| `password` | Password for a known identifier, "use another method" |
| `method_picker` | Shown when `choose` has more than one enabled option |
| `passkey` | Explicit passkey ceremony (when autofill is not used) |
| `otp` | TOTP code entry |
| `otp_enroll` | QR code and verification for TOTP enrollment |
| `consent` | Client, requested scopes, grant / deny |
| `error` | Terminal error with a safe message and a retry link |

Error codes and capability flags are part of `schema/screen.schema.json`; copy lives in the Login UI with i18n keys.

## Design system

Shared in `web/packages/ui` and used by Login UI, Admin UI and Demo client:

1. **shadcn/ui** components (Card, Input, Button, Form, Label, Alert, InputOTP, Dialog, Tabs, Badge) on Tailwind CSS, with theme tokens as CSS variables. The Admin UI has light and dark modes; the Login UI is light only.
2. The **system font** for all UI text; the **system monospace** font for identifiers, OTP digits, IR, YAML, JSON and tokens. No web fonts, no external font requests.
3. Login layout: one centered column on a white page (no card), tall fields with an icon cell, tenant logo, one primary action per screen, the identifier shown as an editable chip on every later screen ("alice@example.com · Change").
4. Accessibility baseline: WCAG 2.1 AA, keyboard complete, correct `autocomplete` attributes (`username webauthn`, `current-password`, `one-time-code`), focus moves to the first field on each screen, errors announced via live region.
5. Tenant customization limited to logo, name (shown in every screen's footer too), primary color, radius, a show password button, "keep me signed in", and a sign up button that is a placeholder for now ([ADR 0033](../adr/0033-login-page-customization-and-keep-me-signed-in.md)). Layout is not tenant configurable.
