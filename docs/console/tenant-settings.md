---
description: Set a tenant's sign in policy on the General page, and look up the tenant's applications and users, which the Admin UI shows read only.
---

# Tenant settings, applications and users

This page covers three pages of the Admin UI that are about the tenant instead of its flow. **General** holds the tenant's sign in policy. The checker checks every flow against this policy. **Applications** and **Users** show the tenant's OAuth clients and accounts, read only. This page is for the people who own a tenant's security policy and for engineers who connect applications.

## General settings

Open a tenant, then select **General** under **Settings** in the sidebar. The checker checks every flow of the tenant against these settings. It checks the draft as you edit it, each publish, and each rollback.

A badge in the header says where the settings come from. **From server config** means the values come from `faye.tenants.<id>` in the server's `application.yaml`. **Saved in console** means someone saved the settings here. Saved settings replace the values from the config, except egress.

### Sign in policy

| Setting | What it does |
|---|---|
| **Require MFA** | Require multi factor authentication (MFA). Successful sign ins must verify two factors (`faye:2fa`). |

With **Require MFA** on:

- The checker refuses a flow that has a successful path below `faye:2fa`.
- The engine refuses a sign in that reaches the end below `faye:2fa`, as a second check.
- Faye does not reuse a remembered session, because a remembered session counts as one factor. The flow runs again for the same person instead.
- Flows can read the setting as `tenant.mfa_required`.

> [!TIP]
> The checker does not know which accounts have which credentials. So the checker assumes that any combination can occur. A second factor that depends only on `user.*`, such as `if: user.has_totp`, leaves a path without a second factor. To pass under **Require MFA**, make the second factor depend on `tenant.mfa_required` as well ([ADR 0008](../adr/0008-m3-checks-versions-and-tenant-policy.md)).

### Block defaults

Block defaults set a block's settings for every flow of the tenant, wherever the flow sets no value of its own ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)).

| Setting | Block's own default |
|---|---|
| **Identifier attempts (with password)** | 3 |
| **Password attempts** | 3 |
| **Passkey attempts** | 1 |
| **TOTP attempts** | 3 |
| **Email code attempts** | 3 |
| **Text message code attempts** | 3 |
| **Add phone attempts** | 3 |
| **Enroll TOTP attempts** | 3 |
| **QR code attempts** | 3 |
| **Passkey autofill** | off |

Leave a field empty to keep the block's own default, which the field shows in grey. A default applies the next time a flow is checked or published. Published versions keep the values they were published with. In the block inspector, a setting that comes from this page shows **tenant default**.

### Handlers and Screens

**Handlers** lists every handler that the server has. **Screens** lists every screen type. Each list is an allowlist. A flow that uses an item that you clear fails the check. In the blocks editor, **Add block** shows a block that uses a cleared item as **Disallowed in General settings**.

Each handler shows the amr (authentication methods references) values that it can prove, if any. Each screen shows its options. The badge next to a list's title says **All allowed**, or how many items are allowed. Select **Allow all** to allow every item again.

### Egress

**Egress** lists the named HTTP targets that this tenant's hooks may call, or **None**. The list is read only here. The operator grants targets in the server config, in `faye.tenants.<id>.egress`. See [Egress](../flows/hooks.md#egress).

### Save the settings

When you change a setting, a bar appears at the bottom: **Unsaved changes**, with **Discard** and **Save changes**.

Before the server saves, it checks the tenant's live version against the new settings. If the live version fails the new settings, the server saves nothing and the page lists the checker's problems in red. For example, you cannot turn on **Require MFA** while the live flow lets someone sign in with one factor. First publish a flow that passes under the new settings. Then save the settings.

Saved settings apply to sign ins that start after the save. You do not publish them.

## Applications

Select **Applications** under **Directory**. The page lists the OAuth clients that sign people in with this tenant's flow and users. A **Read only** badge in the header shows that you cannot change the clients here.

