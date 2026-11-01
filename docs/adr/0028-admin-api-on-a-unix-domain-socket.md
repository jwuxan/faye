---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# The admin API on a Unix domain socket only

## Context and Problem Statement

Before this record the admin API (`/admin/**`: drafts, validate, publish, roll back, hooks test runs, settings, preview scenarios, seeded accounts, the trace feed) was served on Faye's public TCP port, `:8080`, beside the screen API, guarded only by a bearer token (`FAYE_ADMIN_TOKEN`). Anything that could reach `:8080` could try the token: in compose that is the host network, since `:8080` is published for Hydra's login and consent redirects. Inside the container any process, and any request forgery in a component that fetches URLs on loopback, could reach it too. A leaked token ([decisions-and-risks.md](../roadmap/decisions-and-risks.md), "the admin token leaks through the Admin UI") gave the whole admin API to the network.

The only caller is the Admin UI's server, on the same machine ([ADR 0018](0018-two-tenants-and-an-open-local-console.md), [ADR 0023](0023-one-container-many-processes.md)). How should Faye expose the admin API so that no network peer can reach it, and the Admin UI still can?

## Decision Drivers

* Charter: secure by default; the admin surface is the most powerful one Faye has (it publishes flows).
* No network peer, and no loopback request forgery, should reach the admin API at all, token or not.
* Keep HTTP and the existing API: the controllers, the token check, the Admin UI's proxy and the trace feed (server sent events) stay as they are.
* No new dependency on either side (AGENTS.md, simplicity).

## Considered Options

1. **A. A second Tomcat connector on a Unix domain socket; requests split by connector.**
2. **B. A second TCP connector bound to `127.0.0.1`, admin paths only there.**
3. **C. Keep `:8080` and the token** (the status quo).

## Decision Outcome

Chosen: **A.** With `faye.admin-socket` (`FAYE_ADMIN_SOCKET`) set, Faye adds a Tomcat NIO connector bound to that path (Tomcat's `unixDomainSocketPath`, Java 16+ `UnixDomainSocketAddress`), file mode `rw-rw----`, and serves HTTP/1.1 on it. An engine valve (`AdminSocket.Split`) runs before any servlet and splits by the connector a request arrived on: `/admin` and below (after Tomcat decodes and normalizes the path, so `/%61dmin`, `/api/../admin`, `//admin` and `/admin;x` are the same path) only from the socket; every other path only from TCP. The other way round is a bare 404. With no socket configured the admin API is not served at all; the bearer token is still checked on the socket, so a process that can open the socket still needs it.

* **Who may connect** is the socket's directory. In the image, `init` creates `/run/faye` owned by faye (uid 100) and the Admin UI's group, mode `2750`: faye creates the socket, setgid gives it that group. The Admin UI now runs as its own uid and gid, 104, instead of sharing 102 with the Login UI, so the Login UI and the demo's services (103) cannot enter the directory. Hydra runs as faye's uid (100, ADR 0023, for the shared database file) and so can; it can already write Faye's tables in that file, so the socket gives it nothing new. `deploy/dev.sh` puts it in `deploy/.data/run`, mode 700.
* **The Admin UI** calls it with `node:http` and `socketPath` (`web/apps/admin/lib/faye.ts`), streaming the response so the trace feed passes through as before, and `connection()` keeps each call out of prerendering. `FAYE_SERVER_URL` is no longer read by the Admin UI.
* **A stale socket** (a process killed without shutdown) is deleted before binding; Tomcat deletes the socket on a clean shutdown.
* **`deploy/smoke.sh`** makes every admin call through the Admin UI's proxy, as the browser does, and no longer reads the token.

### Consequences

* Good, because the admin API has no network address: no host, container peer or loopback request can reach it, whatever the token.
* Good, because access control is the filesystem's, which the image already uses for secrets (ADR 0023), and the token stays as a second check.
* Good, because nothing above the transport changes: the same controllers, paths, status codes, token check and proxy.
* Bad, because running faye and admin in separate containers (`FAYE_SERVICES`, ADR 0023) now needs a shared volume for the socket's directory, with matching uids and gids. Unverified: not tried in this change.
* Bad, because `sun_path` is short (104 bytes on macOS, 108 on Linux), so a deep `FAYE_ADMIN_SOCKET` path fails at startup.
* Bad, because a second Faye started on the same socket path deletes the first one's socket.
* Bad, because Hydra, sharing faye's uid, can open the socket; separating them needs the database file shared by group instead (not done here).
* Neutral: Windows is not a target; Java and Tomcat support `AF_UNIX` there, unverified for this setup.

### Confirmation

1. `cd server && ./gradlew :faye-app:test --tests faye.app.AdminSocketTest`: through a real Tomcat, the admin API answers on the socket (200, 401 with a wrong token), the socket's mode is `rw-rw----`, every spelling of an admin path is 404 on TCP, and the socket is 404 for every other path.
2. In the image (`docker run -e FAYE_DEMO=true`, no ports published): `/run/faye` is `drwxr-s--- 100:104` and `admin.sock` `srw-rw---- 100:104`; `wget http://127.0.0.1:8080/admin/tenants` is 404; the Admin UI lists the tenants and seeds a preview account through its proxy; uids 102 (Login UI) and 103 (demo) cannot enter `/run/faye`, 104 can open the socket.
3. `deploy/smoke.sh` against the compose stack: `:8080/admin/tenants` is 404, and every admin call (publish, roll back, drafts, settings, preview, the trace feed) succeeds through the Admin UI. The image's healthcheck fails while the socket is missing.

All three ran against this change: item 3 on a fresh `faye-uds` container of this image, from a container sharing its network namespace (the host ports were taken), not yet in CI. Status stays `in progress` until a human accepts it.

## Pros and Cons of the Options

### A. Unix domain socket

* Good, because unreachable from any network, including loopback.
* Good, because who may connect is set by file ownership and mode, per process user.
* Bad, because a shared directory is needed when the two processes do not share a filesystem.

### B. Loopback TCP connector

* Good, because nothing changes for clients but the port.
* Bad, because every process in the network namespace can connect, whichever user it runs as: in the image that is Hydra, the Login UI and the demo's services, and any server side request forgery to `127.0.0.1`.
* Bad, because `-p` or `network_mode: host` mistakes publish it.

### C. Status quo

* Good, because no change.
* Bad, because the admin API stays on the published port, one token away from the network.
