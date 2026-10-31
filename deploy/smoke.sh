#!/bin/sh
# M2 to M6 checks against a running stack (compose, or deploy/dev.sh): signs in from the demo client
# through Hydra, Faye and the Login UI for each tenant, then reads the ID token claims the demo client
# displays; then publishes through the admin API while a sign in is under way; then routes sign ins
# by a risk hook and runs malicious hooks; then drafts, checks, publishes and brands through the
# Admin UI, which has no sign in; then previews a draft without publishing it; then signs in through
# every application the console lists, as its "Try sign in" does; then checks that one SQLite
# database holds Hydra's tables and Faye's.
#
# Accounts are the seeded ones: their passwords are read from the secrets the demo wrote (the
# container's /var/lib/faye/demo, or with FAYE_DATABASE set, as deploy/dev.sh runs, from
# $XDG_STATE_HOME/faye/secrets) into variables and never printed. Needs curl and python3 (for
# TOTP codes and JSON), and FAYE_ADMIN_TOKEN as the server has it (default: the token the image
# generated, read out of the container, or dev.sh's dev-admin-token). FAYE_CONTAINER names the
# container (default faye). Run it against a fresh stack: step 4 enrolls TOTP
# for an account, so a second run finds it already enrolled.
set -eu
container=${FAYE_CONTAINER:-faye}
if [ -n "${FAYE_ADMIN_TOKEN:-}" ]; then
  token=$FAYE_ADMIN_TOKEN
elif [ -n "${FAYE_DATABASE:-}" ]; then
  token=dev-admin-token
else
  token=$(docker exec "$container" cat /var/lib/faye/secrets/admin-token)
fi
jar=$(mktemp)
body=$(mktemp)
events=$(mktemp)   # the preview trace feed, step 11
trap 'rm -f "$jar" "$body" "$events"' EXIT
ua=faye-smoke   # the user agent Faye sees at /login; the demo's ip-reputation stub scores "suspicious" ones high
get() { curl -fsS -A "$ua" -b "$jar" -c "$jar" -o /dev/null -w '%{redirect_url}' "$1"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# Follows redirects until the Login UI shows a screen (prints its JSON) or the demo client shows
# its result page (prints the page).
walk() {
  url=$1
  while :; do
    case "$url" in
      http://localhost:3000/flow/*) curl -fsS "http://localhost:8080/api/flows/${url##*/}"; return ;;
      http://localhost:3001/ | "http://localhost:3001/?"*) curl -fsS -b "$jar" "$url"; return ;;
      "") echo "redirect chain ended without a screen or result" >&2; exit 1 ;;
    esac
    url=$(get "$url")
  done
}

# Starts a sign in from the demo client with the given launch query; prints the first screen.
start() { : > "$jar"; walk "http://localhost:3001/login?$1"; }

# Answers a screen (its JSON in $1) with a payload; prints the next screen or the result page.
answer() {
  flow=$(printf '%s' "$1" | field flowId)
  nonce=$(printf '%s' "$1" | field nonce)
  res=$(curl -fsS -X POST "http://localhost:3000/api/flows/$flow/submit" -H 'content-type: application/json' \
    -d "{\"nonce\":\"$nonce\",\"action\":\"submit\",\"payload\":$2}")
  next=$(printf '%s' "$res" | field redirectTo)
  if [ -n "$next" ]; then walk "$next"; else printf '%s' "$res"; fi
}

expect_screen() {
  printf '%s' "$1" | grep -q "\"type\":\"$2\"" || { echo "expected screen $2, got: $1" >&2; exit 1; }
}

claims() { printf '%s' "$1" | grep -o 'data-claim="[a-z_]*">[^<]*' | sed 's/data-claim="//; s/">/ = /; s/&quot;/"/g'; }

expect_claim() {
  claims "$1" | grep -qxF "$2" || { echo "expected claim $2, got:" >&2; claims "$1" >&2; exit 1; }
}

