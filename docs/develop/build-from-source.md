---
description: Install the toolchain, run Faye with or without Docker, and run every check that CI runs.
---

# Build and run from source

This page is for contributors who change Faye's code. It lists the tools you need, shows three ways to run the stack, and explains each check that CI runs. If you only want to try Faye, follow the [Quickstart](../start/quickstart.md) instead.

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 or later, with the `jar` tool | Both Gradle builds and the server. CI uses Temurin 21. |
| Node.js | 24 | The web apps, the seed, client registration and `smoke.sh`. CI and the image use Node 24. The seed uses `node:sqlite` and Argon2 from `node:crypto`, which older versions lack. |
| pnpm | 11.20.0 | The web workspace in `apps/web`. The version is pinned in `apps/web/package.json`. |
| Docker with Compose | Any recent version | `make up`, `make down` and the image. |
| curl or wget, and sha256sum or shasum | Any | The first Gradle run. |
| curl and python3 | Any | `deploy/smoke.sh`. |
| shasum | Any | `deploy/hydra-local.sh`, which checks Hydra's checksum with `shasum -a 256`. |

### Pick a JDK

The `java` on your `PATH` may be older than 21. The [Makefile](../../Makefile) handles this in two ways:

- On macOS, it runs `/usr/libexec/java_home -v 21+` and uses the JDK that it finds.
- On other systems, it uses your `JAVA_HOME`. Set it in your shell, or pass it to `make`.

