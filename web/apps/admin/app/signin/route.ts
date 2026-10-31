import { createHash, randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import { oidcConfig, type Pending, PENDING_COOKIE, REQUIRED_ACR } from "../../lib/oidc";

const random = () => randomBytes(32).toString("base64url");

/** Starts an authorization code flow with PKCE, asking for two factors. */
export function GET() {
  const config = oidcConfig();
  const pending: Pending = { state: random(), nonce: random(), verifier: random() };
  const url = new URL(config.authorizeUrl);
  url.search = new URLSearchParams({
    response_type: "code",
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    scope: "openid",
    state: pending.state,
    nonce: pending.nonce,
    code_challenge: createHash("sha256").update(pending.verifier).digest("base64url"),
    code_challenge_method: "S256",
    acr_values: REQUIRED_ACR,
    prompt: "login",
  }).toString();
  const res = NextResponse.redirect(url);
  res.cookies.set(PENDING_COOKIE, JSON.stringify(pending), { httpOnly: true, sameSite: "lax", path: "/", maxAge: 600 });
  return res;
}
