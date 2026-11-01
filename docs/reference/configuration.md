---
description: Every environment variable and tenant setting that Faye reads, with its default and meaning, and where Faye keeps its state.
---

# Configuration

This page lists every setting that Faye reads: the environment variables of each process, the tenant configuration in `application.yaml`, and where state lives on disk. It is for engineers who deploy Faye or run it from source. Each default on this page is the value in the code or the script that reads the variable.

The settings fall into these groups:

- [Server](#server): the Java server, read through `application.yaml`.
- [Image and container](#image-and-container): the start scripts of the Docker image.
- [Login UI](#login-ui), [Admin UI](#admin-ui) and [Demo client](#demo-client): the three web apps.
- [Deploy scripts](#deploy-scripts): the seed, the client registrations and the test scripts.
- [Tenants](#tenants): `faye.tenants` and the other keys under `faye` in `application.yaml`.

## Server

The server reads these variables through [`application.yaml`](../../apps/server/faye-app/src/main/resources/application.yaml). The class [`FayeProperties`](../../apps/server/faye-app/src/main/java/faye/app/FayeProperties.java) binds the `faye` keys.

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `FAYE_DATABASE` | `spring.datasource.url` | `faye.db` | The SQLite database file. A relative path is relative to the working directory. Flyway migrates it at startup ([ADR 0020](../adr/0020-versioned-schema-migrations-with-flyway.md)). |
| `FAYE_LOGIN_UI_URL` | `faye.login-ui-url` | `http://localhost:3000` | The base URL of the Login UI. Faye sends the browser to each screen's path under it, with `?flow={flowId}`. |
| `FAYE_HYDRA_ADMIN_URL` | `faye.hydra-admin-url` | empty | Hydra's admin API. If empty, Faye uses an in memory host instead of Hydra: any challenge starts a flow for the client `demo`, and the Admin UI lists no applications. |
| `FAYE_ADMIN_TOKEN` | `faye.admin-token` | empty | The bearer token of the admin API. If empty, the admin API is off. |
| `FAYE_ADMIN_SOCKET` | `faye.admin-socket` | empty | The path of the Unix socket that serves the admin API. Faye never serves the admin API on its TCP port. If empty, the admin API is off ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)). |
| `FAYE_PREVIEW_ENABLED` | `faye.preview-enabled` | `false` | If `true`, a client registered with `metadata.preview: true` runs its tenant's draft, and Faye records a trace for the Admin UI. If `false`, Faye refuses sign ins from preview clients. |
| `FAYE_CODE_KEY` | `faye.code-key` | empty | A base64 key of at least 32 bytes. Faye computes a message authentication code (MAC) with it over each one time code that it delivers ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). If empty, Faye generates a key once and keeps it in the database. |
| `FAYE_DEMO_OUTBOX` | `faye.demo-outbox` | `build/outbox` | A folder. Faye writes each code to a file here for every tenant channel that has no webhook. If empty, such a channel is off, and Faye refuses to publish a flow that uses its blocks. |
| `FAYE_IP_REPUTATION_URL` | `faye.egress.ip-reputation` | empty | The URL of the egress target `ip-reputation`, which Acme's risk hook calls. If empty, the hook cannot reach it and scores every sign in as high risk. |
| `FAYE_WEBAUTHN_RP_ID` | `faye.webauthn.rp-id` | `localhost` | The passkey relying party ID: the Login UI's registrable domain. |
| `FAYE_WEBAUTHN_ORIGINS` | `faye.webauthn.origins` | `http://localhost:3000` | The Login UI origins that may run passkey ceremonies. Separate several origins with commas. |

> [!CAUTION]
> The admin API can publish flows for every tenant. Set `FAYE_ADMIN_SOCKET` to a path that only the Admin UI can open, and use a long random `FAYE_ADMIN_TOKEN`.

The server is a Spring Boot application. Spring Boot's usual property sources also work, for example a command line argument such as `--faye.flow-ttl=15m`.

## Image and container

The image's first boot script, [`deploy/image/scripts/init`](../../deploy/image/scripts/init), reads these variables. It runs as root before any service starts.

| Variable | Default | Meaning |
|---|---|---|
| `FAYE_SERVICES` | `hydra,faye,login,admin` | The processes to run, separated by commas ([ADR 0023](../adr/0023-one-container-many-processes.md)). For example, `faye,login,admin` runs Faye without Hydra. |
| `FAYE_DEMO` | `false` | If `true`, the container also runs the demo: the stub IP reputation service, the client registrations, the seed and the demo client. The compose file sets it to `true`. |
| `FAYE_PUBLIC_HOST` | `localhost` | The host name in every public URL that the script derives. See [Derived variables](#derived-variables). |
| `FAYE_JAVA_OPTS` | `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx128m -XX:+ExitOnOutOfMemoryError` | The JVM options of the Faye server. |
| `FAYE_ADMIN_TOKEN` | generated | The admin API token. If unset, the script reuses `/var/lib/faye/secrets/admin-token`, or generates 32 random bytes on first boot. |
| `SECRETS_SYSTEM` | generated | Hydra's system secret. If unset, the script reuses `/var/lib/faye/secrets/hydra-system-secret`, or generates one on first boot. |

> [!WARNING]
> The script refuses to start Hydra with a plain `http` issuer on any host but `localhost` or `127.0.0.1`. If you set `FAYE_PUBLIC_HOST` to another host, also set `URLS_SELF_ISSUER` to an `https` URL.

### Derived variables

The script sets each of these variables only if it is not already set. Every service in the container sees them.

| Variable | Value | Read by |
|---|---|---|
| `URLS_SELF_ISSUER` | `http://{host}:4444` | Hydra |
| `URLS_LOGIN` | `http://{host}:8080/login` | Hydra |
| `URLS_CONSENT` | `http://{host}:8080/consent` | Hydra |
| `DSN` | `sqlite:///var/lib/faye/faye.db?_fk=true&_journal_mode=WAL&_busy_timeout=10000` | Hydra |
| `HYDRA_ARGS` | `--dev`, only for a loopback issuer | Hydra |
| `FAYE_LOGIN_UI_URL` | `http://{host}:3000` | Faye server |
| `FAYE_WEBAUTHN_RP_ID` | `{host}` | Faye server |
| `FAYE_WEBAUTHN_ORIGINS` | `http://{host}:3000` | Faye server |
| `FAYE_DATABASE` | `/var/lib/faye/faye.db` | Faye server |
| `FAYE_ADMIN_SOCKET` | `/run/faye/admin.sock` | Faye server, Admin UI |
| `FAYE_DEMO_OUTBOX` | `/var/lib/faye/outbox` | Faye server |
| `FAYE_SERVER_URL` | `http://127.0.0.1:8080` | Login UI, demo client |
| `DEMO_CLIENT_URL` | `http://{host}:3001` | Admin UI, demo client |
| `DEMO_REDIRECT_URI` | `http://{host}:3001/callback` | Client registrations |
| `HYDRA_PUBLIC_URL` | `http://{host}:4444` | Demo client |

`{host}` is the value of `FAYE_PUBLIC_HOST`.

When Hydra runs in the container, the script also sets `FAYE_HYDRA_ADMIN_URL` and `HYDRA_ADMIN_URL` to `http://127.0.0.1:4445`, and `HYDRA_INTERNAL_URL` to `http://127.0.0.1:4444`.

When `FAYE_DEMO` is `true`, the script also sets these:

| Variable | Value |
|---|---|
| `FAYE_IP_REPUTATION_URL` | `http://127.0.0.1:8000/score` |
| `FAYE_PREVIEW_ENABLED` | `true` |
| `SECRETS`, `SECRETS_DIR` | `/var/lib/faye/demo` |
| `DATABASE` | `/var/lib/faye/faye.db` |
| `CLIENT_REGISTRATIONS` | `/var/lib/faye/demo/clients.jsonl` |
| `DEMO_SIGN_IN_URL` | `http://{host}:3001/login` |

The [Dockerfile](../../Dockerfile) also sets `SERVE_ADMIN_HOST=127.0.0.1`, so Hydra's admin API listens only inside the container. Hydra reads its other settings from its own environment variables.

## Login UI

| Variable | Default | Meaning |
|---|---|---|
| `FAYE_SERVER_URL` | `http://localhost:8080` | The Faye server. The Login UI calls the screen API there from its own server, never from the browser. |
| `DEMO_SIGN_IN_URL` | unset | Where `/login` goes when no flow is named. If unset, `/login` without a flow shows `/error`. With it set, `/error` also offers to start again after 5 seconds ([ADR 0031](../adr/0031-failed-sign-in-stays-on-faye.md)). |
| `FAYE_EMBED_ORIGIN` | unset | The one origin that may receive a host's handoff result, when an embedding page opened the Login UI in a popup ([ADR 0025](../adr/0025-host-neutral-boundaries-for-embedding.md)). A value that is not exactly one origin, `*` included, is ignored and logged. |

## Admin UI

| Variable | Default | Meaning |
|---|---|---|
| `FAYE_ADMIN_SOCKET` | unset | The server's admin API socket. Required: without it, every call to the admin API fails. |
| `FAYE_ADMIN_TOKEN` | empty | The bearer token that the Admin UI sends. It must match the server's token. The browser never sees it. |
| `DEMO_CLIENT_URL` | `http://localhost:3001` | The demo client that **Preview** and **Try sign in** open. |
| `FAYE_DOCS_DIR` | the repository's `docs/` | The folder of the docs site at `/docs`. The image sets it to `/opt/faye/docs`. |

> [!CAUTION]
> The Admin UI has no sign in. Anyone who can reach it can use the admin API. Serve it only on a loopback address, as the compose file does with `127.0.0.1:3002`.

## Demo client

| Variable | Default | Meaning |
|---|---|---|
| `HYDRA_PUBLIC_URL` | `http://localhost:4444` | Hydra's public URL, where the browser goes to sign in. |
| `HYDRA_INTERNAL_URL` | the value of `HYDRA_PUBLIC_URL` | The base URL of Hydra's token endpoint, as the demo client's server reaches it. |
| `DEMO_CLIENT_URL` | `http://localhost:3001` | The demo client's own URL. The redirect URI is this URL plus `/callback`. |
| `CLIENT_REGISTRATIONS` | `$XDG_STATE_HOME/faye/secrets/clients.jsonl` | The file of client ids and secrets that the client registrations write. `XDG_STATE_HOME` defaults to `~/.local/state`. |
| `FAYE_SERVER_URL` | `http://localhost:8080` | The Faye server, which reports preview runs. |
| `HYDRA_ADMIN_URL` | `http://localhost:4445` | Hydra's admin API. When a preview client signs out, the demo client ends its Hydra login session there. |

## Deploy scripts

| Script | Variable | Default | Meaning |
|---|---|---|---|
| [`seed.mjs`](../../deploy/directory/seed.mjs) | `DATABASE` | `/data/faye.db` | The database to write the accounts into. The seed waits until Faye has migrated it, and does nothing if accounts exist. |
| `seed.mjs` | `SECRETS` | `/secrets` | The folder for `users.csv`, which holds the plain passwords. |
| [`bootstrap.mjs`](../../deploy/bootstrap.mjs) | `HYDRA_ADMIN_URL` | `http://localhost:4445` | Hydra's admin API, where the script registers clients. |
| `bootstrap.mjs` | `DEMO_REDIRECT_URI` | `http://localhost:3001/callback` | The redirect URI of every client it registers. |
| `bootstrap.mjs` | `SECRETS_DIR` | `$XDG_STATE_HOME/faye/secrets` | The folder for `clients.jsonl`. |
| [`ip-reputation.mjs`](../../deploy/ip-reputation.mjs) | `HOST` | `::` | The address to listen on. The port is always 8000. |
| [`dev.sh`](../../deploy/dev.sh) | `FAYE_DATABASE` | `deploy/.data/faye.db` | The database that Hydra and Faye share. |
| `dev.sh` | `FAYE_ADMIN_TOKEN` | `dev-admin-token` | The admin API token for the server and the Admin UI. |
| [`hydra-local.sh`](../../deploy/hydra-local.sh) | `FAYE_DATABASE` | `deploy/.data/faye.db` | The database that Hydra writes its tables into. |
| [`smoke.sh`](../../deploy/smoke.sh) | `FAYE_CONTAINER` | `faye` | The container to read secrets from. |
| `smoke.sh` | `FAYE_DATABASE` | unset | If set, the script tests a stack that `dev.sh` runs: it reads the secrets from `$XDG_STATE_HOME/faye/secrets` and the codes from the outbox next to the database. |
| Gradle tests | `FAYE_REGENERATE` | unset | If `1`, the tests rewrite the generated conformance vectors and the compiler's golden files instead of comparing against them. |

## Tenants

The `faye` section of `application.yaml` declares the tenants and a few limits. This is the shipped configuration:

```yaml
faye:
  flow-ttl: 30m
  tenants:
    acme:
      name: Acme
      flow: classpath:flows/acme.yaml
      # Hooks of this tenant may call these named targets, and no others
      egress: [ip-reputation]
    bank:
      name: Bank
      flow: classpath:flows/passkey-totp.yaml
      # delivery:
      #   email: { url: https://mail-relay.example/faye, secret: "${FAYE_BANK_EMAIL_SECRET}" }
      #   sms: { url: https://sms-relay.example/faye, secret: "${FAYE_BANK_SMS_SECRET}", per-hour: 100 }
  egress:
    ip-reputation: ${FAYE_IP_REPUTATION_URL:}
```

At startup, Faye checks each tenant's flow against the tenant's settings and publishes it. It skips a tenant that already published a version, so a version that you published or rolled back to stays live across restarts.

### Keys of a tenant

Each key under `faye.tenants` is a tenant id. These keys configure the tenant:

| Key | Default | Meaning |
|---|---|---|
| `flow` | required | The tenant's flow file, as a Spring resource such as `classpath:flows/acme.yaml` or `file:/etc/faye/acme.yaml`. |
| `name` | none | The display name that the sign in screens show, until an admin saves branding. |
| `mfa-required` | `false` | If `true`, every sign in must reach `faye:2fa`. The checker refuses a flow with a path that succeeds with `faye:1fa`. |
| `handlers` | all | The handlers that the tenant's flows may call. |
| `screens` | all | The screen types that the tenant's flows may show. See [Sign in screens](screens.md). |
| `egress` | none | The named egress targets that the tenant's hooks may call. |
| `blocks` | the blocks' own | Default settings of blocks, by block id, for example `password: { attempts: 5 }` ([ADR 0030](../adr/0030-blocks-with-fixed-screens-and-handlers.md)). A flow's own `with` still wins. |
| `delivery.email`, `delivery.sms` | none | The webhook for each channel of one time codes ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). |

A webhook takes three keys:

| Key | Default | Meaning |
|---|---|---|
| `url` | required | Where Faye posts each code as JSON. It must be `https`, or `http` to a loopback address. |
| `secret` | required | The key that signs each post. Faye sends an HMAC SHA-256 signature of the body in the `Faye-Signature` header. |
| `per-hour` | `100` | The most codes that this tenant may send on this channel in one hour. |

A channel without a webhook uses the outbox folder, `FAYE_DEMO_OUTBOX`. If that is also empty, the channel is off.

> [!NOTE]
> An admin can change `mfa-required`, `handlers`, `screens` and `blocks` at run time on the tenant's **General** settings page. Saved settings override `application.yaml`. Egress grants are the exception: only `application.yaml` sets them, because they decide which services tenant code may reach. Branding, "keep me signed in" and flow time limits exist only as saved settings. See [Tenant settings](../console/tenant-settings.md) and [Customize the login page](../console/login-page.md).

### Other keys under `faye`

| Key | Default | Meaning |
|---|---|---|
| `faye.flow-ttl` | `30m` | How long the store keeps a suspended flow, from its start. Set it to Hydra's challenge lifetime. A tenant may set shorter time limits per entry kind, never longer ([ADR 0035](../adr/0035-flow-settings-per-entry-kind.md)). |
| `faye.egress.{name}` | none | The base URL of each named HTTP target that hooks may call. A target with an empty URL is left out, so a hook that calls it fails. |
| `faye.demo-users` | `[]` | Accounts that Faye writes into the user store at startup. The tests use them. The shipped configuration has none: the seed writes the demo accounts. |

## Where state lives

In the image, all state lives in one volume at `/var/lib/faye`:

| Path | What | Who can read it |
|---|---|---|
| `faye.db`, with `faye.db-wal` and `faye.db-shm` | The one SQLite database. It holds Hydra's tables and Faye's tables: flows, versions, hooks, settings, traces and each tenant's user pool ([ADR 0017](../adr/0017-one-sqlite-database.md)). Faye's migration history is the table `faye_schema_history`. | Hydra, Faye and the seed |
| `secrets/` | `admin-token` and `hydra-system-secret`. | Root only. The start scripts read them before they drop privileges. |
| `demo/` | `users.csv` (plain passwords) and `clients.jsonl` (client secrets). Only when `FAYE_DEMO` is `true`. | The demo processes only, not Faye |
| `outbox/` | One file per delivered code, named by channel and address, such as `email-{address}.json`. | Faye only |

The admin API socket is `/run/faye/admin.sock`. Its folder admits only the Admin UI's group, so the Login UI cannot open it ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)).

Without Docker, `dev.sh` keeps the database at `deploy/.data/faye.db`, the outbox at `deploy/.data/outbox/`, the socket at `deploy/.data/run/admin.sock`, and the secrets at `~/.local/state/faye/secrets/`. See [Build and run from source](../develop/build-from-source.md#without-docker).

## Next steps

- [Quickstart](../start/quickstart.md): run the image with its defaults.
- [Build and run from source](../develop/build-from-source.md): run each process yourself.
- [Hooks](../build/hooks.md): how a hook uses egress targets.
- [Sign in screens](screens.md): the screens that the `screens` allowlist names.