totp() {
  python3 -c 'import base64,hmac,struct,sys,time
k=base64.b32decode(sys.argv[1]+"="*(-len(sys.argv[1])%8))
h=hmac.new(k,struct.pack(">Q",int(time.time())//30),"sha1").digest()
o=h[-1]&15
print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%1000000))' "$1"
}

# Reads a file from the secrets the seed and bootstrap wrote.
secret_file() {
  if [ -n "${FAYE_DATABASE:-}" ]; then
    cat "${XDG_STATE_HOME:-$HOME/.local/state}/faye/secrets/$1"
  else
    docker exec "$container" cat "/var/lib/faye/demo/$1"
  fi
}
users=$(secret_file users.csv)

# account TENANT totp|none N: sets $id and $pw to the Nth seeded account of TENANT with or without TOTP,
# and $otp to its TOTP secret
account() {
  row=$(printf '%s\n' "$users" | awk -F, -v t="$1" -v k="$2" '$1 == t && (k == "totp") == ($4 != "")' | sed -n "${3}p")
  IFS=, read -r _ id pw otp <<ROW
$row
ROW
  [ -n "$pw" ] || { echo "no seeded $2 account $3 for tenant $1" >&2; exit 1; }
}
account acme none 1; acme_id=$id; acme_pw=$pw                       # password only
account acme totp 1; acme_totp_id=$id; acme_totp_pw=$pw; acme_totp=$otp
account bank totp 1; bank_totp_id=$id; bank_totp_pw=$pw; bank_totp=$otp
account bank none 1; bank_enroll_id=$id; bank_enroll_pw=$pw          # enrolls TOTP in step 4
account bank none 2; bank_id=$id; bank_pw=$pw                       # password only
unset users row id pw otp

# Admin API call; prints the response body and fails on an HTTP error.
admin() { curl -fsS -H "Authorization: Bearer $token" "$@"; }
# The Admin UI's proxy to the admin API, as the browser calls it: no sign in, x-faye-admin on writes.
ui() { curl -fsS -H 'x-faye-admin: 1' "$@"; }

# The id of the application the console lists under this name.
client_id() {
  for t in acme bank; do ui "http://localhost:3002/api/admin/tenants/$t/clients"; done \
    | python3 -c 'import json,sys
name=sys.argv[1]
for doc in sys.stdin.read().replace("][", "]\n[").splitlines():
    for c in json.loads(doc):
        if c["name"] == name: print(c["id"])' "$1"
}
acme_web=$(client_id "Acme Web")
acme_partner=$(client_id "Acme Partner Portal")
bank_online=$(client_id "Bank Online")
[ -n "$acme_web" ] && [ -n "$acme_partner" ] && [ -n "$bank_online" ] || { echo "expected the generated applications" >&2; exit 1; }

echo "1. password only (tenant acme, Acme Web, low risk)"
s=$(start "client=$acme_web")
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
page=$(answer "$s" "{\"password\":\"$acme_pw\"}")
printf '%s' "$page" | grep -q "$acme_id" || { echo "expected the account's email in the token" >&2; exit 1; }
expect_claim "$page" 'amr = ["pwd"]'
expect_claim "$page" 'acr = "faye:1fa"'

echo "2. acr_values=faye:2fa on a password only sign in is refused"
s=$(start "client=$acme_web&acr_values=faye:2fa")
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
page=$(answer "$s" "{\"password\":\"$acme_pw\"}")
printf '%s' "$page" | grep -q 'acr_not_satisfied' || { echo "expected acr_not_satisfied" >&2; exit 1; }

echo "3. password plus TOTP (tenant bank, Bank Online)"
s=$(start "client=$bank_online")
expect_screen "$s" identifier
s=$(answer "$s" "{\"identifier\":\"$bank_totp_id\"}")
s=$(answer "$s" "{\"password\":\"$bank_totp_pw\"}")
expect_screen "$s" otp
s=$(answer "$s" "{\"code\":\"$(totp "$bank_totp")\"}")
expect_screen "$s" passkey   # offered a passkey; decline
page=$(answer "$s" '{"skip":true}')
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'

