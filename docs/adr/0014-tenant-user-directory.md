---
status: superseded by 0018
date: 2026-10-31
decision-makers: Josh Wu
---

# An external user directory per tenant, read only in the admin console

## Context and Problem Statement

Operators want to see who can sign in, as Auth0's User Management > Users does ([Auth0: create users](https://auth0.com/docs/manage-users/user-accounts/create-users)). Accounts live behind `UserStore` (`server/faye-identity`): in the prototype, `InMemoryUserStore` loaded from `faye.demo-users`, and in production whatever directory a deployment plugs in. Ory Hydra stores no end users; it "connects to your existing identity provider through a login and consent app" ([Hydra README](https://github.com/ory/hydra/blob/master/README.md)).

The first request was to add users from the console, with a user pool per tenant. That conflicts with the charter: "What Faye is not" 3 keeps Faye's author tooling to building, checking and trying flows, and principle 2 lets only built in handlers create accounts. The user then decided (2026-10-31): **users live in an external directory, a JSON file of 1000 accounts for the demo; Faye only reads it; the console lists them and cannot edit them.** Passwords and secrets, even for the demo, are credentials: they must not be in the source, in git, or anywhere an agent working on the repo reads them.

## Decision Drivers

* Tenant isolation: users of one tenant must be unknown to the applications of another; a tenant's applications share its users.
* Credentials stay out of the repo and out of Faye's reach beyond what verifying needs (an Argon2 hash; a TOTP seed).
* Charter, "What Faye is not" 3, and the risk "scope creep toward a full IdP" in [decisions-and-risks.md](../roadmap/decisions-and-risks.md).
* Charter principle 2: only built in handlers create accounts and enroll credentials, inside a flow.
* Charter principle 5: a flow can be tried before it is published. Knowing which accounts exist and what they have enrolled (a password, TOTP, a passkey) is how an author picks the right account to try a branch with.
* No secret leaves the server: no password hash, TOTP secret or passkey key.

## Considered Options

1. Console
   * A. A Users section that creates, edits and deletes accounts (needs a charter amendment).
   * B. A read only Users section: the server lists the accounts a tenant's flows can find, with which credentials each has, never their values.
2. Where the demo directory comes from
   * A. A JSON file committed to the repo, with hashes and the passwords to try them with.
   * B. Generated at deploy time by a seed container (`deploy/directory/seed.mjs`, Node's built in Argon2) into Docker volumes: `users.json` (hashes and TOTP seeds) in a volume Faye mounts read only, `users.csv` (passwords) in a secrets volume Faye never mounts. The repo holds only the generator.
3. Tenant isolation
   * A. Separate stores per tenant.
   * B. An account may name a `tenant`; the identity handlers treat it as unknown to clients of any other tenant (the same filter that hides preview accounts), and an account without one is shared.

## Decision Outcome

1B, 2B, 3B. 1A crosses the charter and was withdrawn. 2A puts credentials in git. 3A would need a store per tenant behind one `UserStore` interface for no gain: one filter in `IdentityHandlers.visible` covers identifier lookup, passkeys and the bound subject alike.

```
GET /admin/tenants/{t}/users     subject, tenant (null when shared), identifier, name, emailVerified, password, totp, passkeys (a count), mfaRequired
```

* `faye.user-directory` (`FAYE_USER_DIRECTORY`) names the JSON file; Faye loads it at startup and only reads it. Enrollments made in flows stay in memory, as before.
* The seed writes 1000 accounts over the demo tenants (`default` 400, `mfa` 300, `partner` 200, `risk` 100), each with a random 16 character password, a third with TOTP. It runs once per volume; `docker compose down -v` regenerates.
* Identifiers must be unique across the directory: `byIdentifier` finds one account, then the tenant filter applies.
* `UserStore` gains `all()`. Preview test accounts ([ADR 0011](0011-m6-preview-loop.md)) are left out of the list. No route writes a user (405).
* The demo accounts in `application.yaml` (alice, bob) stay shared, with their documented demo password, because `smoke.sh` and the M2 to M6 checks use them.

### Consequences

* Good, because authors can see which accounts exist and what each can prove, without Faye becoming a user admin tool.
* Good, because nothing in the console can create an account or a credential outside a flow.
* Good, because a tenant's users are invisible to other tenants' applications, with enumeration resistance intact: an unknown account and another tenant's account look the same.
* Good, because no generated password or hash is in the repo; the passwords sit in a Docker volume only the seed and bootstrap mount.
* Bad, because `all()` lists everything in one response and the console pages in the browser. Fine for 1000 accounts; a large directory would need paging and search on the server.
* Bad, because alice and bob keep a documented demo password in `application.yaml`. Moving them into the seeded directory would mean `smoke.sh` reading their credentials from the volume too.

### Confirmation

* `IdentityFlowTest.anAccountOfOneTenantIsUnknownToTheClientsOfAnother`: `identity.resolve` finds the account for its tenant's client only, and through the engine the right password fails for another tenant's client.
* `MilestoneFiveTest.usersAndClientsAreListedReadOnlyWithoutSecrets`: a directory file written at test time loads; a tenant lists its own and shared accounts, not another's; no TOTP secret or hash in the response; a write is 405.
* `deploy/smoke.sh` step 12, on a fresh compose stack: a seeded `default` account (its password read from the secrets volume, never printed) signs in through `demo` and fails through `demo-mfa`; the Admin UI lists it for `default` only.
