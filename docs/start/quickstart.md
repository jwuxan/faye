---
description: Run Faye with Docker, sign in as a seeded demo user, and change a flow, in about 10 minutes after the first build.
---

# Quickstart

This page takes you from nothing to a signed in demo user and a changed flow. Plan for about 10 minutes, plus the time Docker needs to build the image the first time. It is for anyone who wants to see Faye work before reading how it works. You run the whole stack in one Docker container on your own machine.

## Prerequisites

> [!IMPORTANT]
> You need these on your machine:
>
> - Git.
> - Docker with Docker Compose.
> - `make`. If you do not have it, each step also shows the plain `docker compose` command.
> - Free ports 3000, 3001, 3002, 4444 and 8080.
> - A web browser. Type every URL with the host name `localhost`, not `127.0.0.1`. Passkeys work only on `localhost`.

You do not need Java or Node.js. The image builds everything inside Docker.

## Run Faye and sign in

### 1. Get the code

```bash
git clone https://github.com/jwuxan/faye
cd faye
```

### 2. Start the stack

```bash
make up
```

Without `make`, run the same command directly:

```bash
docker compose -f deploy/compose.yaml up -d --build --remove-orphans
```

The command builds one image from the [Dockerfile](../../Dockerfile) and starts one container named `faye` ([ADR 0023](../adr/0023-one-container-many-processes.md)). The image holds:

- Ory Hydra, the authorization server.
- The Faye server, a Java application.
- The Login UI and the Admin UI, two Next.js apps.

The [compose file](../../deploy/compose.yaml) sets `FAYE_DEMO=true`. The demo adds a demo client, a stub IP reputation service, the client registrations and the seed that creates the demo accounts.

Wait until the container is healthy:

```bash
docker compose -f deploy/compose.yaml ps
```

The `STATUS` column shows `(healthy)` when the stack is ready.

> [!NOTE]
> The first start takes a few minutes. Docker builds the image first. Then the seed hashes 1000 passwords with Argon2. To watch progress, run `docker logs -f faye`. The seed prints a line that starts with `[seed] seeded 1000 users` when it is done.

### 3. Find a seeded account

The seed writes the demo accounts and their passwords into the container. Print them:

```bash
docker exec faye cat /var/lib/faye/demo/users.csv
```

The file starts like this:

```text
tenant,identifier,password,totp_secret
acme,ava.park.0001@acme.example,ava.park,
acme,ben.park.0002@acme.example,ben.park,
acme,chloe.park.0003@acme.example,chloe.park,<base32 secret>
```

| Column | Meaning |
|---|---|
| `tenant` | The tenant whose user pool holds the account: `acme` (600 accounts) or `bank` (400 accounts). |
| `identifier` | The email address you type on the sign in screen. |
| `password` | The account's password. It is always the person's name as `first.last`, in lowercase. |
| `totp_secret` | The account's secret for an authenticator app, which makes time based one time passwords (TOTP). It is in base32. Every third account has one. The others leave it empty. |

The database stores only Argon2 hashes. The plain passwords exist only in this file, inside the container's volume.

If `cat` reports that the file does not exist, the seed is still running. Wait a minute and try again.

### 4. Sign in through the demo client

Open http://localhost:3001. This is the demo client, an OpenID Connect application that signs in through Hydra.

1. In **Client**, pick **Acme Web**.
2. Leave **acr_values**, **prompt**, **max_age** and **ui_locales** at their defaults, and select **Sign in**.

The browser goes to Hydra, then to Faye, then to the Login UI. Acme's flow is [`acme.yaml`](../../apps/server/faye-app/src/main/resources/flows/acme.yaml). You see these screens:

1. **Email.** Type the identifier of an `acme` account, then select **Continue**.
2. **Choose how to continue.** The flow offers **Password**, **Email** and **QR code**. Select **Password**.
3. **Enter your password.** Type the password, then select **Sign in**.
4. **Add a phone number.** The flow offers to add a phone for texted codes. Select **Not now**.

The browser returns to the demo client. The **Signed in** card shows the claims of the ID token: `sub`, `name`, `email`, `amr`, `acr` and `auth_time`. The **ID token** card shows the raw token and its decoded claims.

For this sign in, the authentication methods references (`amr`) are `["pwd"]`. The authentication context class reference (`acr`) is `faye:1fa`. The engine computes both claims from the credentials that verified. A flow cannot set them.

> [!TIP]
> To see a second factor, select **Sign out**, pick **Bank Online**, and use a `bank` account that has a `totp_secret`. After the password, the flow asks for a 6 digit code. If you have `oathtool`, run `oathtool --totp -b <secret>`. Otherwise, add the secret to an authenticator app. Next, the flow offers to create a passkey. Select **Not now**. In the token, `amr` now lists `pwd` and `otp`, and `acr` is `faye:2fa`.

