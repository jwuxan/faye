#!/bin/sh
# Registers the demo OAuth clients in Hydra, one per demo tenant. Safe to rerun.
#   demo         tenant default  (password only)                first party
#   demo-mfa     tenant mfa      (passkey, or password + TOTP)  first party
#   partner-app  tenant partner  (password, consent screen)     third party
#   demo-risk    tenant risk     (password, TOTP if risky)      first party
#   faye-admin   tenant admin    (password + TOTP)              first party, the Admin UI itself
#   preview-<tenant>  for each tenant: runs its draft (metadata.preview), same party as its demo client
set -eu
admin=${HYDRA_ADMIN_URL:-http://localhost:4445}
redirect=${DEMO_REDIRECT_URI:-http://localhost:3001/callback}
admin_redirect=${ADMIN_REDIRECT_URI:-http://localhost:3002/callback}

until curl -fsS "$admin/health/ready" >/dev/null 2>&1; do sleep 1; done

register() { # id name tenant first_party [redirect_uri] [preview]
  if curl -fsS "$admin/admin/clients/$1" >/dev/null 2>&1; then
    echo "client $1 already registered"
    return
  fi
  curl -fsS -X POST "$admin/admin/clients" -H 'Content-Type: application/json' -d @- <<JSON
{
  "client_id": "$1",
  "client_name": "$2",
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "scope": "openid profile email offline_access",
  "redirect_uris": ["${5:-$redirect}"],
  "token_endpoint_auth_method": "none",
  "metadata": { "tenant": "$3", "first_party": $4, "preview": ${6:-false} }
}
JSON
  echo
  echo "client $1 registered"
}

register demo "Faye demo client" default true
register demo-mfa "Faye demo client (MFA)" mfa true
register partner-app "Partner app" partner false
register demo-risk "Faye demo client (risk hook)" risk true
register faye-admin "Faye admin" admin true "$admin_redirect"
# The Admin UI's Preview opens the demo client with these. Faye refuses them unless
# FAYE_PREVIEW_ENABLED is true.
register preview-default "Preview: default" default true "$redirect" true
register preview-mfa "Preview: mfa" mfa true "$redirect" true
register preview-partner "Preview: partner" partner false "$redirect" true
register preview-risk "Preview: risk" risk true "$redirect" true
register preview-admin "Preview: admin" admin true "$redirect" true
