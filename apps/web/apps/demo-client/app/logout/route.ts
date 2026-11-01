import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { client, decodeJwt, ID_TOKEN_COOKIE, oidcConfig } from "../../lib/oidc";

/** Signs out of this app: forgets the ID token, and for a preview client also ends Hydra's login session. */
export async function POST() {
  const raw = (await cookies()).get(ID_TOKEN_COOKIE)?.value;
  let claims: Record<string, unknown> | null = null;
  try {
    claims = raw ? decodeJwt(raw).claims : null;
  } catch {
    // A malformed token cannot name a session to revoke; still sign out below
  }
  const aud = claims?.aud;
  const clientId = typeof aud === "string" ? aud : Array.isArray(aud) && typeof aud[0] === "string" ? aud[0] : null;
  const sid = claims?.sid;
  if (client(clientId)?.preview && typeof sid === "string") {
    const admin = process.env.HYDRA_ADMIN_URL ?? "http://localhost:4445";
    try {
      const res = await fetch(`${admin}/admin/oauth2/auth/sessions/login?sid=${encodeURIComponent(sid)}`, { method: "DELETE", cache: "no-store" });
      if (!res.ok) console.error(`revoking Hydra login session ${sid} failed: HTTP ${res.status}`);
    } catch (e) {
      console.error("revoking Hydra login session failed:", e);
    }
  }
  const res = NextResponse.redirect(new URL("/", oidcConfig().baseUrl), 303);
  res.cookies.delete(ID_TOKEN_COOKIE);
  return res;
}
