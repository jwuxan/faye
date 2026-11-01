#!/bin/sh
# Runs the whole stack locally without Docker, like deploy/compose.yaml: Hydra
# (deploy/hydra-local.sh), the client registrations, the ip-reputation stub, the seed (each
# tenant's user pool, once Faye has migrated the schema),
# Faye, and the Login UI, demo client and Admin UI in dev mode, all on one SQLite database,
# deploy/.data/faye.db. Generated passwords and client secrets go to $XDG_STATE_HOME/faye/secrets
# (default ~/.local/state/faye/secrets), outside the repo. Ctrl-C stops everything this script started.
#
#   make dev        # then open http://localhost:3001, or run deploy/smoke.sh
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)

set -m   # each job gets its own process group, so stop() can kill its whole tree
stop() {
  trap - INT TERM EXIT
  for pgid in $(jobs -p); do kill -TERM "-$pgid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap 'stop; exit 0' INT TERM
trap stop EXIT

# run NAME CMD...: runs CMD in the background with its output prefixed by NAME
run() {
  name=$1; shift
  { "$@" </dev/null 2>&1 | awk -v p="[$name] " '{ print p $0; fflush() }'; } &
}

export FAYE_DATABASE="${FAYE_DATABASE:-$root/deploy/.data/faye.db}"
# Faye serves the admin API only on this Unix domain socket (ADR 0028), in a directory only you can enter
mkdir -p "$root/deploy/.data/run" && chmod 700 "$root/deploy/.data/run"
export FAYE_ADMIN_SOCKET="$root/deploy/.data/run/admin.sock"
secrets=${XDG_STATE_HOME:-$HOME/.local/state}/faye/secrets
run hydra "$root/deploy/hydra-local.sh"
run bootstrap node "$root/deploy/bootstrap.mjs"
run ip-reputation node "$root/deploy/ip-reputation.mjs"
run faye env \
  FAYE_HYDRA_ADMIN_URL=http://localhost:4445 \
  FAYE_ADMIN_TOKEN="${FAYE_ADMIN_TOKEN:-dev-admin-token}" \
  FAYE_IP_REPUTATION_URL=http://localhost:8000/score \
  FAYE_PREVIEW_ENABLED=true \
  FAYE_DEMO_OUTBOX="$root/deploy/.data/outbox" \
  "$root/apps/server/gradlew" -p "$root/apps/server" --console=plain :faye-app:bootRun
# Writes each tenant's user pool once Faye has migrated the schema it polls for
run seed env DATABASE="$FAYE_DATABASE" SECRETS="$secrets" node "$root/deploy/directory/seed.mjs"
run login env DEMO_SIGN_IN_URL=http://localhost:3001/login pnpm -C "$root/apps/web" --filter @faye/login dev
run demo-client pnpm -C "$root/apps/web" --filter @faye/demo-client dev
run admin env \
  FAYE_ADMIN_TOKEN="${FAYE_ADMIN_TOKEN:-dev-admin-token}" \
  pnpm -C "$root/apps/web" --filter @faye/admin dev

echo "[dev] Admin UI http://localhost:3002 (Try sign in on any application), demo client http://localhost:3001; accounts and passwords in $secrets/users.csv; Ctrl-C stops"
wait