To try a one time code instead of a password, pick **Email** on the method screen. The demo does not send email. Faye writes each code to a file in the container:

```bash
docker exec faye sh -c 'cat /var/lib/faye/outbox/*'
```

### 5. Open the Admin UI

Open http://localhost:3002. The Admin UI opens on **Choose a tenant**. Select **acme**.

The sidebar has three groups:

- **Sign in**: **Login flow** (the flow editor, beside a live graph of the flow), **Hooks**, **Versions** and **Customization**.
- **Directory**: **Applications** and **Users**, read only lists of the tenant's applications and accounts.
- **Settings**: **General**, the tenant's settings.

On **Applications**, every application has **Try sign in**, which runs it through the demo client.

> [!CAUTION]
> The Admin UI has no sign in. Anyone who can reach the Admin UI can publish flows. The compose file publishes the Admin UI only on the address `127.0.0.1`, so only your machine can reach it. You still open it as http://localhost:3002. Do not change this setting.

### 6. Change the flow and preview it

1. Open **Login flow** and select the **YAML** tab.
2. Find the `add-phone` step and delete it. These are the lines to delete:

   ```yaml
       # Offered to people without phone codes; "Not now" skips it
       add-phone:
         if: { not: user.has_phone }
         block: add-phone
         with: { attempts: 1 }
   ```

3. Wait for the header to show **Draft saved** and the status bar to show **No problems**. The checker runs on every edit.
4. Select the **Preview** tab. In **Account**, choose **New user**. Then select **Preview**.
5. Sign in with the identifier and password that the panel shows. The **Add a phone number** screen no longer appears. The trace highlights each block that the run visits.

The preview runs your draft, not the live version. Nothing changes for real users until you select **Publish**.

To build a flow step by step, read [Write your first flow](../flows/first-flow.md). To learn every preview control, read [Preview a draft](../console/preview.md).

### 7. Stop and clean up

To stop the stack and keep its data:

```bash
make down
```

`make down` runs `docker compose -f deploy/compose.yaml down`. The `data` volume keeps the database, the secrets and the seeded accounts. The next `make up` uses them again.

To delete everything, including every account and published version:

```bash
docker compose -f deploy/compose.yaml down -v
```

The next start seeds new accounts with new TOTP secrets.

## What runs on each port

| Port | Service | Notes |
|---|---|---|
| 4444 | Hydra, public API | OAuth 2.0 and OpenID Connect endpoints. The demo client talks to it. |
| 8080 | Faye server | Hydra sends the browser to `/login` and `/consent` here. The Login UI calls the screen API here. |
| 3000 | Login UI | Shows every sign in screen. |
| 3001 | Demo client | An application that signs in and shows the ID token. |
| 3002 | Admin UI | Bound to `127.0.0.1` on your machine. It has no sign in. |

Some services stay inside the container and are never published:

- Hydra's admin API on port 4445.
- The stub IP reputation service on port 8000.
- Faye's admin API, which listens only on the Unix socket `/run/faye/admin.sock` ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)).

All state lives in one volume at `/var/lib/faye`. Hydra's tables and Faye's tables share one SQLite database file ([ADR 0017](../adr/0017-one-sqlite-database.md)).

## Troubleshooting

### Sign in hangs, or the container never becomes healthy

The usual cause is an old database from an earlier build. Delete the volume and start again:

```bash
docker compose -f deploy/compose.yaml down -v && make up
```

### The first start is slow

This is normal. The health check allows 240 seconds for the start. The seed hashes 1000 passwords before the accounts exist. Run `docker logs -f faye` to see each service start.

### A port is already in use

If Docker reports that a port is already allocated, stop the program that uses it. Do not change the port numbers in the compose file. Hydra, the Login UI and the demo client find each other by these URLs.

### Passkeys do not work

Passkeys work only on the host name `localhost`. Open the demo client at http://localhost:3001. The demo client sends you to the Login UI at http://localhost:3000. A passkey fails at `127.0.0.1` or at another host name. To serve another host, see `FAYE_PUBLIC_HOST` in [Configuration](../reference/configuration.md#image-and-container).

### The admin API answers 404 on port 8080

This is by design. Faye serves the admin API only on a Unix socket inside the container. The Admin UI calls it for you.

## Next steps

- [Core concepts](concepts.md): tenants, flows, blocks, screens and versions.
- [Write your first flow](../flows/first-flow.md): build a flow block by block.
- [Admin UI overview](../console/overview.md): every page of the console.
- [Configuration](../reference/configuration.md): every environment variable and tenant setting.
- [Build and run from source](../develop/build-from-source.md): run the stack without Docker and change the code.
