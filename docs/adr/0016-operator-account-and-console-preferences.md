---
status: proposed
date: 2026-10-31
decision-makers: Josh Wu
---

# Operator account and console preferences

## Context and Problem Statement

Operators want the basics of an admin console: change their own password, choose the console's appearance, and edit general settings.

* **General settings** of a tenant (MFA policy, handler and screen allowlists) and **login branding** already exist ([ADR 0010](0010-m5-admin-ui.md) 3B, 6B). [ADR 0013](0013-admin-console-shell-and-flow-workspace.md) gives them their own console pages. No new decision is needed for them.
* **Changing the operator's password.** When this record was written, operators were accounts in the `admin` tenant who signed in through Hydra with password and TOTP. [ADR 0018](0018-two-tenants-and-an-open-local-console.md) has since removed that tenant and the console's sign in, so there is no operator account. More generally, changing a password is account management, not sign in. The user decided (2026-10-31): **Faye focuses on login, establishing a person's digital identity; changing a password is not part of it.** Charter, "What Faye is not" 3: "Account settings, profile pages and other product UI belong elsewhere."
* **Console appearance.** The shared theme follows the OS (`@custom-variant dark (@media (prefers-color-scheme: dark))`) and there is no toggle.

## Decision Drivers

* Charter, "What Faye is not" 3: Faye's screens get people signed in, signed up and through consent; account settings belong elsewhere.
* Charter principle 2: credentials change only through built in handlers inside a flow, never through a side door.
* Accounts come from a directory outside Faye, which Faye reads ([ADR 0014](0014-tenant-user-directory.md), [ADR 0018](0018-two-tenants-and-an-open-local-console.md)).
* Small: no new store for preferences if a cookie or `localStorage` will do.

## Considered Options

1. Changing the operator's password
   * A. An admin API endpoint that sets a password hash for a subject.
   * B. A `password.change` handler and screen, used by a short `admin` tenant flow (current password, then TOTP, then new password). The console starts that flow through Hydra with a dedicated `prompt=login` request, like sign in.
   * C. Leave it to the operator's directory (out of Faye).
2. Durable operator accounts
   * A. The `admin` tenant's users live in the external directory behind `UserStore` ([ADR 0014](0014-tenant-user-directory.md)), which must then be durable and writable by the `password.change` handler.
   * B. A separate operator store.
   * C. None: with 1C Faye keeps no operator accounts.
3. Console appearance
   * A. Keep "follows the OS".
   * B. Light, dark or system, chosen in the console header, stored in a cookie so the server renders the right theme with no flash. This needs the `dark` variant to key off a class as well as the media query, in `@faye/ui`, which the Login UI shares.

## Decision Outcome

1C, 2C; 3B proposed.

1A is a credential write outside any flow, which principle 2 rules out. 1B is a password change screen, which is account settings: it crosses "What Faye is not" 3 and was withdrawn by the user. Passwords change in the directory that owns the accounts, outside Faye. With no password change in Faye and no console sign in ([ADR 0018](0018-two-tenants-and-an-open-local-console.md)), there are no operator accounts to keep, so 2A and 2B are not needed.

3B does not depend on anything else. It is left out of ADR 0013 because changing the dark variant touches the Login UI's theme too. It is not implemented yet, which is why this record stays proposed.

### Consequences

* Good, because Faye adds no account management screen, handler or protocol change, and stays on sign in.
* Good, because no route or handler in Faye changes an existing account's password.
* Bad, because an operator who wants to change a password must do it in the directory that holds the account.
* Neutral: preferences (3B) stay per browser.

### Confirmation

* 1C: no `password.change` handler or screen exists: `grep -rn --exclude-dir=node_modules --exclude-dir=.next "password.change\|password_change" schema server/*/src/main web/apps` finds nothing (run on 2026-10-31: nothing).
* 3B, when implemented: `cd web && pnpm build`, and a check that the Login UI still follows the OS.
