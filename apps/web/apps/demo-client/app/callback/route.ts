import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { client, decodeJwt, ID_TOKEN_COOKIE, oidcConfig, type Pending, PENDING_COOKIE, redirectUri } from "../../lib/oidc";

/** Exchanges the code for tokens, checks state and nonce, and keeps the ID token for display. */
export async function GET(request: Request) {
  const config = oidcConfig();
  const params = new URL(request.url).searchParams;
  const done = (query: Record<string, string>) => {
    const res = NextResponse.redirect(new URL(`/?${new URLSearchParams(query)}`, config.baseUrl));
    res.cookies.delete(PENDING_COOKIE);
    // Every outcome replaces the last sign in, so a failed one never shows an older ID token
    res.cookies.delete(ID_TOKEN_COOKIE);
    return res;
  };

  const raw = (await cookies()).get(PENDING_COOKIE)?.value;
  const pending = raw ? (JSON.parse(raw) as Pending) : null;
  if (!pending || params.get("state") !== pending.state) {
    return done({ error: "invalid_state", error_description: "State does not match this browser's sign in." });
  }
  const error = params.get("error");
  if (error) return done({ error, error_description: params.get("error_description") ?? "" });

  // A confidential application authenticates with its secret (client_secret_basic); a public one with PKCE alone
  const secret = client(pending.clientId)?.secret;
  const headers: Record<string, string> = { "content-type": "application/x-www-form-urlencoded" };
  if (secret) {
    const basic = `${encodeURIComponent(pending.clientId)}:${encodeURIComponent(secret)}`;
    headers.authorization = `Basic ${Buffer.from(basic).toString("base64")}`;
  }
  const tokenRes = await fetch(config.tokenUrl, {
    method: "POST",
    headers,
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: params.get("code") ?? "",
      redirect_uri: redirectUri(),
      client_id: pending.clientId,
      code_verifier: pending.verifier,
    }),
    cache: "no-store",
  });
  const tokens = (await tokenRes.json()) as { id_token?: string; error?: string; error_description?: string };
  if (!tokenRes.ok || !tokens.id_token) {
    return done({ error: tokens.error ?? "token_error", error_description: tokens.error_description ?? `HTTP ${tokenRes.status}` });
  }
  if (decodeJwt(tokens.id_token).claims.nonce !== pending.nonce) {
    return done({ error: "invalid_nonce", error_description: "ID token nonce does not match." });
  }

  const res = done({});
  res.cookies.set(ID_TOKEN_COOKIE, tokens.id_token, { httpOnly: true, sameSite: "lax", path: "/" });
  return res;
}
