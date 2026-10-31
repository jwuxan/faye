#!/bin/sh
# Runs the whole stack locally without Docker, like deploy/compose.yaml: Redis (reused if one is
# already up), Hydra (deploy/hydra-local.sh), the demo client registrations, the ip-reputation stub,
# Faye, and the Login UI, demo client and Admin UI in dev mode. Ctrl-C stops everything this script started.
#
#   make dev        # then open http://localhost:3001, or run deploy/smoke.sh
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
java=${JAVA_HOME:+$JAVA_HOME/bin/}java

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

if redis-cli ping >/dev/null 2>&1; then
  echo "[dev] reusing the Redis on :6379"
else
  run redis redis-server --save "" --appendonly no
fi
run hydra "$root/deploy/hydra-local.sh"
run bootstrap "$root/deploy/bootstrap.sh"
run ip-reputation "$java" "$root/deploy/ip-reputation/IpReputation.java"
run faye env \
  FAYE_HYDRA_ADMIN_URL=http://localhost:4445 \
  FAYE_ADMIN_TOKEN="${FAYE_ADMIN_TOKEN:-dev-admin-token}" \
  FAYE_IP_REPUTATION_URL=http://localhost:8000/score \
  FAYE_PREVIEW_ENABLED=true \
  "$root/server/gradlew" -p "$root/server" --console=plain :faye-app:bootRun
run login pnpm -C "$root/web" --filter @faye/login dev
run demo-client pnpm -C "$root/web" --filter @faye/demo-client dev
run admin env \
  FAYE_ADMIN_TOKEN="${FAYE_ADMIN_TOKEN:-dev-admin-token}" \
  FAYE_ADMIN_SUBJECTS=user-alice \
  pnpm -C "$root/web" --filter @faye/admin dev

echo "[dev] demo client http://localhost:3001, Admin UI http://localhost:3002 (alice@example.com / correct horse battery staple, TOTP JBSWY3DPEHPK3PXP); Ctrl-C stops"
wait