echo "4. step up: acr_values=faye:2fa makes an account without TOTP enroll it (tenant bank)"
s=$(start "client=$bank_online&acr_values=faye:2fa")
s=$(answer "$s" "{\"identifier\":\"$bank_enroll_id\"}")
s=$(answer "$s" "{\"password\":\"$bank_enroll_pw\"}")
expect_screen "$s" otp_enroll
secret=$(printf '%s' "$s" | field secret)
s=$(answer "$s" "{\"code\":\"$(totp "$secret")\"}")
page=$(answer "$s" '{"skip":true}')
printf '%s' "$page" | grep -q "$bank_enroll_id" || { echo "expected the account's email in the token" >&2; exit 1; }
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'

echo "5. consent for a third party application (tenant acme, Acme Partner Portal)"
s=$(start "client=$acme_partner")
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
s=$(answer "$s" "{\"password\":\"$acme_pw\"}")
expect_screen "$s" consent
page=$(answer "$s" '{"decision":"allow"}')
printf '%s' "$page" | grep -q "$acme_id" || { echo "expected the email claim" >&2; exit 1; }

echo "M2 sign in ok"

echo "6. a version published mid sign in does not affect it (tenant acme)"
s=$(start "client=$acme_web")
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
before=$(admin http://localhost:8080/admin/tenants/acme/versions | field current)
published=$(admin -X POST -H 'content-type: application/yaml' --data-binary @- \
  http://localhost:8080/admin/tenants/acme/publish <<'YAML' | field versionHash
flow: password-totp
version: 2
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    credential: { retry: 3, screen: password, use: password.verify }
    second-factor: { retry: 3, screen: otp, use: totp.verify }
YAML
)
[ -n "$published" ] && [ "$published" != "$before" ] || { echo "publish did not make a new version" >&2; exit 1; }
page=$(answer "$s" "{\"password\":\"$acme_pw\"}")   # still the version it started on
expect_claim "$page" 'amr = ["pwd"]'
s=$(start "client=$acme_web")                          # a new sign in runs the new version
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
s=$(answer "$s" "{\"password\":\"$acme_pw\"}")
expect_screen "$s" otp
admin -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$before\"}" \
  http://localhost:8080/admin/tenants/acme/current >/dev/null

echo "7. a flow with a branch that skips the credential is refused with its YAML line (tenant acme)"
status=$(curl -sS -o "$body" -w '%{http_code}' -H "Authorization: Bearer $token" -X POST \
  -H 'content-type: application/yaml' --data-binary @- http://localhost:8080/admin/tenants/acme/publish <<'YAML'
flow: lax
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    credential:
      alternative:
        password: { screen: password, use: password.verify }
        trusted: { if: { eq: [request.ip, "10.0.0.1"] }, set: { trusted: true } }
YAML
)
[ "$status" = 422 ] && grep -q '"source":"acme.yaml:9:24"' "$body" \
  || { echo "expected 422 at acme.yaml:9:24, got $status: $(cat "$body")" >&2; exit 1; }
[ "$(admin http://localhost:8080/admin/tenants/acme/versions | field current)" = "$before" ] \
  || { echo "tenant acme is not back on its original version" >&2; exit 1; }

echo "M3 versioning and checks ok"

echo "8. a risk hook routes high risk sign ins to TOTP (tenant acme)"
s=$(start "client=$acme_web")
s=$(answer "$s" "{\"identifier\":\"$acme_totp_id\"}")
page=$(answer "$s" "{\"password\":\"$acme_totp_pw\"}")
expect_claim "$page" 'amr = ["pwd"]'
ua=suspicious-smoke/1.0
s=$(start "client=$acme_web")
s=$(answer "$s" "{\"identifier\":\"$acme_totp_id\"}")
s=$(answer "$s" "{\"password\":\"$acme_totp_pw\"}")
expect_screen "$s" otp
page=$(answer "$s" "{\"code\":\"$(totp "$acme_totp")\"}")
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'
ua=faye-smoke

echo "9. malicious hooks fail safely (tenant acme)"
for hook in 'export default function () { for (;;) {} }' \
            "export default () => ({ succeed: true, subject: 'someone', amr: ['pwd'] })"; do
  admin -X POST -H 'content-type: application/yaml' --data-binary @- \
    http://localhost:8080/admin/tenants/acme/publish >/dev/null <<YAML
flow: hostile
version: 1
hooks:
  hostile: { source: "$hook" }
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    hostile: { hook: hostile }
    credential: { screen: password, use: password.verify }
YAML
  s=$(start "client=$acme_web")
  page=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
  printf '%s' "$page" | grep -q 'hook_error' || { echo "expected hook_error for: $hook" >&2; exit 1; }
done
admin -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$before\"}" \
  http://localhost:8080/admin/tenants/acme/current >/dev/null

echo "M4 hooks ok"

echo "10. the Admin UI needs no sign in; drafts, checks, publishes and brands through its proxy"
page=$(curl -fsS http://localhost:3002/)
printf '%s' "$page" | grep -q '>Tenants<' && printf '%s' "$page" | grep -q '>acme<' && printf '%s' "$page" | grep -q '>bank<' \
  || { echo "expected the Admin UI tenant list with acme and bank" >&2; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:3002/signin)" = 404 ] || { echo "expected no sign in route" >&2; exit 1; }
[ "$(ui http://localhost:3002/api/admin/tenants | grep -o '"id":"[a-z]*"' | sort | tr -d '\n')" = '"id":"acme""id":"bank"' ] \
  || { echo "expected exactly the tenants acme and bank" >&2; exit 1; }
# A write without the header is refused
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'content-type: application/json' -d '{"yaml":""}' \
  http://localhost:3002/api/admin/tenants/acme/draft)" = 403 ] || { echo "expected 403 without x-faye-admin" >&2; exit 1; }
original=$(ui http://localhost:3002/api/admin/tenants/acme/draft)
draft='flow: password-totp\nversion: 3\nlogin:\n  steps:\n    identify: { screen: identifier, use: identity.resolve }\n    credential: { retry: 3, screen: password, use: password.verify }\n    second-factor: { retry: 3, screen: otp, use: totp.verify }\n'
ui -X PUT -H 'content-type: application/json' -d "{\"yaml\":\"$draft\"}" http://localhost:3002/api/admin/tenants/acme/draft >/dev/null
ui http://localhost:3002/api/admin/tenants/acme/draft | grep -q '"origin":"draft"' || { echo "expected the saved draft" >&2; exit 1; }
yaml=$(printf "$draft")
ui -X POST -H 'content-type: application/yaml' --data-binary "$yaml" http://localhost:3002/api/admin/tenants/acme/validate \
  | grep -q '"ok":true' || { echo "expected the draft to pass the checker" >&2; exit 1; }
published=$(ui -X POST -H 'content-type: application/yaml' --data-binary "$yaml" \
  http://localhost:3002/api/admin/tenants/acme/publish | field versionHash)
ui "http://localhost:3002/api/admin/tenants/acme/versions/$published" | grep -q 'version: 3' \
  || { echo "expected the published version to keep its YAML" >&2; exit 1; }
ui -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$before\"}" \
  http://localhost:3002/api/admin/tenants/acme/current >/dev/null
ui -X PUT -H 'content-type: application/json' -d "$original" http://localhost:3002/api/admin/tenants/acme/draft >/dev/null
ui -X PUT -H 'content-type: application/json' -d '{"mfaRequired":false,"branding":{"name":"Acme","primaryColor":"#0a7cff"}}' \
  http://localhost:3002/api/admin/tenants/acme/settings >/dev/null
s=$(start "client=$acme_web")
printf '%s' "$s" | grep -q '"branding":{"name":"Acme","primaryColor":"#0a7cff"}' || { echo "expected branding on the screen: $s" >&2; exit 1; }
ui -X PUT -H 'content-type: application/json' -d '{"mfaRequired":false}' http://localhost:3002/api/admin/tenants/acme/settings >/dev/null

echo "M5 Admin UI ok"

echo "11. preview: a draft runs for the preview client, with a scenario and a seeded account, traced live (tenant acme)"
began=$(date +%s)
acme_current=$(ui http://localhost:3002/api/admin/tenants/acme/versions | field current)
# The draft differs from the published flow only in its id, so a run on it is a run on the draft
python3 -c 'import json,sys; print(json.dumps({"yaml": sys.stdin.read()}))' <<'YAML' | \
  ui -X PUT -H 'content-type: application/json' -d @- http://localhost:3002/api/admin/tenants/acme/draft >/dev/null
flow: risk-totp-preview
version: 1
hooks:
  risk: { file: risk.js, capabilities: { http: [ip-reputation] }, timeout_ms: 500 }
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    risk: { hook: risk }
    credential: { retry: 3, screen: password, use: password.verify }
    second-factor:
      if: { eq: [vars.risk, high] }
      alternative:
        totp: { if: user.has_totp, retry: 3, screen: otp, use: totp.verify }
        enroll: { if: { not: user.has_totp }, retry: 3, screen: otp_enroll, use: totp.enroll }
YAML
ui -X PUT -H 'content-type: application/json' -d '{"request":{"ip":"203.0.113.7"}}' \
  http://localhost:3002/api/admin/tenants/acme/preview/scenario >/dev/null
seeded=$(ui -X POST -H 'content-type: application/json' -d '{"totp":true}' http://localhost:3002/api/admin/tenants/acme/preview/users)
tester=$(printf '%s' "$seeded" | field identifier)
tester_password=$(printf '%s' "$seeded" | field password)
tester_secret=$(printf '%s' "$seeded" | field totpSecret)
# Follow the trace feed through the Admin UI's proxy, as the Preview panel does
curl -sN --max-time 40 http://localhost:3002/api/admin/tenants/acme/preview/events > "$events" &
feed=$!
sleep 1
s=$(start "client=preview-acme&login_hint=$tester")
preview_flow=$(printf '%s' "$s" | field flowId)
s=$(answer "$s" "{\"identifier\":\"$tester\"}")
s=$(answer "$s" "{\"password\":\"$tester_password\"}")
expect_screen "$s" otp   # the scenario's IP scores high
page=$(answer "$s" "{\"code\":\"$(totp "$tester_secret")\"}")
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'
printf '%s' "$page" | grep -q 'data-expectation="match"' || { echo "expected the demo client's expectation check to match" >&2; exit 1; }
curl -fsS "http://localhost:8080/api/preview/flows/$preview_flow" | grep -q '"draft":true' \
  || { echo "expected the preview to have run the draft" >&2; exit 1; }
sleep 1
{ kill "$feed" && wait "$feed"; } 2>/dev/null || true
grep -q "^event:trace" "$events" && grep -q "\"flowId\":\"$preview_flow\"" "$events" \
  || { echo "expected the preview's events on the trace feed, got: $(head -c 400 "$events")" >&2; exit 1; }
[ "$(ui http://localhost:3002/api/admin/tenants/acme/versions | field current)" = "$acme_current" ] \
  || { echo "the preview published something" >&2; exit 1; }
took=$(( $(date +%s) - began ))
[ "$took" -lt 60 ] || { echo "one preview iteration took ${took}s, the M6 exit allows under 60" >&2; exit 1; }
echo "   one iteration (draft, scenario, account, sign in, token, trace) took ${took}s"
# The seeded preview account is unknown to the tenant's production applications
s=$(start "client=$acme_web")
s=$(answer "$s" "{\"identifier\":\"$tester\"}")
s=$(answer "$s" "{\"password\":\"$tester_password\"}")
printf '%s' "$s" | grep -q 'invalid_credentials' || { echo "expected the preview account to fail on Acme Web: $s" >&2; exit 1; }
ui -X PUT -H 'content-type: application/json' -d '{"request":{}}' http://localhost:3002/api/admin/tenants/acme/preview/scenario >/dev/null
ui -X PUT -H 'content-type: application/json' -d "$original" http://localhost:3002/api/admin/tenants/acme/draft >/dev/null

echo "M6 preview ok"

echo "12. every application the console lists signs in its tenant's users, as its Try sign in does"
apps=0
for tenant in acme bank; do
  if [ "$tenant" = acme ]; then id=$acme_id; pw=$acme_pw; else id=$bank_id; pw=$bank_pw; fi
  listed=$(ui "http://localhost:3002/api/admin/tenants/$tenant/clients")
  printf '%s' "$listed" | grep -q '"client_secret"' && { echo "a client secret reached the admin API" >&2; exit 1; }
  for client in $(printf '%s' "$listed" | python3 -c 'import json,sys
for c in json.load(sys.stdin):
    if not c["preview"]: print(c["id"])'); do
    s=$(start "client=$client&prompt=login")
    s=$(answer "$s" "{\"identifier\":\"$id\"}")
    page=$(answer "$s" "{\"password\":\"$pw\"}")
    if printf '%s' "$page" | grep -q '"type":"consent"'; then page=$(answer "$page" '{"decision":"allow"}'); fi
    printf '%s' "$page" | grep -q "$id" && printf '%s' "$page" | grep -q "$client" \
      || { echo "expected a $tenant account signed in to $client, got: $(printf '%s' "$page" | head -c 400)" >&2; exit 1; }
    expect_claim "$page" 'amr = ["pwd"]'
    apps=$((apps + 1))
  done
done
[ "$apps" = 10 ] || { echo "expected ten applications, signed in to $apps" >&2; exit 1; }
# A tenant's users are unknown to the other tenant's applications, so the right password fails
s=$(start "client=$bank_online")
s=$(answer "$s" "{\"identifier\":\"$acme_id\"}")
s=$(answer "$s" "{\"password\":\"$acme_pw\"}")
printf '%s' "$s" | grep -q 'invalid_credentials' || { echo "expected the acme account to be unknown to tenant bank" >&2; exit 1; }
# The console lists each pool read only, with no password or hash
pool=$(ui http://localhost:3002/api/admin/tenants/acme/users)
printf '%s' "$pool" | grep -q "\"identifier\":\"$acme_id\"" || { echo "expected the account in acme's users" >&2; exit 1; }
printf '%s' "$pool" | grep -q '\$argon2' && { echo "a hash reached the admin API" >&2; exit 1; }
ui http://localhost:3002/api/admin/tenants/bank/users | grep -q "\"identifier\":\"$acme_id\"" \
  && { echo "the acme account is listed for tenant bank" >&2; exit 1; }

echo "tenants, applications and user pools ok"

echo "13. one SQLite database holds Hydra's tables and Faye's"
tables="SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('hydra_client', 'faye_user', 'faye_flow', 'faye_version', 'faye_schema_history') ORDER BY name"
if [ -n "${FAYE_DATABASE:-}" ]; then
  found=$(node -e 'const { DatabaseSync } = require("node:sqlite"); const db = new DatabaseSync(process.argv[1], { readOnly: true }); console.log(db.prepare(process.argv[2]).all().map((r) => r.name).join(","))' "$FAYE_DATABASE" "$tables")
else
  found=$(docker exec -u 100:101 "$container" node -e 'const { DatabaseSync } = require("node:sqlite"); const db = new DatabaseSync(process.argv[1], { readOnly: true }); console.log(db.prepare(process.argv[2]).all().map((r) => r.name).join(","))' /var/lib/faye/faye.db "$tables" 2>/dev/null)
fi
[ "$found" = faye_flow,faye_schema_history,faye_user,faye_version,hydra_client ] || { echo "expected Hydra's and Faye's tables in one database, found: $found" >&2; exit 1; }

echo "single database ok"