The list shows every client that the authorization server holds with `metadata.tenant` set to this tenant. Type in **Search by name or client ID** to filter the list. Each row shows the application's name and its kind:

- **Public**: a client with no secret, which uses Proof Key for Code Exchange (PKCE).
- **Confidential**: a client with a secret.
- **Machine**: a client that only uses the `client_credentials` grant and has no callback URLs.

Each row also says **First party** or **Third party**. A first party client is registered with `metadata.first_party: true`. The shipped flows skip consent for first party clients. A **Preview** badge marks the tenant's preview client.

Select an application to see its details:

| Field | What it shows |
|---|---|
| **Client ID** | The id that the authorization server gave the client, with a copy button. |
| **Client secret** | Never the secret. For a public client: "None. Public client; uses PKCE." Otherwise: "Stored by the authorization server; shown only once, at registration." |
| **Token endpoint authentication** | How the client authenticates, for example `client_secret_basic`, or `none`. |
| **Callback URLs** | Where the authorization server may send the person back. |
| **Grant types** | The OAuth grant types that the client may use. |
| **Tenant** | The tenant from `metadata.tenant`. |
| **Preview** | Shown for a preview client. A preview client runs the draft instead of the live version when previews are on. |
| **Registered** | When the client was registered, if the authorization server knows the date. |

### Try a sign in as an application

Select **Try sign in** next to the application's name. The demo client opens in a new tab. The demo client starts a sign in as that application, with `prompt=login`, so the tenant's flow runs from the first screen. A normal application runs the live version. The preview client runs the draft.

The demo client can sign in as the applications that `deploy/bootstrap.mjs` registers, because the demo client reads their secrets from the stack's secrets volume.

If the list is empty, the page says **No applications**. Register a client in the authorization server with `metadata.tenant` set to the tenant's id. Without Hydra, the in memory host has no clients.

## Users

Select **Users** under **Directory**. The page lists the accounts that the tenant's applications can sign in, with the credentials that each account has. The page is read only.

Type in **Search by email, name or subject** to filter the list. The list shows 50 accounts per page. Use **Previous page** and **Next page** to move between pages.

Select an account to see its details:

| Field | What it shows |
|---|---|
| **Subject** | The account's stable id, which the authorization server puts in tokens as `sub`. |
| **Pool** | The tenant's id if the account belongs to this tenant only. **Shared** if every tenant's applications can sign it in. |
| **Email** | The account's email, and whether it is verified. |
| **Credentials** | **Password**, **Authenticator app (TOTP)** and the number of passkeys, each **Enrolled** or **Not enrolled**. |
| **MFA** | **Required for this account, regardless of tenant policy**, or **Tenant policy**. |

The page never shows a password or a secret, only whether one exists. An account that belongs to one tenant is unknown to every other tenant's applications.

## Why applications and users are read only

The console shows applications and users, but never creates, changes or deletes them. Faye is designed this way.

- **Applications belong to the authorization server.** Faye is not an OAuth2 or OpenID Connect provider and does not register clients ([charter](../../CHARTER.md), "What Faye is not" 1). Register clients with the authorization server's own tools, such as Hydra's admin API. Faye lists clients through a read only interface. Faye never sees a secret after the authorization server issues it ([ADR 0015](../adr/0015-oauth2-client-management.md)).
- **Accounts come from outside Faye.** A tenant's accounts are created outside Faye. In the local stack, `deploy/directory/seed.mjs` writes the accounts. Faye reads the accounts ([ADR 0018](../adr/0018-two-tenants-and-an-open-local-console.md)). Only built in, reviewed handlers enroll credentials, and only inside a flow, for example with **Enroll TOTP** or **Add passkey** (charter principle 2). An account editor in the console would be a second way to change credentials, outside every check that a flow passes.

## Next steps

- [Edit and publish a flow](flow-editor.md): build a flow that passes the tenant's policy.
- [Hooks](../flows/hooks.md): call the egress targets that the tenant grants.
- [Customize the login page](login-page.md)
