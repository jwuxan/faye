---
status: in progress
date: 2026-10-31
decision-makers: Josh Wu
---

# One container, many processes

## Context and Problem Statement

[ADR 0019](0019-a-five-service-compose-stack.md) left Faye as five compose services (`hydra`, `faye`, `login`, `admin`, `demo`) from two images plus Hydra's. Running Faye means running compose, or reproducing its wiring by hand: five containers, a shared volume, one uid for every process that opens the database, internal URLs between services, and the start order (Hydra creates the database, Faye migrates it, the seed writes rows). The user asked (2026-10-31) for **one container that runs every process**, so that deploying Faye is one `docker run`, the way GitLab's Omnibus image and many self hosted products ship.

Charter principle 4 (out of the box: trying Faye locally takes minutes) and success criterion 1 (a team deploys Faye with Hydra the same day, without writing code) back this. Principle 7 (small and modular: each part has one job and can be replaced) and principle 3 (host neutral) constrain it: the processes stay separate programs with their own contracts, and Hydra stays a replaceable host, not part of Faye.

Facts checked while deciding (2026-10-31, on a Colima arm64 VM):

* The pinned Hydra image is Alpine and its `/usr/bin/hydra` (32 MB) links only against musl: `docker run --rm --entrypoint sh <hydra image> -c 'ldd /usr/bin/hydra'` prints `ld-musl-aarch64.so.1`. It can be copied into another Alpine image.
* `node:24-alpine` (v24.22.2) has `crypto.argon2Sync` and `node:sqlite`'s `DatabaseSync` (`docker run --rm node:24-alpine node -e ...`), so the seed runs on musl. This closes the open item in ADR 0019's More Information.
* Faye already runs on `eclipse-temurin:21-jre-alpine`, with sqlite-jdbc's musl native library (ADR 0019).
* None of the three web apps depends on a native module (no `sharp`, `argon2`, `better-sqlite3` in `web/apps/*/package.json`). Unverified: that Next 16.4.3's standalone output runs on musl; verify by starting each app's `server.js` in the new image.
* Docker's guidance is one concern per container, and it documents three ways to run several processes in one: a wrapper script with `wait -n`, shell job control, or a process manager such as supervisord ([Run multiple processes in a container](https://docs.docker.com/engine/containers/multi-service_container/)).
* GitLab's image is the Linux package inside a container, its services supervised by runit; GitLab calls it a "fat container", simple to start and upgrade, meant for smaller installs, and not recommended on Kubernetes ([Packages and images](https://docs.gitlab.com/administration/package_information/omnibus_packages/), [Omnibus architecture](https://docs.gitlab.com/omnibus/development/architecture/)).
* s6-overlay v3.2.3.2 (published 2026-07-16) is current; it ships s6-rc 0.7.0.0, which orders one shot and long running services by declared dependencies ([release](https://github.com/just-containers/s6-overlay/releases/tag/v3.2.3.2)).
* [ADR 0017](0017-one-sqlite-database.md) already ties Faye and Hydra to one SQLite file on one host. Separate containers buy no horizontal scaling today; they share a volume on the same machine either way.

## Decision Drivers

* One image, one `docker run`, one volume: a working sign in with no compose file and no wiring.
* No change to the engine, the stores, the web apps' code or the screen protocol. Only packaging, start scripts and configuration defaults change.
* Hydra stays a separate, unmodified process that can be switched off for an external authorization server (charter principle 3, and "not an OAuth2 provider").
* Product and demo stay apart (ADR 0019): the demo processes and their secrets are off unless asked for.
* Secure defaults out of the box: no fixed secrets in a shared image, Hydra's admin API and the stub not reachable from outside the container, the Admin UI still local only (ADR 0018).
* AGENTS.md rule 7: third party tools pinned by digest or checksum, nothing binary committed.
* `deploy/smoke.sh` keeps passing, with only its URLs and secret paths adjusted.

## Considered Options

1. Process supervision inside the container
   * A. s6-overlay v3 as PID 1. Services are directories under `/etc/s6-overlay/s6-rc.d/`: one shot `hydra-migrate`, long running `hydra`, `faye`, `login`, `admin`, and the demo's `ip-reputation`, `bootstrap` (one shot), `seed` (one shot) and `demo-client`. s6-rc starts them in dependency order, restarts a long running one that exits, stops them in reverse order on `SIGTERM`, reaps zombies, and drops each to its own uid with `s6-setuidgid`.
   * B. runit, as GitLab and Discourse use. Small and proven, but no one shot services and no dependency graph: ordering becomes `sv check` loops in run scripts.
   * C. supervisord. Familiar and in Docker's docs, but needs Python (tens of MB), orders only by priority, and has no readiness or dependency model.
   * D. tini plus a shell script, as `deploy/dev.sh` and the current `demo` command do: start everything, `wait -n`, exit when any process exits and let Docker's restart policy restart the container. No dependency. But one crashed Next server restarts the JVM and Hydra too, ordering is hand rolled polling, and shutdown order is not controlled.
   * E. A supervisor written in Java or Node. Rejected: code to own for a solved problem.
2. Base image
   * A. Alpine: `eclipse-temurin:21-jre-alpine` as the base, Node copied from `node:24-alpine` (plus `libstdc++`), Hydra copied from its pinned image, s6-overlay's tarballs downloaded and checked by SHA 256. Every binary already runs on musl (facts above) except Next, unverified.
   * B. Debian slim: `node:24-slim`, a glibc Temurin JRE copied in, Hydra's `linux_sqlite` release tarball by checksum (as `deploy/hydra-local.sh` does). Known to run Next; larger.
3. What the image runs
   * A. Every process, each switchable: `FAYE_SERVICES` lists what starts, default `hydra,faye,login,admin`; `FAYE_DEMO=true` adds the demo's four. Without `hydra` in the list, `FAYE_HYDRA_ADMIN_URL` points at an external one.
   * B. Everything always on, demo included.
   * C. Product only; the demo stays a separate container.
4. Ports
   * A. Keep each process on its port: Hydra public `4444`, Faye `8080`, Login UI `3000`, Admin UI `3002`, demo client `3001`. Hydra's admin API (`4445`) and the ip-reputation stub (`8000`) bind to `127.0.0.1` inside the container and are not exposed. The Admin UI is published only on the host's loopback (`-p 127.0.0.1:3002:3002`), as compose does today.
   * B. One public port behind a reverse proxy in the container (Caddy or nginx, as Omnibus bundles nginx), routing by path. Fewer ports, but Hydra's public paths, Faye's `/login` and `/consent` and the Login UI's routes would share one origin, which changes cookies, WebAuthn's origin (`FAYE_WEBAUTHN_ORIGINS`) and Hydra's issuer, and adds a component to every request. Deferred to its own ADR.
5. Secrets and configuration
   * A. On first boot a one shot `init` service creates `/var/lib/faye/secrets/` and, if not set in the environment, generates Hydra's `SECRETS_SYSTEM` and `FAYE_ADMIN_TOKEN` (32 random bytes each) into it, mode 600, and logs the token's path, as GitLab writes `initial_root_password`. Later boots reuse them. Public URLs derive from one setting, `FAYE_PUBLIC_HOST` (default `localhost`), and each can be overridden with the existing variables (`URLS_SELF_ISSUER`, `FAYE_LOGIN_UI_URL`, `FAYE_WEBAUTHN_RP_ID`, `FAYE_WEBAUTHN_ORIGINS`). Internal URLs (`FAYE_SERVER_URL`, `FAYE_HYDRA_ADMIN_URL`, `FAYE_IP_REPUTATION_URL`) default to `127.0.0.1`.
   * B. Keep compose's fixed development secrets as image defaults. Rejected: every container from a public image would share Hydra's system secret.
6. Compose and the image build
   * A. One `Dockerfile` at the repo root, multistage: it reuses the build stages of `server/Dockerfile` and `web/Dockerfile` and assembles the runtime image. `deploy/compose.yaml` becomes one service, `faye`, on one volume, kept for people who prefer compose. `server/Dockerfile` and `web/Dockerfile` remain for building each part alone.
   * B. Keep the five service compose stack as the default and add the single image beside it. Two topologies for `smoke.sh` and CI to cover.

## Decision Outcome

1A, 2A, 3A, 4A, 5A, 6A.

1A over 1D: D is the simpler choice and has no dependency, but out of the box means a crash in one process should not restart the others, and the start order (Hydra migrates and creates the file, Faye runs Flyway, then the seed writes rows) belongs in declared dependencies rather than polling loops. s6-overlay is a few MB of static binaries, the base of the widely used linuxserver.io images, and does exactly this. 1B lacks one shot services and dependencies; 1C brings Python for less.

2A because every binary in the image is already verified on musl except Next's standalone server, which has no native module; if it fails on musl, fall back to 2B and record that here.

Amended during implementation (2026-10-31): Next's standalone servers run on musl, so 2A holds, with a plainer base. The runtime stage is `alpine:3.24.3` (the Alpine of `node:24.22.2-alpine3.24`, so Node's `libstdc++` matches) instead of `eclipse-temurin:21-jre-alpine`, and the JRE is a `jlink` runtime of the modules `jdeps` finds in the boot jar, built on `eclipse-temurin:21.0.13_9-jdk-alpine`. Every base image is pinned by exact version tag and index digest. Node is 24.22.2: the Node.js security release of 2026-10-21 fixed a Brotli decoder out of bounds write that can lead to remote code execution (CVE-2026-111497) and TLS hostname bypasses in 24.x, so no image may carry a 24.x older than 24.22.1 ([advisory](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases)).

3A keeps the product and the demo apart (ADR 0019) and keeps Hydra replaceable: the same image can run Faye alone against another authorization server, or be split back into one process per container on a platform that wants that, by setting `FAYE_SERVICES` per container.

### Layout of the image

| Path | What |
|---|---|
| `/init` | s6-overlay's PID 1 (the image's `ENTRYPOINT`). |
| `/etc/s6-overlay/s6-rc.d/` | One directory per service, with `type`, `run` or `up`, and `dependencies.d/`. A service not in `FAYE_SERVICES` is removed from the `user` bundle by `S6_STAGE2_HOOK`, which s6-overlay runs "to dynamically patch the service database at run-time right before it is compiled and run" ([README](https://github.com/just-containers/s6-overlay/blob/v3.2.3.2/README.md)). |
| `/opt/faye/` | `faye.jar`, the three apps' standalone output, `deploy/*.mjs`, `bin/hydra`. |
| `/var/lib/faye/` | The one volume: `faye.db` (ADR 0017), `secrets/` (generated, mode 700), `demo/` (the demo's users.csv and clients.jsonl, mode 700, owned by the demo's uid). |

Dependencies (`A` → `B`: B starts once A is up or done): `init` → `hydra-migrate` → `hydra` → `faye` → `login`, `admin`; with the demo: `hydra` → `bootstrap`, `faye` → `seed`, `ip-reputation` → `faye`, and `bootstrap` → `demo-client`. `faye` is ready when its port answers; `hydra` when `/health/ready` on its admin port does.

Users: PID 1 runs as root only to drop each service to its own uid. `hydra` and `faye` run as `100:101` with umask 007 so each can write the WAL files the other creates (ADR 0017, 0019). `login` and `admin` run as an unprivileged uid with no access to `/var/lib/faye`; they never open the database. The demo's processes run as a third uid in group 101 (the seed writes rows), and only that uid can read `/var/lib/faye/demo/`, which keeps ADR 0019's rule that the generated passwords and client secrets are out of Faye's reach.

Hydra's `--dev` flag (plain http issuer) is passed only when the issuer URL is `http://localhost` or `http://127.0.0.1`; any other http issuer stops the container with an error asking for https.

Logs: every service writes to the container's stdout and stderr, each line prefixed with the service name, as `deploy/dev.sh` does. `docker logs` is the one log.

Health: `HEALTHCHECK` passes when every started long running service is up (`s6-svstat`) and Faye and Hydra answer. A one shot that fails (a migration) stops the container (`S6_BEHAVIOUR_IF_STAGE2_FAILS=2`) instead of serving on a half migrated database.

Upgrade: pull the new tag and recreate the container on the same volume; `hydra-migrate` and Flyway run on start, as today.

### Footprint

The image is the demo of the core runtime, so it is kept small. Defaults, each measured before it went in:

* Faye's JVM runs with `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx128m -XX:+ExitOnOutOfMemoryError`; `FAYE_JAVA_OPTS` replaces the whole set. The default JVM on a 6 GB host picks G1, C2 and a 1.4 GB heap cap. With these flags Faye's container used 174 MiB idle and 235 MiB after `smoke.sh` (200 and 244 before). After a full GC the live heap is about 39 MiB. 128 MiB leaves room for Argon2 (16 MiB per verification) and a 16 MiB hook sandbox running at the same time. C1 only means slower peak throughput, a trade that suits small installs. `smoke.sh`'s preview iteration took the same 3 s and the hook deadlines still hold.
* Each Node server runs with `--max-semi-space-size=1`. On the Login UI under 300 requests for a flow page (an unknown flow id, so the server renders its error screen without reaching Faye), anonymous RSS fell from 132 to 141 MiB down to 90 to 93 MiB, with the same 1.23 s total. `--optimize-for-size` and `--lite-mode` saved about as much or slightly more, but are rejected: V8 refuses them in `NODE_OPTIONS`, and `--lite-mode` turns the JIT off.
* Identical files across the three apps' standalone output are hardlinked in the build and carried through a tar bind mount, since `COPY --from` keeps only some of the links. `/opt/faye/web` goes from 125 MB to 49 MB.
* The `jlink` JRE is 59 MB, against the 287 MB `eclipse-temurin:21-jre-alpine` image.

Measured on 2026-10-31 (Colima, arm64, 4 CPUs, 6 GB). Memory is the sum of `docker stats` MemUsage over the stack's containers (cgroup usage less inactive file cache). It was sampled 30 s after every endpoint answered (idle) and 10 s after a full `deploy/smoke.sh` on a fresh volume, using the same script for both topologies:

| | Five services (ADR 0019) | One image (this ADR) |
|---|---|---|
| Images, disk / content | 961 MB / 256 MB (`faye-web` 519, `faye` 384, Hydra 58) | 519 MB / 170 MB |
| Memory with the demo, idle | 357 MiB | 325 to 327 MiB (two runs) |
| Memory with the demo, after `smoke.sh` | 479 MiB | 413 to 421 MiB (two runs) |
| Memory of the product only (`hydra,faye,login,admin`), idle | 300 MiB (those four containers) | 267 MiB |

The processes after `smoke.sh`, by RSS: Faye's JVM 258 MiB, each Next server 98 to 108 MiB, of which about 59 MiB is the Node binary's file pages shared between them, Hydra 45 MiB, the ip-reputation stub 64 MiB, and each of the six log prefixers under 1 MiB. Most of what remains is the JVM, where Metaspace takes 57 MiB and the heap up to 128, and the Next servers' own heaps. A runtime of only Hydra, Faye and the Login UI (`FAYE_SERVICES=hydra,faye,login`) leaves out the Admin UI's 88 MiB.

Considered and not taken:

* Java 25 with `-XX:+UseCompactObjectHeaders`: Faye's anonymous RSS went from 164 to 155 MiB after 100 sign in starts. That is too little for a runtime change and its own ADR.
* AppCDS or an AOT cache: Faye already starts in 2.2 s.
* Virtual threads, a smaller `-Xss`, and capping Metaspace or the code cache: Native Memory Tracking showed thread stacks committing 4 MiB in total, so there was nothing to win, and a cap risks an OutOfMemoryError.
* `-Xmx96m` or less: it would fit the idle live set, but concurrent Argon2 verifications could exhaust the heap.
* Merging the three Next apps into one Node process: it changes the web apps, which this record rules out.

The out of the box command, to try it:

```sh
docker run -d --name faye -v faye-data:/var/lib/faye -e FAYE_DEMO=true \
  -p 4444:4444 -p 8080:8080 -p 3000:3000 -p 3001:3001 -p 127.0.0.1:3002:3002 faye
```

This supersedes ADR 0019's arrangement of services (not ADR 0017's one database, nor ADR 0020's migrations). When this record moves to `in progress`, set ADR 0019's status to `superseded by 0023` and update `AGENTS.md`'s Layout and Commands, `README.md`, and `docs/roadmap/architecture.md` in the same change.

### Consequences

* Good, because deploying Faye is one image, one volume and one command, with no fixed secrets and nothing to wire.
* Good, because Hydra's admin API and the ip-reputation stub stop being published at all; today compose publishes `4445` to the host.
* Good, because the web apps no longer run as the database's uid, and the image carries one copy of Node, one JRE and one Hydra instead of three images' worth.
* Good, because `FAYE_SERVICES` keeps every part switchable, so Faye against another authorization server, or one process per container, stays a configuration change.
* Bad, because one container is one failure and upgrade unit: recreating it restarts every process, and the Login UI cannot be scaled apart from Faye. With one SQLite file on one host (ADR 0017) that was already true in practice.
* Bad, because PID 1 runs as root to drop privileges per service. Running the image with `--user` gives up the per service uids (and the demo secrets' separation); document it as unsupported with the demo on.
* Bad, because s6-overlay is a new dependency with its own configuration style, and a reader has to learn s6-rc's directory layout to change the start order.
* Bad, because Docker's one concern per container guidance and GitLab's own advice say a fat container fits small installs, not Kubernetes. A later Helm chart would run the same image once per process via `FAYE_SERVICES`.
* Neutral: `deploy/dev.sh` and `deploy/hydra-local.sh` are unchanged; they already run every process on one host.
* Good, because the image is 519 MB on disk and 170 MB of content, against 961 MB and 256 MB for the three images before, and the stack uses 9 to 14% less memory (Footprint above).
* Bad, because a secret passed through `docker run -e` (`FAYE_ADMIN_TOKEN`, `SECRETS_SYSTEM`) goes into s6-overlay's container environment, where every service can read it. Secrets generated on first boot are read from `/var/lib/faye/secrets/` only by the run scripts of the services that need them. Anything passed with `-e` is also visible to `docker inspect`.
* Neutral: Hydra maps environment variables onto its configuration, so the demo's `SECRETS` and `SECRETS_DIR` would land under its `secrets` key and break it. Hydra's run script unsets both.
* Neutral: Hydra creates the database file `0640`, so `hydra-migrate` sets it to `0660` for group 101. Verified: the seed (uid 103, group 101), Faye and Hydra share the file and its WAL, and `smoke.sh`'s single database step passes on a fresh volume.

Open gaps (why this record is `in progress`):

* Built and run on arm64 only. The x86_64 path, including s6-overlay's x86_64 checksum, is unverified until CI's `e2e` job runs on `ubuntu-latest`.
* CI's `e2e` job has been changed but has not run on GitHub yet.

### Confirmation

* On a fresh volume: `docker build -t faye .`, the `docker run` above, then `docker ps` shows one container, `docker exec faye s6-rc -a list` lists the started services, and `deploy/smoke.sh` (pointed at `/var/lib/faye/demo/` for the generated secrets) prints every `ok` line through `single database ok`.
* `curl -s http://localhost:4445/health/ready` from the host fails (admin API not exposed), and `docker exec faye wget -qO- http://127.0.0.1:4445/health/ready` succeeds.
* `docker run` without `FAYE_DEMO` starts no demo service and creates no `/var/lib/faye/demo/`.
* `docker restart faye` keeps the generated secrets, the admin token and every enrollment.
* `docker exec faye s6-svc -k /run/service/login` restarts only the Login UI; Faye's and Hydra's uptime in `s6-svstat` are unchanged.
* CI's `e2e` job builds the image and runs the same checks in place of the five service stack.
* Footprint: on a fresh volume, run the stack with the demo on, wait until every port answers, then wait 30 s. `docker stats --no-stream faye` should show about 325 MiB, and about 415 MiB after `deploy/smoke.sh`. `docker image ls faye` should show about 520 MB. `docker run --rm --entrypoint node faye -v` must print v24.22.1 or later.

Verified on 2026-10-31, on arm64, fresh volumes: every check above except CI's.

## More Information

Implementation order, each step leaving CI green:

1. Root `Dockerfile` on Alpine with s6-overlay pinned by checksum, the product services and `init`; verify Next on musl (or switch to 2B and amend this record).
2. Demo services behind `FAYE_DEMO`, their uid and `/var/lib/faye/demo/`.
3. `deploy/compose.yaml` down to one service; `smoke.sh` and CI's wait loop updated (the loop reads Hydra's readiness through `docker exec`, since `4445` is no longer published).
4. Docs: `AGENTS.md`, `README.md`, `docs/roadmap/architecture.md`, ADR 0019's status.

Out of scope: one public port behind a reverse proxy (4B), TLS termination in the image, and a Helm chart; each gets its own record if wanted.
