import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { oidcConfig, type Pending, PENDING_COOKIE, REQUIRED_ACR, verifyIdToken } from "../../lib/oidc";
import { seal, SESSION_COOKIE } from "../../lib/session";

/**
 * Redeems the code, verifies the ID token, and signs the operator in only if they proved two
 * factors and are listed in FAYE_ADMIN_SUBJECTS.
 */
export async function GET(request: Request) {
  const config = oidcConfig();
  const params = new URL(request.url).searchParams;
  const done = (error?: string) => {
    const url = new URL("/", config.baseUrl);
    if (error) url.searchParams.set("error", error);
    const res = NextResponse.redirect(url);
    res.cookies.delete(PENDING_COOKIE);
    return res;
  };

  const raw = (await cookies()).get(PENDING_COOKIE)?.value;
  const pending = raw ? (JSON.parse(raw) as Pending) : null;
  if (!pending || params.get("state") !== pending.state) return done("The sign in did not start in this browser.");
  if (params.get("error")) return done(params.get("error_description") || params.get("error")!);

  const tokenRes = await fetch(config.tokenUrl, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: params.get("code") ?? "",
      redirect_uri: config.redirectUri,
      client_id: config.clientId,
      code_verifier: pending.verifier,
    }),
    cache: "no-store",
  });
  const tokens = (await tokenRes.json()) as { id_token?: string; error_description?: string };
  if (!tokenRes.ok || !tokens.id_token) return done(tokens.error_description ?? `token endpoint said ${tokenRes.status}`);

  let claims: Record<string, unknown>;
  try {
    claims = await verifyIdToken(tokens.id_token, pending.nonce);
  } catch (e) {
    return done((e as Error).message);
  }
  if (claims.acr !== REQUIRED_ACR) return done("The Admin UI needs a sign in with two factors.");
  const sub = String(claims.sub);
  if (!config.subjects.includes(sub)) return done(`${sub} is not an operator.`);

  const res = done();
  const session = seal(sub);
  res.cookies.set(SESSION_COOKIE, session.value, { httpOnly: true, sameSite: "lax", path: "/", maxAge: session.maxAge });
  return res;
}
