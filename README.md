# faye
An IdP Frontend

## Run everything

```sh
docker compose -f deploy/compose.yaml up --build
deploy/smoke.sh                        # scripted sign ins for every demo tenant; or open http://localhost:3001
```

Hydra (`:4444` public, `:4445` admin) sends the browser to Faye (`:8080`), which runs the flow in the Login UI (`:3000`); the demo client (`:3001`) shows the decoded ID token. The Admin UI (`:3002`) composes, checks, publishes and rolls back each tenant's flow; operators sign in to it through Hydra as Alice, with her password and TOTP code. `deploy/bootstrap.sh` registers one demo client per tenant, and the demo client's launch panel picks the client, `acr_values`, `login_hint`, `prompt`, `max_age` and `ui_locales`.

Preview: in the Admin UI, **Preview** opens a panel beside the block tree. Launch opens the demo client with the tenant's preview client (`preview-<tenant>`, registered with `metadata.preview = true`), which runs the tenant's draft, once it passes the checker, without publishing it. Presets seed preview only accounts (New user, User with TOTP, whose current code the panel shows), set a high risk IP (`203.0.113.7`, which the `ip-reputation` stub scores high) or ask for `faye:2fa`. The trace streams in as the person signs in and lights up the blocks the run visits; the demo client's result view shows the trace and checks the token's `amr` / `acr` against the paths the checker predicted. Previews are on only when the server has `FAYE_PREVIEW_ENABLED=true`, as compose sets it.

| Client | Tenant | Flow (`server/faye-app/src/main/resources/flows/`) |
|---|---|---|
| `demo` | `default` | `password-only.yaml` |
| `demo-mfa` | `mfa` | `passkey-totp.yaml`: passkey from autofill, or password plus TOTP; `acr_values=faye:2fa` forces a second factor (enrolling TOTP if needed) |
| `partner-app` (third party) | `partner` | `partner-consent.yaml`: password, then a consent screen |
| `demo-risk` | `risk` | `risk-totp.yaml`: password, plus TOTP when the `risk.js` hook scores the sign in high through the `ip-reputation` stub (a user agent containing `suspicious` scores high) |

Seeded accounts (in memory, reset on restart): `alice@example.com` (TOTP secret `JBSWY3DPEHPK3PXP`) and `bob@example.com` (no second factor), both with password `correct horse battery staple`. Passkeys created in the Login UI are scoped to `localhost`.

Without Docker, `deploy/hydra-local.sh` runs the same pinned Hydra (v2.2.0) on SQLite. It downloads the release binary on first use into `deploy/.bin/` (git ignored) and checks it against a pinned checksum. Then run `deploy/bootstrap.sh`, Redis, the server with `FAYE_HYDRA_ADMIN_URL=http://localhost:4445`, `FAYE_ADMIN_TOKEN=dev-admin-token` and `FAYE_PREVIEW_ENABLED=true`, the web apps (`pnpm --filter @faye/login start`, `pnpm --filter @faye/demo-client start`, and `FAYE_ADMIN_TOKEN=dev-admin-token FAYE_ADMIN_SUBJECTS=user-alice pnpm --filter @faye/admin start`), and `deploy/smoke.sh`.

## Server

Java 21+ (Gradle compiles with `--release 21`). Plan and milestones: [docs/roadmap](docs/roadmap/README.md).

```sh
cd server
./gradlew build                        # compile (-Xlint:all -Werror), unit + conformance tests
redis-server &                         # flow state store; the restart test skips without it
./gradlew :faye-app:bootRun            # http://localhost:8080/login?login_challenge=anything
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
