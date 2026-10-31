---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Applications in the admin console: read only from the authorization server

## Context and Problem Statement

Operators want to see a tenant's applications, as Auth0's Applications page shows each client's id, type, callback URLs and grant types ([Auth0: application settings](https://auth0.com/docs/get-started/applications/application-settings)). In Faye a client belongs to a tenant through `metadata.tenant` on the Hydra client, which selects the flow its sign ins run; so a tenant's clients share its flow and users. Clients were registered in Hydra by `deploy/bootstrap.sh` (now `deploy/bootstrap.mjs`, [ADR 0019](0019-a-five-service-compose-stack.md)).

The first request was to create clients from the console with a generated id and secret. That conflicts with the charter, "What Faye is not" 1: "Faye does not implement protocol endpoints, register clients, or talk to relying parties." The user then decided (2026-10-31): **clients are registered in Hydra outside Faye (ten demo applications, with generated ids and secrets), and are read only in the console.** Their secrets must not be in the source, in git, or where an agent working on the repo reads them.

Facts about Hydra at v2.2.0, the pinned version ([api.json](https://github.com/ory/hydra/blob/v2.2.0/spec/api.json)): `GET /admin/clients` pages with `page_size` and `page_token`, gives the next page in a `Link: rel="next"` header, and never returns secrets. A secret is shown once, when the client is created.

## Decision Drivers

* Charter, "What Faye is not" 1; the authorization server owns clients.
* Charter principle 3 and AGENTS.md rule 3: nothing Hydra specific in the engine; Hydra specifics live in `faye-host-hydra`.
* No secret ever passes through Faye.

## Considered Options

1. An Applications section that creates clients, resets secrets and deletes clients through Hydra (needs a charter amendment).
2. A read only Applications section: a `ClientDirectory` interface beside `FlowHost`, implemented over Hydra's `GET /admin/clients`; the console lists the tenant's clients.
3. The Admin UI calls Hydra's admin API itself.
4. No Applications section; document `hydra create client`.

## Decision Outcome

Option 2. Option 1 crosses the charter and was withdrawn by the user: Faye reads clients only, from the authorization server as their external source, and never creates, changes or deletes one. Option 3 would put Hydra specifics and a second admin credential in the web app.

* `ClientDirectory` (`faye-engine`, host boundary) has one method, `clients()`. The engine does not use it.
* `HydraClientDirectory` (`faye-host-hydra`) reads `GET /admin/clients?page_size=500` and follows `Link: rel="next"`, up to 20 pages, through Ory's generated Java client ([ADR 0021](0021-official-hydra-admin-client.md)). It sends GET only.
* Without Hydra, the in memory host has no registered clients, and the directory is empty.

```
GET /admin/tenants/{t}/clients     id, name, tenant, firstParty, preview, redirectUris, grantTypes, authMethod, createdAt
```

The list is filtered to `metadata.tenant = {t}`. Hydra unreachable gives 502. There is no route that writes a client.

**Demo applications.** `deploy/bootstrap.mjs`, outside Faye, registers ten confidential clients over the two tenants of [ADR 0018](0018-two-tenants-and-an-open-local-console.md) (Acme Web, Acme Docs, Acme Shop, and the third party Acme Partner Portal and Acme Analytics for `acme`; Bank Online, Bank Mobile, Bank Advisor Desk, Bank Business and Bank Investments for `bank`) without a `client_id` or `client_secret`, so Hydra generates both. The one response that carries the secret is appended to `clients.jsonl` in the secrets volume (compose; mounted only into the `demo` service, [ADR 0019](0019-a-five-service-compose-stack.md)) or under `$XDG_STATE_HOME/faye/secrets` (outside the repo); the log shows only the client id. A client whose name exists is skipped, so rerunning keeps the same ids and secrets. Faye never reads that file; the demo client, the applications' relying party, does (ADR 0018 3A).

The console's Applications page shows each client's name and id, its type (public for `token_endpoint_auth_method: none`, else confidential), party, grant types and callback URLs, with details in a side sheet. For the secret it says only whether the client has one and that the authorization server holds it.

### Consequences

* Good, because an author can see which applications run this tenant's flow, and whether they are first or third party (which decides consent), without Faye registering anything.
* Good, because the next authorization server needs one more small adapter, read only.
* Bad, because creating a client or resetting a secret still means going to Hydra (`hydra create client`, `bootstrap.mjs`).
* Neutral: preview clients (`metadata.preview`) are listed and marked.

### Confirmation

* `HydraClientDirectoryTest`: two pages followed through `Link`; tenant, party and auth method read from the client; a secret in Hydra's response is not carried; only GET is sent; a Hydra error is reported.
* `MilestoneFiveTest.usersAndClientsAreListedReadOnlyWithoutSecrets`: empty with the in memory host; unknown tenant 404; a write is 405.
* `deploy/smoke.sh` step 12, on a fresh compose stack: `GET /admin/tenants/{acme,bank}/clients` through the Admin UI carries no `client_secret`, and each of the ten applications it lists signs in its tenant's account with the secret Hydra generated.

Run on 2026-10-31: `cd server && ./gradlew build` passes (`HydraClientDirectoryTest` 3, `MilestoneFiveTest` 8); `deploy/smoke.sh` passes all 13 steps on a fresh compose stack (`docker compose -f deploy/compose.yaml down -v && docker compose -f deploy/compose.yaml up --build -d`).
