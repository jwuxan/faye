#!/bin/sh
# M2 to M6 checks against a running stack (compose, or hydra-local.sh plus local apps): signs
# in from the demo client through Hydra, Faye and the Login UI for each demo tenant, then reads the
# ID token claims the demo client displays; then publishes through the admin API while a sign in is
# under way; then routes sign ins by a risk hook and runs malicious hooks; then signs in to the
# Admin UI and drafts, checks, publishes and brands through it; then previews a draft through the
# Admin UI and the demo client without publishing it. Needs curl and python3 (for TOTP codes and
# JSON), and FAYE_ADMIN_TOKEN as the server has it
# (default: compose's local one). Run it against a freshly started server: step 4 enrolls TOTP for
# Bob, so a second run finds him already enrolled.
set -eu
token=${FAYE_ADMIN_TOKEN:-dev-admin-token}
jar=$(mktemp)
body=$(mktemp)
ops=$(mktemp)   # the Admin UI session, kept apart from the sign ins that reset $jar
events=$(mktemp)   # the preview trace feed, step 11
trap 'rm -f "$jar" "$body" "$ops" "$events"' EXIT
ua=faye-smoke   # the user agent Faye sees at /login; the ip-reputation stub scores "suspicious" ones high
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
      http://localhost:3002/ | "http://localhost:3002/?"*) curl -fsS -b "$jar" "$url"; return ;;
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

password='"password":"correct horse battery staple"'

echo "1. password only (tenant default)"
s=$(start client=demo)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
page=$(answer "$s" "{$password}")
expect_claim "$page" 'sub = "user-alice"'
expect_claim "$page" 'amr = ["pwd"]'
expect_claim "$page" 'acr = "faye:1fa"'

echo "2. acr_values=faye:2fa on the password only flow is refused"
s=$(start 'client=demo&acr_values=faye:2fa')
s=$(answer "$s" '{"identifier":"alice@example.com"}')
page=$(answer "$s" "{$password}")
printf '%s' "$page" | grep -q 'acr_not_satisfied' || { echo "expected acr_not_satisfied" >&2; exit 1; }

