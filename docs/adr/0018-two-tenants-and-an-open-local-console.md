---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Two tenants, each with its applications and user pool, and a console without sign in

## Context and Problem Statement

The user asked (2026-10-31) to:

1. Remove the login credentials hard coded in `application.yaml` (`faye.demo-users`: alice and bob, with a documented password and TOTP secret).
2. Keep only two tenants in config, distribute the ten applications between them, and make the structure tenant → applications → user pool.
3. Remove the console's sign in, and let anyone using the console try a sign in as any of the ten applications.

Before this, `application.yaml` configured five tenants (`admin`, `default`, `mfa`, `partner`, `risk`), the Admin UI signed operators in through Hydra as alice with TOTP (client `faye-admin`, tenant `admin`; [ADR 0010](0010-m5-admin-ui.md) 2B), and the ten generated applications ([ADR 0015](0015-oauth2-client-management.md)) could not be tried: their secrets were only in the secrets volume and no relying party used them. This record supersedes [ADR 0014](0014-tenant-user-directory.md) (the directory is now the tenants' pools in the database, and alice and bob are gone) and ADR 0010's option 2B.

## Decision Drivers

* Charter principle 1 and [ADR 0014](0014-tenant-user-directory.md): no password in the source; tenant isolation of accounts.
* Charter principle 5: a flow can be tried against a real client.
* Keep the M2 to M6 checks: password, passkey and TOTP, step up, consent, a risk hook with egress, previews.
* The risk "the admin token leaks through the Admin UI" of the decision log ([ADR 0036](0036-retire-the-roadmap.md)): the console's sign in was its mitigation.

## Considered Options

1. Which two tenants
   * A. `acme` (password, the risk hook, consent for third party applications; a new `acme.yaml` that joins `risk-totp.yaml` and `partner-consent.yaml`) and `bank` (`passkey-totp.yaml`).
   * B. Keep two of the old ids (`default`, `mfa`) and drop the consent and hook demos.
2. Accounts
   * A. Only the seeded pools: 600 `acme`, 400 `bank`, each with a tenant, passwords in the secrets volume. Tests keep fixed accounts in `src/test/resources/application.yaml`, which replaces the main config on the test classpath.
   * B. Keep alice and bob, with their password in an environment variable.
3. Trying an application
   * A. The demo client becomes the relying party of all ten: it reads `clients.jsonl` (ids and secrets as bootstrap saved them) from the secrets volume, mounted read only, and redeems codes with `client_secret_basic` plus PKCE. The console's Applications page links each client to `demo-client/login?client=<id>&prompt=login`.
   * B. Register the ten applications as public clients, so no secret is needed.
4. Console access
   * A. No sign in; the console listens on 127.0.0.1 only (compose maps `127.0.0.1:3002`, `next dev` binds 127.0.0.1); writes through its proxy still need `x-faye-admin: 1`, which a cross site form cannot send.
   * B. Keep the Hydra sign in.

## Decision Outcome

1A, 2A, 3A, 4A. 1B loses consent and hooks from the stack. 2B keeps a shared credential in config. 3B changes what the applications are (confidential web apps) to save reading one file. 4B is what the user asked to remove.

* Tenants: `acme` (Acme Web, Acme Docs, Acme Shop; Acme Partner Portal and Acme Analytics, third party) and `bank` (Bank Online, Bank Mobile, Bank Advisor Desk, Bank Business, Bank Investments), plus `preview-acme` and `preview-bank`. The `admin` tenant, its flow, `faye-admin`, and the fixed demo clients (`demo`, `demo-mfa`, `partner-app`, `demo-risk`) are gone.
* `faye.demo-users` stays as a property, empty in the shipped config, for tests. Preview test accounts now carry their tenant.
* What [ADR 0014](0014-tenant-user-directory.md) decided about users still holds, with the database in place of the JSON file: accounts are provisioned outside Faye (here by `deploy/directory/seed.mjs`), Faye reads them, and the console lists each tenant's pool read only with which credentials an account has, never their values. No route writes a user (405). Creating or editing accounts from the console crosses the charter ("What Faye is not" 3, principle 2) and is not done.
* The in memory host (no Hydra) loads challenges as client `demo` of the first configured tenant when there is no `default` tenant, so `bootRun` without Hydra still works.

### Consequences

* Good, because no password or TOTP secret is in the shipped config, and every account belongs to exactly one tenant's pool.
* Good, because each of the ten applications can be tried from the console in one click, with its real secret, and the smoke test signs in through all ten.
* Bad, because anyone who can reach the console can draft, publish and roll back every tenant's flow, and change settings, with the server's admin token. It is safe only on a developer machine; a shared deployment needs the sign in back (or an authenticating proxy). The admin token itself still has a local only default in compose (`dev-admin-token`).
* Bad, because the demo client now holds ten client secrets. They stay in the secrets volume; Faye never mounts it.
* Bad, because people trying sign ins must read a seeded password from the secrets volume instead of a documented one.
* Neutral: with no operator accounts, [ADR 0016](0016-operator-account-and-console-preferences.md) leaves password changes out of Faye (1C).

### Confirmation

* `cd server && ./gradlew build`: `MilestoneFiveTest.usersAndClientsAreListedReadOnlyWithoutSecrets` (pools by tenant), and the tests on `src/test/resources/application.yaml`.
* `deploy/smoke.sh` on a fresh compose stack: step 10 (the console answers without sign in, `/signin` is 404, exactly `acme` and `bank`), step 12 (each of the ten applications the console lists signs in its tenant's account, consent for the third party ones; an `acme` account fails on a `bank` application), step 13 (one database).
* `grep -n password server/faye-app/src/main/resources/application.yaml | grep -vE '^[0-9]+:\s*#'` finds nothing: the word appears only in comments describing the flows.

Run on 2026-10-31: `cd server && ./gradlew build` passes (`MilestoneFiveTest` 8); `deploy/smoke.sh` passes all 13 steps on a fresh compose stack (`docker compose -f deploy/compose.yaml down -v && docker compose -f deploy/compose.yaml up --build -d`); the `application.yaml` check finds nothing.
