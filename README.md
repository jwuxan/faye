# Faye

Faye is the login box that sits in front of an authorization server. The server (Ory Hydra) speaks OAuth2/OIDC to applications; Faye owns every screen the user sees and decides how they prove who they are: password, TOTP, passkey, consent screen, risk check. Flows are written in YAML, so you can reshape a sign in without touching server code.

The repo ships with a full sandbox: two tenants (`acme` and `bank`) with their own user pools, seeded accounts, ten registered applications, and a demo OIDC client that shows you the token at the end.

## Try it in 3 minutes

You need Docker. Nothing else.

```sh
make up        # builds the image and starts one container
```

Wait for the container to be healthy (`docker compose -f deploy/compose.yaml ps`), then:

1. Open http://localhost:3001, the demo client's launch panel.
2. Pick an application (Acme Web is a good first one) and sign in.
3. When it asks for an account, grab a seeded one:

   ```sh
   docker exec faye cat /var/lib/faye/demo/users.csv
   ```

4. You land back on the demo client, which shows the decoded ID token, its `amr`/`acr`, and the trace of the steps the flow ran.

That is the whole loop: Hydra (`:4444`) sends the browser to Faye (`:8080`), Faye runs the flow in the login UI (`:3000`), and the demo client (`:3001`) shows the result.

If sign in hangs or the container stays unhealthy, the usual cause is a stale database from an earlier build. Wipe the volume and start over:

```sh
docker compose -f deploy/compose.yaml down -v && make up
```

To exercise every tenant and application at once, run `deploy/smoke.sh` against a fresh stack.

## The Admin UI

Open http://localhost:3002 (localhost only, no sign in). From there you can edit a tenant's flow as blocks or YAML beside a live graph, publish or roll back versions, browse the registered applications, and look at the user pools. Every application has a **Try sign in** button that runs it through the demo client. ⌘K opens a command palette; hold ⌘ to see every shortcut.

The **Preview** panel next to the flow editor runs a draft flow without publishing it. The scenario picks an account (none, a new user, or a user with TOTP whose current code is shown), and switches set a high risk IP or force `faye:2fa`. As the person signs in, the trace streams in and lights up the blocks the run visits, and the demo client checks the token's `amr`/`acr` against the paths the checker predicted.

## What the two tenants show

| Tenant | Applications | Flow (`apps/server/faye-app/src/main/resources/flows/`) |
|---|---|---|
| `acme` | Acme Web, Acme Docs, Acme Shop; Acme Partner Portal and Acme Analytics (third party) | `acme.yaml`: password, plus TOTP when the `risk.js` hook scores the sign in high (a user agent containing `suspicious` does it), and a consent screen for third party applications |
| `bank` | Bank Online, Bank Mobile, Bank Advisor Desk; Bank Business and Bank Investments (third party) | `passkey-totp.yaml`: passkey from autofill, or password plus TOTP; `acr_values=faye:2fa` forces a second factor; a consent screen for third party applications |

The seed writes 1000 accounts (600 acme, 400 bank, a third with TOTP) with Argon2 hashes. Passwords live only inside the container at `/var/lib/faye/demo/users.csv`; application secrets are in `clients.jsonl` in the same directory. Passkeys created in the login UI are scoped to `localhost`. All state, Hydra's tables included, is one SQLite file in the `data` volume.

## Run it without Docker

`deploy/dev.sh` runs the same stack locally: seed, a pinned Hydra (downloaded into `deploy/.bin/` on first use), Faye, and the web apps, all on `deploy/.data/faye.db`. Passwords and client secrets go to `~/.local/state/faye/secrets`. Then:

```sh
FAYE_DATABASE=deploy/.data/faye.db deploy/smoke.sh
```

## Developing

Server needs JDK 21+; `java` on PATH may be older (`make setup` picks one). Decisions and open work: [docs/adr](docs/adr/README.md).

```sh
cd apps/server
./gradlew build                        # compile (-Xlint:all -Werror), unit + conformance tests
./gradlew :faye-app:bootRun            # http://localhost:8080, state in faye-app/faye.db
```

```sh
cd apps/web
pnpm install
pnpm typecheck && pnpm lint && pnpm test && pnpm build
pnpm e2e:install && pnpm e2e         # Login UI browser tests (Chromium), after the build
FAYE_SERVER_URL=http://localhost:8080 pnpm --filter @faye/login dev   # http://localhost:3000/login?flow={flowId}
```

Repo map: `src/schema/` holds the IR grammar and screen protocol (JSON Schema), `src/conformance/` the engine test vectors, `apps/web/` the pnpm workspace (`apps/login` renders the typed screens, `apps/demo-client` is the OIDC relying party, `apps/admin` the Admin UI), `apps/server/faye-app/src/main/resources/flows/` the tenant flows.

With `FAYE_HYDRA_ADMIN_URL` set (compose does), the server talks to Hydra through `faye-host-hydra`; without it, challenges come from the in-memory `faye-host-mock`, so any `login_challenge` works at http://localhost:8080/login?login_challenge=anything.
