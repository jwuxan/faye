---
description: The errors and messages that people meet most often when they run Faye, build flows or sign in, with the cause and the fix for each.
---

# Troubleshooting

This page lists the problems that people meet most often with Faye, one section per symptom. It is for anyone who runs the stack, builds flows in the Admin UI, or develops Faye. Each section shows what you see, with the message exactly as Faye prints it, then why it happens and how to fix it. Each section links to the page that explains the topic in depth.

## Find your symptom

| What you see | Section |
|---|---|
| The container stays unhealthy, or a sign in never finishes | [The container never becomes healthy](#the-container-never-becomes-healthy-or-sign-in-hangs) |
| `users.csv` does not exist | [users.csv is missing](#userscsv-is-missing) |
| `make setup` says the JDK is too old | [make setup refuses the JDK](#make-setup-refuses-the-jdk) |
| **Admin API request failed** on **Choose a tenant** | [Admin API request failed](#admin-api-request-failed) |
| `FAYE_ADMIN_SOCKET is not set` | [The Admin UI cannot find the admin socket](#the-admin-ui-cannot-find-the-admin-socket) |
| **Previews are disabled on this server** | [Previews are disabled](#previews-are-disabled) |
| 404 for `/admin/...` on port 8080 | [The admin API answers 404 on port 8080](#the-admin-api-answers-404-on-port-8080) |
| `Missing content when trying to generate the content hash for static asset` | [The Admin UI dev server cannot hash a static asset](#the-admin-ui-dev-server-cannot-hash-a-static-asset) |
| `handler 'sms_code.verify' is not enabled for this tenant` | [A code block is refused](#a-code-block-is-refused) |
| `a path succeeds without verifying a credential` | [A path succeeds without a credential](#a-path-succeeds-without-a-credential) |
| `entry 'login' has more than 10000 paths` | [The flow has too many paths](#the-flow-has-too-many-paths) |
| **Matches no enumerated path** in a preview | [A preview matches no path](#a-preview-matches-no-path) |
| "This sign in timed out" or "This sign in has expired" | [The sign in timed out or expired](#the-sign-in-timed-out-or-expired) |
| "This page is out of date" | [The page is out of date](#the-page-is-out-of-date) |
| A passkey fails on a host other than `localhost` | [Passkeys fail outside localhost](#passkeys-fail-outside-localhost) |
| `smoke.sh` fails on its second run | [The smoke test fails on a second run](#the-smoke-test-fails-on-a-second-run) |

## Run the stack

### The container never becomes healthy, or sign in hangs

**What you see.** `docker compose -f deploy/compose.yaml ps` shows `(unhealthy)` for the `faye` container, or it shows `(health: starting)` for a long time. Or a sign in in the browser waits and never shows the next screen.

To see what the health check reports, run this command:

```bash
docker inspect --format '{{json .State.Health}}' faye
```

The [health check](../../deploy/image/healthcheck) prints one line for each part that fails. The lines are `<service> is not up`, `faye is not answering on :8080`, `faye has no admin socket at <path>` and `hydra is not ready`.

**Why.** The usual cause is an old database from an earlier build of the image. The `data` volume keeps the database after the container stops, so the next `make up` uses it again.

First make sure that the stack is not only slow. On the first start, the health check waits 240 seconds before it counts failures, and the seed hashes 1000 passwords. Run `docker logs -f faye` and wait for the line that starts with `[seed] seeded 1000 users`.

**Fix.** Delete the volume and start again:

```bash
docker compose -f deploy/compose.yaml down -v && make up
```

> [!WARNING]
> `down -v` deletes the whole volume: every account, every published version, the admin token and Hydra's secret. The next start seeds new accounts with new TOTP secrets.

Read more in the [Quickstart](../start/quickstart.md) and in [Configuration](configuration.md#where-state-lives).

### users.csv is missing

**What you see.**

```text
$ docker exec faye cat /var/lib/faye/demo/users.csv
cat: can't open '/var/lib/faye/demo/users.csv': No such file or directory
```

**Why.** The [seed](../../deploy/directory/seed.mjs) writes `users.csv`. The seed starts only when `FAYE_DEMO` is `true`. It waits until Faye has migrated the database, and then it hashes 1000 passwords. Until it finishes, the file does not exist.

**Fix.**

- If the stack started a short time ago, wait. The seed prints `[seed] seeded 1000 users into /var/lib/faye/faye.db; their passwords are in /var/lib/faye/demo/users.csv` when it is done. Run `docker logs -f faye` to watch for it.
- If you run your own `docker run` command, set `-e FAYE_DEMO=true`. Without it, the container has no seed and no demo accounts.
- If the log shows `[seed] /var/lib/faye/faye.db: Faye has not migrated the schema; start Faye first`, Faye did not start within 180 seconds. Look at the log for Faye's own error. If you cannot find the cause, [delete the volume](#the-container-never-becomes-healthy-or-sign-in-hangs) and start again.

If you run the stack with `make dev`, the file is `~/.local/state/faye/secrets/users.csv`. See [Build and run from source](../develop/build-from-source.md#without-docker).

### make setup refuses the JDK

**What you see.** `make setup` stops with one of these messages:

```text
JDK 21+ not found; install one or pass JAVA_HOME=...
```

```text
JDK at /usr/lib/jvm/java-17-openjdk-amd64 is 17.0.12, need 21+
```

**Why.** Faye needs a Java Development Kit (JDK) 21 or later. On macOS, the [Makefile](../../Makefile) finds a JDK 21 with `/usr/libexec/java_home`. On other systems, the Makefile uses your `JAVA_HOME`. The first message means that `JAVA_HOME` is not set, or that it has no `bin/java`. The second message means that `JAVA_HOME` points to an older JDK.

**Fix.** Install a JDK 21 or later. Then pass its folder to `make`:

```bash
make setup JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

If you call `./gradlew` directly, set `JAVA_HOME` the same way. See [Pick a JDK](../develop/build-from-source.md#pick-a-jdk).

## Use the Admin UI

### Admin API request failed

**What you see.** The Admin UI opens on **Choose a tenant** and shows a red box instead of the tenants:

```text
Admin API request failed (401)
Check that FAYE_ADMIN_TOKEN matches on the server and in the admin app.
```

**Why.** The Admin UI calls the Faye server's admin API over a Unix socket, with the bearer token `FAYE_ADMIN_TOKEN`. The server refused the call. The status code in brackets says why:

| Status | Cause |
|---|---|
| `401` | The Admin UI sent a token that is not the server's token. |
| `404` | The server has no `FAYE_ADMIN_TOKEN`, so its admin API is off. |

**Fix.** Give the Faye server and the Admin UI the same `FAYE_ADMIN_TOKEN`, and restart both. In the image, the first boot generates one token for both processes, and `make dev` uses `dev-admin-token` for both. A mismatch usually comes from a token that you set for only one process.

See [Keep the Admin UI on your own machine](../console/overview.md#keep-the-admin-ui-on-your-own-machine) and [Configuration](configuration.md#admin-ui).

### The Admin UI cannot find the admin socket

**What you see.** You run the Admin UI on its own, for example with `pnpm --filter @faye/admin dev`. The first page fails with an error instead of **Choose a tenant**, and the dev server prints:

```text
Error: FAYE_ADMIN_SOCKET is not set
```

If the variable is set but nothing listens at that path, the error is from Node.js instead, such as `connect ENOENT /tmp/faye-run/admin.sock`.

**Why.** The Admin UI reaches the admin API only through the Unix socket that `FAYE_ADMIN_SOCKET` names. The variable has no default. The Faye server opens the socket only when its own `FAYE_ADMIN_SOCKET` and `FAYE_ADMIN_TOKEN` are both set ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)).

**Fix.** Start the server and the Admin UI with the same two values:

```bash
# Terminal 1: the server, with the admin API on
mkdir -p -m 700 /tmp/faye-run
cd apps/server
FAYE_ADMIN_SOCKET=/tmp/faye-run/admin.sock FAYE_ADMIN_TOKEN=dev-admin-token ./gradlew :faye-app:bootRun
```

```bash
# Terminal 2: the Admin UI, with the same two values
cd apps/web
FAYE_ADMIN_SOCKET=/tmp/faye-run/admin.sock FAYE_ADMIN_TOKEN=dev-admin-token pnpm --filter @faye/admin dev
```

`make dev` sets both values for you. See [Piece by piece](../develop/build-from-source.md#piece-by-piece).

### Previews are disabled

**What you see.** The **Preview** tab of **Login flow** shows:

```text
Previews are disabled on this server. Start Faye with FAYE_PREVIEW_ENABLED=true to run drafts with the preview-acme client.
```

**Why.** `FAYE_PREVIEW_ENABLED` is `false` by default. The image sets it to `true` only when `FAYE_DEMO` is `true`, and `make dev` sets it to `true`. A server that you start with `./gradlew :faye-app:bootRun` alone has previews off.

**Fix.** Restart the Faye server with `FAYE_PREVIEW_ENABLED=true`. A preview also needs the tenant's preview client, `preview-<tenant>`, in the authorization server, and the demo client. The demo's [`bootstrap.mjs`](../../deploy/bootstrap.mjs) registers the preview clients. See [Preview a draft](../console/preview.md#before-you-start).

### The admin API answers 404 on port 8080

**What you see.** A request such as `curl http://localhost:8080/admin/tenants` gets `404` with an empty body.

**Why.** This is by design. Faye serves the admin API only on its Unix socket, never on its TCP port. On port 8080, every path under `/admin` is a 404 ([ADR 0028](../adr/0028-admin-api-on-a-unix-domain-socket.md)).

**Fix.** Use the Admin UI at http://localhost:3002. The Admin UI calls the admin API over the socket for you. `smoke.sh` does the same.

### The Admin UI dev server cannot hash a static asset

**What you see.** The Admin UI dev server shows a Turbopack error, and the page does not load:

```text
Missing content when trying to generate the content hash for static asset
```

**Why.** In dev mode, Next.js builds pages with Turbopack. Turbopack computes a hash of each static file that a page loads. The Admin UI loads its two fonts from files in `node_modules`: Inter from `@fontsource-variable/inter`, and Paper Mono from the `paper-mono` package. If `node_modules` lost a package or its files, Turbopack has no content to hash.

**Fix.** Stop the dev server. Reinstall the web dependencies, then start the dev server again:

```bash
cd apps/web
pnpm install
```

## Check and publish flows

### A code block is refused

**What you see.** **Problems** in the flow editor shows one of these errors, and you cannot publish the draft:

```text
handler 'email_code.verify' is not enabled for this tenant
handler 'sms_code.verify' is not enabled for this tenant
handler 'phone.enroll' is not enabled for this tenant
```

In the **Blocks** tab, **Add block** disables **Email code**, **Text message code** or **Add phone**, with this reason:

```text
No email delivery for this tenant; set faye.tenants.<id>.delivery.email
No text message delivery for this tenant; set faye.tenants.<id>.delivery.sms
```

If the tenant's flow in `application.yaml` uses these blocks, the server does not start. Its error starts with `tenant <id> flow fails the checker:` or `tenant <id> current version <hash> fails the checker:`, and lists the same findings.

**Why.** Faye sends a one time code through a delivery channel: email or text message. A channel is on for a tenant when the tenant has a webhook for it, or when `FAYE_DEMO_OUTBOX` names a folder. If neither exists, the channel is off. Faye then removes the channel's handlers from the tenant's allowed handlers, so the checker refuses every flow that calls them ([ADR 0032](../adr/0032-delivered-one-time-codes-and-qr-sign-in.md)). The text message channel serves both **Text message code** and **Add phone**.

The same error also appears when the tenant's **Handlers** allowlist in **General** settings leaves the handler out.

**Fix.** Choose one:

- Add a webhook for the channel under `faye.tenants.<id>.delivery` in `application.yaml`, then restart the server. See [Keys of a tenant](configuration.md#keys-of-a-tenant).
- For local tests, set `FAYE_DEMO_OUTBOX` to a folder. Faye then writes each code to a file in that folder instead of sending it.
- Remove the code blocks from the flow.

If the channel is on, check the tenant's **Handlers** in [General settings](../console/tenant-settings.md#handlers-and-screens).

### A path succeeds without a credential

**What you see.** The checker refuses the flow with an error like this one:

```text
tenant.yaml:8:11: a path succeeds without verifying a credential: identify > credential > (condition at 8 false) > succeed with no credential
```

**Why.** The checker lists every path through the flow. On at least one path, the person reaches the end of the sign in, but no credential handler verified anything. Faye never signs anyone in without a credential. In the example, the `credential` step has an `if:` on line 8. When the condition is false, the step is skipped, and nothing else asks for a credential.

**Fix.** Read the path in the message. It names each step that the path passed, `/option` after a choice, and each condition that the path assumed false. Then do one of these:

- Remove the condition that skips the credential.
- Add a credential block to the branch that has none.

The **Paths** tab shows the same path in the graph. See [Check every path in the Paths tab](../console/flow-editor.md#check-every-path-in-the-paths-tab) and [Checker errors](dsl.md#checker-errors).

### The flow has too many paths

**What you see.**

```text
entry 'login' has more than 10000 paths or a path longer than 10000 steps; split it
```

**Why.** The checker proves its rules on every path. It stops at 10,000 paths in one entry, rather than check only some of them ([ADR 0037](../adr/0037-hard-limits-on-flows-and-hooks.md)). Paths multiply with every choice, condition and attempt. A step with a hook also doubles the paths of later conditions that read `vars`, because the hook may set any value.

**Fix.** Lower the number of paths:

- Lower the attempts of a block that does not need many, with `with: { attempts: N }`. The shipped `acme.yaml` sets `with: { attempts: 1 }` on `add-phone` for this reason.
- Remove an option that few people use.
- Remove a condition that does not change the result.

See [Limits](dsl.md#limits).

### A preview matches no path

**What you see.** After a preview run ends, the **Trace** section of the **Preview** tab shows, in red:

```text
Matches no enumerated path: the engine and the checker disagree.
```

**Why.** When a run ends, the server looks for the paths that the checker listed and that made the same choices and calls as the run. It found none. The checker is meant to list every way that a run can go, so the engine did something that the checker did not predict.

There is one known harmless cause. The server lists the paths under the tenant's settings at the time that the console asks, not at the time of the run. If you changed the tenant's settings during the run, a correct run can show as a mismatch.

**Fix.** Run the preview again without changing the settings. If the message comes back, treat it as a bug in Faye, not in your flow. Report it with the flow file and the trace. See [Check the run against the paths](../console/preview.md#check-the-run-against-the-paths).

## Sign in

### The sign in timed out or expired

**What you see.** The Login UI shows one of these messages, on its error page or under the form:

| Message | Error code |
|---|---|
| This sign in timed out. Go back to the app and start again. | `flow_expired` |
| This sign in has expired. Go back to the app and try again. | `flow_not_found` or `flow_finished` |

**Why.**

- `flow_expired`: the sign in passed one of the tenant's time limits. **Time limit** counts from the start of the sign in. **Idle timeout** counts from the last answer.
- `flow_not_found`: Faye does not have this sign in for this browser. The sign in is older than `faye.flow-ttl` (30 minutes in the shipped configuration), or another browser opened the link. Faye answers only the browser that started the sign in, by a cookie.
- `flow_finished`: the sign in already ended.

**Fix.** Start a new sign in from the application. When the demo runs, the error page offers **Start now** and starts a new sign in after 5 seconds. If people often run out of time, raise the limits in the flow's **Settings** tab. A limit can never be longer than `faye.flow-ttl`. See [Time limits](../console/flow-editor.md#time-limits) and [Error messages](screens.md#error-messages).

### The page is out of date

**What you see.** After the person submits a screen, the Login UI shows:

```text
This page is out of date. Reload to continue.
```

**Why.** Each screen carries a single use value, the nonce. Faye refused the answer because the flow no longer waits on that screen (`stale_nonce`), or because another answer was saved first (`conflict`). This happens when the same sign in is open in two tabs and the person answered in the other tab. It also happens when two answers to the same screen arrive at the same time. The refused answer changes nothing.

**Fix.** Reload the page. The Login UI then shows the screen that the sign in waits on now. See [How the engine works](../understand/engine.md#resume-on-submit).

### Passkeys fail outside localhost

**What you see.** You serve the Login UI on a host other than `localhost`, or you open it at `127.0.0.1`. A passkey then fails with one of these messages:

```text
The passkey request was cancelled. Try again.
```

```text
That didn't work. Check what you entered and try again.
```

**Why.** A passkey belongs to one relying party ID: a domain. The browser refuses the request when the page's host does not fit that ID, or when the page is not a secure context: `https`, or `http` on `localhost`. The browser also refuses an IP address such as `127.0.0.1`. Then the Login UI shows the first message. Faye's server also checks the relying party ID and the origin of the Login UI. If they do not match `FAYE_WEBAUTHN_RP_ID` and `FAYE_WEBAUTHN_ORIGINS`, the passkey fails as a wrong credential, with the second message.

The defaults are `localhost` and `http://localhost:3000`. A passkey that someone created on `localhost` does not work on another host.

**Fix.**

- For local use, open every URL at `localhost`, not at `127.0.0.1`.
- For another host in the image, serve the Login UI over `https`. Browsers allow passkeys only on `localhost` or over `https`. Then set `FAYE_PUBLIC_HOST` to the host, `FAYE_WEBAUTHN_ORIGINS` to the `https` origin of the Login UI, and `URLS_SELF_ISSUER` to an `https` URL. The image refuses a plain `http` issuer on any host other than `localhost`.

See [Image and container](configuration.md#image-and-container) and [Server](configuration.md#server).

## Test the stack

### The smoke test fails on a second run

**What you see.** `deploy/smoke.sh` stops at step 4 with a message like this one:

```text
4. step up: acr_values=faye:2fa makes an account without TOTP enroll it (tenant bank)
expected screen otp_enroll, got: {...}
```

**Why.** Step 4 enrolls an authenticator app for the first `bank` account that has none. The script expects the setup screen, `otp_enroll`. On a second run against the same data, that account already has the app, so the flow asks for a code instead.

**Fix.** Run `smoke.sh` against a fresh stack:

```bash
docker compose -f deploy/compose.yaml down -v && make up
```

Wait until the container is healthy, then run `deploy/smoke.sh` again. For a stack from `make dev`, stop it, delete `deploy/.data/` and `~/.local/state/faye/secrets/`, and start it again. See [The smoke test](../develop/build-from-source.md#the-smoke-test).

## Next steps

- [Quickstart](../start/quickstart.md): run the stack and sign in.
- [Configuration](configuration.md): every environment variable and its default.
- [Flow DSL reference](dsl.md#messages): every compiler and checker message.
- [Build and run from source](../develop/build-from-source.md): run each process yourself.
