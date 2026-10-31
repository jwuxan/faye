import { createHash, randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import { CLIENTS, oidcConfig, type Pending, PENDING_COOKIE, redirectUri } from "../../lib/oidc";

const random = () => randomBytes(32).toString("base64url");

/** Optional authorization request parameters the launch panel can set. */
const PASSTHROUGH = ["acr_values", "login_hint", "prompt", "max_age", "ui_locales"] as const;

/** Starts an authorization code flow with PKCE, for the client and parameters in the query. */
export function GET(request: Request) {
  const config = oidcConfig();
  const query = new URL(request.url).searchParams;
  const requested = query.get("client");
  const clientId = CLIENTS.some((c) => c.id === requested) ? requested! : config.clientId;
  const pending: Pending = { state: random(), nonce: random(), verifier: random(), clientId };
  const params = new URLSearchParams({
    response_type: "code",
    client_id: clientId,
    redirect_uri: redirectUri(),
    scope: config.scope,
    state: pending.state,
    nonce: pending.nonce,
    code_challenge: createHash("sha256").update(pending.verifier).digest("base64url"),
    code_challenge_method: "S256",
  });
  for (const name of PASSTHROUGH) {
    const value = query.get(name);
    if (value) params.set(name, value);
  }
  const url = new URL(config.authorizeUrl);
  url.search = params.toString();

  const res = NextResponse.redirect(url);
  res.cookies.set(PENDING_COOKIE, JSON.stringify(pending), { httpOnly: true, sameSite: "lax", path: "/", maxAge: 600 });
  return res;
}
