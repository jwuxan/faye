# faye
An IdP Frontend

## Run everything

```sh
docker compose -f deploy/compose.yaml up --build
deploy/smoke.sh                        # scripted sign ins for every tenant and application; or open http://localhost:3002
```

One image, one container: `hydra`, `faye` (the server), `login` (the Login UI) and `admin` (the Admin UI) run under s6-overlay, and `FAYE_DEMO=true` adds everything that is only there to try it (client registrations, seeded user pools, the demo client and the `ip-reputation` stub) ([ADR 0023](docs/adr/0023-one-container-many-processes.md)). Without compose: `docker build -t faye . && docker run -d --name faye -v faye-data:/var/lib/faye -e FAYE_DEMO=true -p 4444:4444 -p 8080:8080 -p 3000:3000 -p 3001:3001 -p 127.0.0.1:3002:3002 faye`.

Hydra (`:4444` public; its admin API stays on the container's loopback) sends the browser to Faye (`:8080`), which runs the flow in the Login UI (`:3000`); the demo client (`:3001`) shows the decoded ID token. The Admin UI (`:3002`, localhost only, no sign in) composes, checks, publishes and rolls back each tenant's flow, and its Applications page has **Try sign in** for every application. The demo client's launch panel picks the application, `acr_values`, `login_hint`, `prompt`, `max_age` and `ui_locales`.

Each tenant has its applications and its own user pool; an account of one tenant is unknown to the other tenant's applications.

| Tenant | Applications (Hydra generates their ids and secrets) | Flow (`server/faye-app/src/main/resources/flows/`) |
|---|---|---|
| `acme` | Acme Web, Acme Docs, Acme Shop; Acme Partner Portal and Acme Analytics (third party) | `acme.yaml`: password, plus TOTP when the `risk.js` hook scores the sign in high through the `ip-reputation` stub (a user agent containing `suspicious` scores high), and a consent screen for third party applications |
| `bank` | Bank Online, Bank Mobile, Bank Advisor Desk, Bank Business, Bank Investments | `passkey-totp.yaml`: passkey from autofill, or password plus TOTP; `acr_values=faye:2fa` forces a second factor (enrolling TOTP if needed) |

Accounts: the `seed` one shot writes 1000 (600 `acme`, 400 `bank`, a third with TOTP) into the database with Argon2 hashes, and their passwords to `/var/lib/faye/demo`, never to the repo: `docker exec faye cat /var/lib/faye/demo/users.csv`. The same directory holds the applications' secrets (`clients.jsonl`), which only the demo client, their relying party, reads. Passkeys created in the Login UI are scoped to `localhost`.

Storage: one SQLite database (`/var/lib/faye/faye.db` in the volume) holds Hydra's tables and Faye's: flow state, versions, hooks, settings, preview traces and the user pools ([ADR 0017](docs/adr/0017-one-sqlite-database.md)).

Preview: in the Admin UI, **Preview** opens a panel beside the block tree. Launch opens the demo client with the tenant's preview client (`preview-<tenant>`, registered with `metadata.preview = true`), which runs the tenant's draft, once it passes the checker, without publishing it. Presets seed preview only accounts (New user, User with TOTP, whose current code the panel shows), set a high risk IP (`203.0.113.7`, which the `ip-reputation` stub scores high) or ask for `faye:2fa`. The trace streams in as the person signs in and lights up the blocks the run visits; the demo client's result view shows the trace and checks the token's `amr` / `acr` against the paths the checker predicted. Previews are on only when the server has `FAYE_PREVIEW_ENABLED=true`, which the image sets when `FAYE_DEMO=true`.

Without Docker, `deploy/dev.sh` runs the same stack: the seed, the pinned Hydra (v2.2.0, `deploy/hydra-local.sh`, which downloads the release binary on first use into `deploy/.bin/`, git ignored, and checks it against a pinned checksum), bootstrap, Faye and the web apps, all on `deploy/.data/faye.db`; passwords and client secrets go to `~/.local/state/faye/secrets`. Then `FAYE_DATABASE=deploy/.data/faye.db deploy/smoke.sh`.

## Server

Java 21+ (Gradle compiles with `--release 21`). Plan and milestones: [docs/roadmap](docs/roadmap/README.md).

```sh
cd server
./gradlew build                        # compile (-Xlint:all -Werror), unit + conformance tests
./gradlew :faye-app:bootRun            # http://localhost:8080/login?login_challenge=anything (tenant acme); state in faye-app/faye.db
```

- `schema/`: IR grammar and screen protocol (JSON Schema).
- `web/`: pnpm workspace. `packages/contract` holds TS types generated from `schema/screen.schema.json` (`pnpm generate`); `packages/ui` holds the shared shadcn/ui components and Geist theme; `apps/login` renders the typed screens; `apps/demo-client` is the OIDC relying party; `apps/admin` is the Admin UI.
- `conformance/`: engine test vectors, run by `faye-engine` tests.
- `server/faye-app/src/main/resources/flows/`: tenant flows in YAML.

```sh
cd web
pnpm install
pnpm typecheck && pnpm lint && pnpm test && pnpm build
FAYE_SERVER_URL=http://localhost:8080 pnpm --filter @faye/login dev   # http://localhost:3000/flow/{flowId}
```

With `FAYE_HYDRA_ADMIN_URL` set (compose does), the server talks to Hydra through `faye-host-hydra`; without it, challenges come from the in memory `faye-host-mock` host, so any `login_challenge` works locally.
