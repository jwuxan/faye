---
status: superseded by 0023
date: 2026-10-31
decision-makers: Josh Wu
---

# A five service compose stack

## Context and Problem Statement

`deploy/compose.yaml` ran nine services: `seed`, `hydra-migrate`, `hydra`, `bootstrap`, `ip-reputation`, `faye`, `login`, `demo-client` and `admin`. Each web app built its own image from the whole pnpm workspace, dev dependencies included (about 1.55 GB each), and Faye ran on the Ubuntu JRE (562 MB). The user asked (2026-10-31) for a light deployment: only Faye, Hydra and what is necessary. Faye, its Login UI (the runtime the screens render in) and the Admin UI are the product; everything else is only there to demonstrate it.

Facts checked while deciding:

* The pinned Hydra image is Alpine (it has `sh`), runs as `ory` (100:101), and its `/var/lib/sqlite` is owned by 100:101 (`docker run --rm --entrypoint sh <image> -c 'ls -ld /var/lib/sqlite'`, 2026-10-31). A new named volume mounted there first takes that owner, so no root `chown` step is needed.
* Next's standalone output of each app is about 42 MB (`du -sh web/apps/*/.next/standalone`).
* Faye's egress client (`java.net.http.HttpClient`, `faye-hooks` `HttpEgress`) asks to upgrade plain `http` requests to `h2c`; a Next server answers such a request with an empty reply (`curl --http2` against `next start`). The ip-reputation stub can't be a Next route.
* The seed used to hold SQLite's write lock while it hashed 1000 passwords with Argon2. With the seed no longer running before Faye, Faye's startup publish hit `SQLITE_BUSY` past its 10 s busy timeout.

## Decision Drivers

* Fewer services and smaller images, with the product's three clearly apart from the demo.
* `deploy/smoke.sh` passes unchanged against the new stack.
* Charter: no product change for the sake of the demo.

## Considered Options

1. Hydra's migration
   * A. Hydra's own service runs `hydra migrate sql -e --yes && exec hydra serve all --dev`.
   * B. Keep a one shot `hydra-migrate` service.
2. Seed, client registration and the ip-reputation stub
   * A. One `demo` service, the demo client's: on start it runs the stub in the background, then `bootstrap.mjs` (ported from `bootstrap.sh`, since the image has Node and no curl; it waits for Hydra to be ready, so Hydra creates the database file) and `seed.mjs`, then serves the demo client.
   * B. Keep `seed`, `bootstrap` and `ip-reputation` as their own services.
   * C. Serve the stub as a route of the demo client. Rejected: the `h2c` upgrade above.
   * D. Make `HttpEgress` speak HTTP/1.1 only. Rejected for now: a product change for a stub.
3. Web images
   * A. One image (`web/Dockerfile`) with the three apps' standalone output; compose picks the app with `APP` and the port with `PORT`. `login` builds it, `admin` and `demo` reuse the tag; the apps are built one at a time so the build fits a small Docker VM (in parallel, it was killed for lack of memory in a Colima VM).
   * B. One standalone image per app.
   * C. Keep the full workspace images.
4. Faye's base image: A. `eclipse-temurin:21-jre-alpine` (sqlite-jdbc ships a musl native library); B. keep the Ubuntu JRE.

## Decision Outcome

1A, 2A, 3A, 4A. Five services: `hydra`, `faye`, `login`, `admin`, `demo`. The seed hashes every account first and holds the write lock only for the inserts. Every service that opens the database runs as 100:101. The `directory-secrets` volume is mounted only into `demo`; the web image creates `/secrets` owned by 100:101 so the new volume takes that owner.

This changes how compose arranges the [ADR 0017](0017-one-sqlite-database.md) database (no `seed` first, no root `chown`), not the decision to have one database.

### Consequences

* Good, because nine services become five, and the images on a 2026-10-31 Colima build went from 3 × ~1.55 GB + 562 MB to 519 MB (web) + 372 MB (faye).
* Good, because the product's services carry no demo configuration but the stub's URL and the preview flag, as before.
* Bad, because `demo` runs two processes (the stub in the background); if the stub dies, the risk hook fails closed (scores high) until `demo` restarts.
* Bad, because the login and admin images contain the demo client and an empty `/secrets`, which they never use.
* Neutral: the Login UI is still its own service; merging it into Faye would mean serving Next from Spring, out of scope.

### Confirmation

On a fresh stack (`docker compose -f deploy/compose.yaml down -v`, `docker rmi faye-web`, then `up -d --build`): `docker compose ps` lists five services and `deploy/smoke.sh` prints every `ok` line through `single database ok`. CI's `e2e` job runs the same.

## More Information

The stack's SQLite database and the `demo` service exist to try Faye; they are not the product's runtime requirements ([ADR 0017](0017-one-sqlite-database.md)), so the stack is not load tested.

Not pursued here: a node Alpine base for the web image (Node's built in Argon2 and `node:sqlite` on musl unverified), estimated to save about another 150 MB. [ADR 0023](0023-one-container-many-processes.md) proposes an Alpine image and decides it there.

Run on 2026-10-31 on a fresh stack (separate compose project, new volumes): `docker compose ps` lists `hydra`, `faye`, `login`, `admin`, `demo`; images `faye-web` 519 MB and `faye` 384 MB; `deploy/smoke.sh` prints every `ok` line through `single database ok`.
