/**
 * The Admin UI signs operators in through Hydra as client faye-admin, whose tenant (admin) runs a
 * password plus TOTP flow. Read per request so one build runs against any Hydra. Server only.
 *
 * HYDRA_PUBLIC_URL is what the browser reaches (and the token issuer); HYDRA_INTERNAL_URL is how
 * this server reaches the same Hydra, which differs inside compose.
 */
import { createPublicKey, verify, type webcrypto } from "node:crypto";

export function oidcConfig() {
  const publicUrl = process.env.HYDRA_PUBLIC_URL ?? "http://localhost:4444";
  const internalUrl = process.env.HYDRA_INTERNAL_URL ?? publicUrl;
  const baseUrl = process.env.ADMIN_URL ?? "http://localhost:3002";
  return {
    issuer: publicUrl,
    authorizeUrl: `${publicUrl}/oauth2/auth`,
    tokenUrl: `${internalUrl}/oauth2/token`,
    jwksUrl: `${internalUrl}/.well-known/jwks.json`,
    clientId: process.env.ADMIN_CLIENT_ID ?? "faye-admin",
    redirectUri: `${baseUrl}/callback`,
    baseUrl,
    /** Only these subjects may use the Admin UI. Empty: nobody. */
    subjects: (process.env.FAYE_ADMIN_SUBJECTS ?? "").split(",").map((s) => s.trim()).filter(Boolean),
  };
}

/** The assurance every operator sign in must reach. */
export const REQUIRED_ACR = "faye:2fa";

/** Cookie holding state, nonce and PKCE verifier between /signin and /callback. */
export const PENDING_COOKIE = "faye_admin_pending";

export type Pending = { state: string; nonce: string; verifier: string };

/**
 * Verifies an ID token from Hydra's token endpoint: RS256 signature against Hydra's keys, issuer,
 * audience, expiry and nonce. Returns its claims, or throws with the reason.
 */
export async function verifyIdToken(jwt: string, nonce: string): Promise<Record<string, unknown>> {
  const config = oidcConfig();
  const [h, c, s] = jwt.split(".");
  if (!h || !c || !s) throw new Error("malformed ID token");
  const header = JSON.parse(Buffer.from(h, "base64url").toString("utf8")) as { alg?: string; kid?: string };
  if (header.alg !== "RS256") throw new Error(`unexpected ID token alg ${header.alg}`);
  const res = await fetch(config.jwksUrl, { cache: "no-store" });
  const { keys } = (await res.json()) as { keys: (webcrypto.JsonWebKey & { kid?: string; use?: string })[] };
  const jwk = keys.find((k) => k.kid === header.kid && (k.use ?? "sig") === "sig");
  if (!jwk) throw new Error("ID token signed by an unknown key");
  const signed = verify("RSA-SHA256", Buffer.from(`${h}.${c}`), createPublicKey({ key: jwk, format: "jwk" }), Buffer.from(s, "base64url"));
  if (!signed) throw new Error("bad ID token signature");

  const claims = JSON.parse(Buffer.from(c, "base64url").toString("utf8")) as Record<string, unknown>;
  const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (String(claims.iss).replace(/\/$/, "") !== config.issuer.replace(/\/$/, "")) throw new Error("wrong issuer");
  if (!aud.includes(config.clientId)) throw new Error("wrong audience");
  if (typeof claims.exp !== "number" || claims.exp < Date.now() / 1000) throw new Error("expired ID token");
  if (claims.nonce !== nonce) throw new Error("nonce does not match");
  return claims;
}