```bash
make setup JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

When you call `./gradlew` directly, set `JAVA_HOME` the same way.

### The Gradle wrapper

The repository has no Gradle wrapper jar, because it holds no binaries ([ADR 0012](../adr/0012-bootstrap-gradle-without-a-wrapper-jar.md)). Instead, `./gradlew` downloads Gradle 9.7.1 on its first run. It checks the archive against the SHA-256 sum in `gradle/wrapper/gradle-wrapper.properties`, then unpacks it with the JDK's `jar` tool. This first run needs curl or wget, and sha256sum or shasum.

> [!NOTE]
> On Windows, `gradlew.bat` still needs a wrapper jar that you supply. ADR 0012 changes only the POSIX `gradlew`.

### Set up once

`make setup` checks the toolchain, installs the web dependencies, and compiles both Gradle builds.

> [!WARNING]
> `make setup` still requires `redis-server` and `redis-cli`, although Faye no longer uses Redis ([ADR 0017](../adr/0017-one-sqlite-database.md)). If you do not have Redis, run the same steps by hand:
>
> ```bash
> pnpm -C apps/web install --frozen-lockfile
> (cd src && ./gradlew assemble)
> (cd apps/server && ./gradlew assemble)
> ```

## Run the stack

Pick one of three ways. They run the same code.

| Way | Command | Best for |
|---|---|---|
| One container | `make up` | Running the stack as it ships. |
| Without Docker | `make dev` | Changing code with fast reloads, the whole stack running. |
| Piece by piece | Gradle and pnpm commands | Working on one app at a time. |

### One container with Docker

```bash
make up
```

This builds the image from the [Dockerfile](../../Dockerfile) and starts the container `faye`, as in the [Quickstart](../start/quickstart.md). Every process runs under s6-overlay in one container ([ADR 0023](../adr/0023-one-container-many-processes.md)). `FAYE_SERVICES` selects a subset of the processes. See [Configuration](../reference/configuration.md#image-and-container).

Stop it with `make down`. Delete its data with `docker compose -f deploy/compose.yaml down -v`.

### Without Docker

```bash
make dev
```

`make dev` runs [`deploy/dev.sh`](../../deploy/dev.sh). It starts the same processes as the image, on your machine, and prefixes each line of output with the process name:

| Process | What it is |
|---|---|
| `hydra` | Hydra v2.2.0 from [`deploy/hydra-local.sh`](../../deploy/hydra-local.sh). The script downloads a pinned release into `deploy/.bin/` on first use and checks its checksum. It supports macOS on arm64 and x86_64, and Linux on x86_64 and aarch64. |
| `bootstrap` | [`deploy/bootstrap.mjs`](../../deploy/bootstrap.mjs) registers the ten applications and the two preview clients in Hydra. |
| `ip-reputation` | The stub IP reputation service on port 8000, for Acme's risk hook. |
| `faye` | The server through `./gradlew :faye-app:bootRun`, with Hydra, the preview, the admin API and the outbox turned on. |
| `seed` | [`deploy/directory/seed.mjs`](../../deploy/directory/seed.mjs) writes 1000 accounts once Faye has migrated the database. |
| `login`, `demo-client`, `admin` | The three Next.js apps in dev mode, on ports 3000, 3001 and 3002. |

`dev.sh` keeps its state in these places:

| What | Where |
|---|---|
| The SQLite database, shared by Hydra and Faye | `deploy/.data/faye.db` |
| One time codes, written as files | `deploy/.data/outbox/` |
| The admin API socket | `deploy/.data/run/admin.sock` |
| Passwords (`users.csv`) and client secrets (`clients.jsonl`) | `$XDG_STATE_HOME/faye/secrets/`, by default `~/.local/state/faye/secrets/` |

The admin API token is `dev-admin-token` unless you set `FAYE_ADMIN_TOKEN`. Hydra runs with `--dev` and a fixed system secret. Never use this setup in production.

Press Ctrl-C to stop every process. To start again from nothing, delete both `deploy/.data/` and the secrets folder.

### Piece by piece

Run only the apps that you work on.

**The server.** Run it with Gradle:

```bash
cd apps/server
./gradlew :faye-app:bootRun
```

The server listens on http://localhost:8080. Its database is `faye.db` in `apps/server/faye-app/`. If `FAYE_HYDRA_ADMIN_URL` is not set, the server uses an in memory host, `MockFlowHost`, instead of Hydra. Any challenge then starts a flow for the client `demo` of the first tenant, `acme`. Open http://localhost:8080/login?login_challenge=anything to start one.

The shipped configuration has no accounts, so the user pool starts empty. To add the demo accounts, run the seed while the server runs:

```bash
DATABASE=apps/server/faye-app/faye.db SECRETS="$HOME/.local/state/faye/secrets" node deploy/directory/seed.mjs
```

**The Login UI.** Run it in dev mode:

```bash
cd apps/web
FAYE_SERVER_URL=http://localhost:8080 pnpm --filter @faye/login dev
```

It listens on http://localhost:3000. The server sends the browser there with `?flow=` once a flow starts.

**The Admin UI.** The Admin UI calls the server's admin API over a Unix socket. Start the server and the Admin UI with the same socket path and token:

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

The Admin UI listens on http://127.0.0.1:3002. If either value is missing or does not match, the Admin UI cannot reach the admin API and shows an error. Without Hydra, **Applications** is empty. **Try sign in** and **Preview** also need the demo client.

## Run the checks

Faye has two Gradle builds and one pnpm workspace. The product is in `src/`, and the prototype that uses it is in `apps/` ([ADR 0034](../adr/0034-two-shippable-modules-under-src.md)). See [Read the codebase](codebase.md) for what each part holds.

### The product build

```bash
cd src
./gradlew build
```

This build has the strictest gates:

- It compiles with `-Xlint:all -Werror`, Error Prone and NullAway.
- It checks the format with Spotless and the style with Checkstyle.
- It runs the unit tests and the conformance suite.
- It enforces JaCoCo coverage floors.

If the format check fails, run `./gradlew spotlessApply`. To publish the jars with sources and javadoc to your local Maven repository, run `./gradlew publishToMavenLocal`.

### The prototype server build

```bash
cd apps/server
./gradlew build
```

This builds `faye-identity`, `faye-clients`, `faye-host-hydra` and `faye-app`. It takes `faye-core` and `faye-engine` from the `src` build as a composite build. Each test context gets a fresh SQLite database under `apps/server/faye-app/build/`.

### The web checks

```bash
cd apps/web
pnpm install
pnpm typecheck && pnpm lint && pnpm test && pnpm build
```

Lint runs with `--max-warnings 0`. `pnpm test` runs `node --test` over `apps/admin/lib`, `apps/login/lib` and `packages/ui/src`.

If you change [`src/schema/screen.schema.json`](../../src/schema/screen.schema.json), run `pnpm generate` to regenerate the TypeScript types in `packages/contract/src`. CI fails if the committed types differ from the schema.

### The Login UI browser tests

```bash
cd apps/web
pnpm e2e:install   # once: downloads the pinned Chromium into apps/login/.playwright/
pnpm build
pnpm e2e
```

Playwright starts the built Login UI on port 13000 against a stub of the screen API on port 18080. A running stack on ports 3000 and 8080 is left alone.

### The smoke test

```bash
deploy/smoke.sh
```

[`smoke.sh`](../../deploy/smoke.sh) is the acceptance test of the whole stack. It signs in through every tenant and every application, publishes through the Admin UI, runs hooks, previews a draft, and signs in with an emailed code and a QR code. It needs curl and python3.

> [!IMPORTANT]
> Run `smoke.sh` against a fresh stack. One of its steps enrolls TOTP for an account, so a second run finds it already enrolled and fails. Reset with `docker compose -f deploy/compose.yaml down -v && make up` first.

To test the stack that `make dev` runs, point the script at its database. It then reads the secrets from `~/.local/state/faye/secrets/`:

```bash
FAYE_DATABASE=deploy/.data/faye.db deploy/smoke.sh
```

### Fuzzing

```bash
deploy/fuzz.sh 2m [harness]
```

[`fuzz.sh`](../../deploy/fuzz.sh) runs coverage guided fuzzing with Jazzer, one harness per JVM ([ADR 0007](../adr/0007-fuzz-and-adversarial-testing.md)). The first argument is the time per harness, `2m` by default. The second argument filters the 11 harnesses by name, for example `FlowCompilerFuzzTest`. `./gradlew build` only replays the stored inputs. It does not search for new ones.

> [!CAUTION]
> A fuzz finding is a security bug until someone triages it. Do not attach the crash input to a public issue or log. Turn it into a readable regression test: a conformance vector, a named test or a text seed.

### Regenerate the conformance vectors

The checker enumerates every path through the conformance flows and writes one vector per path into `src/conformance/generated/`. After you change a conformance flow or the checker, rewrite them:

```bash
cd src
FAYE_REGENERATE=1 ./gradlew :faye-engine:test
```

The same variable rewrites the compiler's golden files: `FAYE_REGENERATE=1 ./gradlew :faye-core:test`. Never edit generated files by hand. The build fails when they are stale.

## Makefile targets

Run `make help` to list them.

| Target | What it runs |
|---|---|
| `setup` | Checks the toolchain, runs `pnpm install --frozen-lockfile`, and runs `assemble` in both Gradle builds. |
| `build` | `assemble` in both Gradle builds, then `pnpm build`. No tests. |
| `test` | `test` in both Gradle builds, then the web tests. |
| `lint` | Spotless and Checkstyle in both Gradle builds, then `pnpm typecheck` and `pnpm lint`. |
| `check` | `build` in both Gradle builds, the contract diff, `pnpm typecheck`, `pnpm lint` and `pnpm build`. |
| `format` | `spotlessApply` in both Gradle builds. |
| `generate` | `pnpm generate`: the TypeScript contract types from the schema. |
| `dev` | `deploy/dev.sh`: the whole stack without Docker. |
| `up` | `docker compose -f deploy/compose.yaml up -d --build --remove-orphans`. |
| `down` | `docker compose -f deploy/compose.yaml down`. The volume stays. |
| `smoke` | `deploy/smoke.sh` against a running stack. |
| `clean` | `clean` in both Gradle builds, and the Next.js build output. Keeps `node_modules` and `deploy/.bin`. |

> [!NOTE]
> `make check` is not a full copy of CI. It skips `pnpm test`, the Login UI browser tests and the smoke test. Run those separately before you open a pull request.

## What CI runs

[`ci.yml`](../../.github/workflows/ci.yml) runs four jobs on every pull request and on every push to `main`. All four must pass.

| Job | What it runs |
|---|---|
| `src` | `./gradlew build publishToMavenLocal` in `src/`, with JDK 21. It uploads the JaCoCo report. |
| `server` | `./gradlew build` in `apps/server/`. |
| `web` | `pnpm install --frozen-lockfile`, then `pnpm generate` with a diff check, `typecheck`, `lint`, `test`, `build` and the Login UI browser tests in Chromium. |
| `e2e` | Builds and starts the compose stack, waits for the seed and the client registrations, runs `deploy/smoke.sh`, and checks that Hydra's admin API is not published. |

[`fuzz.yml`](../../.github/workflows/fuzz.yml) fuzzes each harness for 2 minutes on pull requests that touch `src/`, `apps/server/` or the fuzz script. It fuzzes each harness for 30 minutes every night.

## Known rough edges

These are true of the code today:

- `make setup` requires `redis-server` and `redis-cli`, and the help text of `make dev` still lists Redis. Faye moved every store to SQLite in [ADR 0017](../adr/0017-one-sqlite-database.md), and `dev.sh` starts no Redis.
- The help text of `make generate` names `schema/screen.schema.json`. The schema now lives at `src/schema/screen.schema.json`.
- `src/conformance/README.md` says to regenerate with `:faye-check:test`. The task is `:faye-engine:test`.
- The repository's `README.md` names a `faye-host-mock` module. The mock host is now `MockFlowHost`, in the test fixtures of `faye-engine`.

## Next steps

- [Read the codebase](codebase.md): a guided tour of the modules and the rules every change follows.
- [Configuration](../reference/configuration.md): every environment variable and tenant setting.
- [Write the docs](write-docs.md): the style guide for these pages.