echo "3. password plus TOTP (tenant mfa)"
s=$(start client=demo-mfa)
expect_screen "$s" identifier
s=$(answer "$s" '{"identifier":"alice@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" otp
s=$(answer "$s" "{\"code\":\"$(totp JBSWY3DPEHPK3PXP)\"}")
expect_screen "$s" passkey   # offered a passkey; decline
page=$(answer "$s" '{"skip":true}')
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'

echo "4. step up: acr_values=faye:2fa makes Bob enroll TOTP (tenant mfa)"
s=$(start 'client=demo-mfa&acr_values=faye:2fa')
s=$(answer "$s" '{"identifier":"bob@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" otp_enroll
secret=$(printf '%s' "$s" | field secret)
s=$(answer "$s" "{\"code\":\"$(totp "$secret")\"}")
page=$(answer "$s" '{"skip":true}')
expect_claim "$page" 'sub = "user-bob"'
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'

echo "5. consent for a third party client (tenant partner)"
s=$(start client=partner-app)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" consent
page=$(answer "$s" '{"decision":"allow"}')
expect_claim "$page" 'sub = "user-alice"'
printf '%s' "$page" | grep -q 'alice@example.com' || { echo "expected the email claim" >&2; exit 1; }

echo "M2 sign in ok"

# Admin API call; prints the response body and fails on an HTTP error.
admin() { curl -fsS -H "Authorization: Bearer $token" "$@"; }

echo "6. a version published mid sign in does not affect it (tenant default)"
s=$(start client=demo)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
before=$(admin http://localhost:8080/admin/tenants/default/versions | field current)
published=$(admin -X POST -H 'content-type: application/yaml' --data-binary @- \
  http://localhost:8080/admin/tenants/default/publish <<'YAML' | field versionHash
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
page=$(answer "$s" "{$password}")   # still the password only version it started on
expect_claim "$page" 'amr = ["pwd"]'
s=$(start client=demo)               # a new sign in runs the new version
s=$(answer "$s" '{"identifier":"alice@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" otp
admin -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$before\"}" \
  http://localhost:8080/admin/tenants/default/current >/dev/null

echo "7. a flow with a branch that skips the credential is refused with its YAML line (tenant default)"
status=$(curl -sS -o "$body" -w '%{http_code}' -H "Authorization: Bearer $token" -X POST \
  -H 'content-type: application/yaml' --data-binary @- http://localhost:8080/admin/tenants/default/publish <<'YAML'
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
[ "$status" = 422 ] && grep -q '"source":"default.yaml:9:24"' "$body" \
  || { echo "expected 422 at default.yaml:9:24, got $status: $(cat "$body")" >&2; exit 1; }
[ "$(admin http://localhost:8080/admin/tenants/default/versions | field current)" = "$before" ] \
  || { echo "tenant default is not back on its original version" >&2; exit 1; }

echo "M3 versioning and checks ok"

echo "8. a risk hook routes high risk sign ins to TOTP (tenant risk)"
s=$(start client=demo-risk)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
page=$(answer "$s" "{$password}")
expect_claim "$page" 'amr = ["pwd"]'
ua=suspicious-smoke/1.0
s=$(start client=demo-risk)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" otp
sleep $((31 - $(date +%s) % 30))   # step 3 used this window's code, and a code is accepted once
page=$(answer "$s" "{\"code\":\"$(totp JBSWY3DPEHPK3PXP)\"}")
expect_claim "$page" 'amr = ["pwd","otp"]'
expect_claim "$page" 'acr = "faye:2fa"'
ua=faye-smoke

echo "9. malicious hooks fail safely (tenant risk)"
risk_before=$(admin http://localhost:8080/admin/tenants/risk/versions | field current)
for hook in 'export default function () { for (;;) {} }' \
            "export default () => ({ succeed: true, subject: 'user-alice', amr: ['pwd'] })"; do
  admin -X POST -H 'content-type: application/yaml' --data-binary @- \
    http://localhost:8080/admin/tenants/risk/publish >/dev/null <<YAML
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
  s=$(start client=demo-risk)
  page=$(answer "$s" '{"identifier":"alice@example.com"}')
  printf '%s' "$page" | grep -q 'hook_error' || { echo "expected hook_error for: $hook" >&2; exit 1; }
done
admin -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$risk_before\"}" \
  http://localhost:8080/admin/tenants/risk/current >/dev/null

echo "M4 hooks ok"

echo "10. an operator signs in to the Admin UI with password and TOTP, then works through its proxy"
: > "$jar"
s=$(walk http://localhost:3002/signin)
s=$(answer "$s" '{"identifier":"alice@example.com"}')
s=$(answer "$s" "{$password}")
expect_screen "$s" otp
sleep $((31 - $(date +%s) % 30))   # step 8 used this window's code
page=$(answer "$s" "{\"code\":\"$(totp JBSWY3DPEHPK3PXP)\"}")
printf '%s' "$page" | grep -q '>Tenants<' || { echo "expected the Admin UI tenant list, got: $page" >&2; exit 1; }
cp "$jar" "$ops"
ui() { curl -fsS -b "$ops" -H 'x-faye-admin: 1' "$@"; }
ui http://localhost:3002/api/admin/tenants | grep -q '"id":"default"' || { echo "expected tenants through the proxy" >&2; exit 1; }
# Without a session, or without the header on a write, the proxy refuses
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:3002/api/admin/tenants)" = 401 ] \
  || { echo "expected 401 without a session" >&2; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$ops" -X PUT -H 'content-type: application/json' -d '{"yaml":""}' \
  http://localhost:3002/api/admin/tenants/default/draft)" = 403 ] || { echo "expected 403 without x-faye-admin" >&2; exit 1; }
draft='flow: password-totp\nversion: 3\nlogin:\n  steps:\n    identify: { screen: identifier, use: identity.resolve }\n    credential: { retry: 3, screen: password, use: password.verify }\n    second-factor: { retry: 3, screen: otp, use: totp.verify }\n'
ui -X PUT -H 'content-type: application/json' -d "{\"yaml\":\"$draft\"}" http://localhost:3002/api/admin/tenants/default/draft >/dev/null
ui http://localhost:3002/api/admin/tenants/default/draft | grep -q '"origin":"draft"' || { echo "expected the saved draft" >&2; exit 1; }
yaml=$(printf "$draft")
ui -X POST -H 'content-type: application/yaml' --data-binary "$yaml" http://localhost:3002/api/admin/tenants/default/validate \
  | grep -q '"ok":true' || { echo "expected the draft to pass the checker" >&2; exit 1; }
published=$(ui -X POST -H 'content-type: application/yaml' --data-binary "$yaml" \
  http://localhost:3002/api/admin/tenants/default/publish | field versionHash)
ui "http://localhost:3002/api/admin/tenants/default/versions/$published" | grep -q 'version: 3' \
  || { echo "expected the published version to keep its YAML" >&2; exit 1; }
ui -X POST -H 'content-type: application/json' -d "{\"versionHash\":\"$before\"}" \
  http://localhost:3002/api/admin/tenants/default/current >/dev/null
ui -X PUT -H 'content-type: application/json' -d '{"mfaRequired":false,"branding":{"name":"Acme","primaryColor":"#0a7cff"}}' \
  http://localhost:3002/api/admin/tenants/default/settings >/dev/null
s=$(start client=demo)
printf '%s' "$s" | grep -q '"branding":{"name":"Acme","primaryColor":"#0a7cff"}' || { echo "expected branding on the screen: $s" >&2; exit 1; }
ui -X PUT -H 'content-type: application/json' -d '{"mfaRequired":false}' http://localhost:3002/api/admin/tenants/default/settings >/dev/null

echo "M5 Admin UI ok"

echo "11. preview: a draft runs for the preview client, with a scenario and a seeded account, traced live (tenant risk)"
began=$(date +%s)
risk_current=$(ui http://localhost:3002/api/admin/tenants/risk/versions | field current)
original=$(ui http://localhost:3002/api/admin/tenants/risk/draft)
# The draft differs from the published flow only in its id, so a run on it is a run on the draft
python3 -c 'import json,sys; print(json.dumps({"yaml": sys.stdin.read()}))' <<'YAML' | \
  ui -X PUT -H 'content-type: application/json' -d @- http://localhost:3002/api/admin/tenants/risk/draft >/dev/null
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
  http://localhost:3002/api/admin/tenants/risk/preview/scenario >/dev/null
seeded=$(ui -X POST -H 'content-type: application/json' -d '{"totp":true}' http://localhost:3002/api/admin/tenants/risk/preview/users)
tester=$(printf '%s' "$seeded" | field identifier)
tester_password=$(printf '%s' "$seeded" | field password)
tester_secret=$(printf '%s' "$seeded" | field totpSecret)
# Follow the trace feed through the Admin UI's proxy, as the Preview panel does
curl -sN -b "$ops" --max-time 40 http://localhost:3002/api/admin/tenants/risk/preview/events > "$events" &
feed=$!
sleep 1
s=$(start "client=preview-risk&login_hint=$tester")
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
[ "$(ui http://localhost:3002/api/admin/tenants/risk/versions | field current)" = "$risk_current" ] \
  || { echo "the preview published something" >&2; exit 1; }
took=$(( $(date +%s) - began ))
[ "$took" -lt 60 ] || { echo "one preview iteration took ${took}s, the M6 exit allows under 60" >&2; exit 1; }
echo "   one iteration (draft, scenario, account, sign in, token, trace) took ${took}s"
# The seeded account is unknown to the production client
s=$(start client=demo-risk)
s=$(answer "$s" "{\"identifier\":\"$tester\"}")
s=$(answer "$s" "{\"password\":\"$tester_password\"}")
printf '%s' "$s" | grep -q 'invalid_credentials' || { echo "expected the seeded account to fail on demo-risk: $s" >&2; exit 1; }
ui -X PUT -H 'content-type: application/json' -d '{"request":{}}' http://localhost:3002/api/admin/tenants/risk/preview/scenario >/dev/null
ui -X PUT -H 'content-type: application/json' -d "$original" http://localhost:3002/api/admin/tenants/risk/draft >/dev/null

echo "M6 preview ok"
