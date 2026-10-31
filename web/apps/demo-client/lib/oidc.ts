/**
 * Demo relying party settings, read per request so one build runs against any Hydra.
 *
 * HYDRA_PUBLIC_URL is what the browser reaches (and the token issuer); HYDRA_INTERNAL_URL is how
 * this server reaches the same Hydra, which differs inside compose.
 */
export function oidcConfig() {
  const publicUrl = process.env.HYDRA_PUBLIC_URL ?? "http://localhost:4444";
  return {
    authorizeUrl: `${publicUrl}/oauth2/auth`,
    tokenUrl: `${process.env.HYDRA_INTERNAL_URL ?? publicUrl}/oauth2/token`,
    clientId: process.env.DEMO_CLIENT_ID ?? "demo",
    baseUrl: process.env.DEMO_CLIENT_URL ?? "http://localhost:3001",
    scope: "openid profile email offline_access",
  };
}

/**
 * Clients registered by deploy/bootstrap.sh, one per demo tenant, and a preview client per tenant
 * (metadata.preview = true) that runs the tenant's draft instead of its published flow. All are
 * public PKCE clients sharing this app's redirect URI; DEMO_CLIENT_ID picks the default.
 */
export const CLIENTS = [
  { id: "demo", label: "Password only", tenant: "default" },
  { id: "demo-mfa", label: "Passkey, or password plus TOTP", tenant: "mfa" },
  { id: "partner-app", label: "Third party app (asks for consent)", tenant: "partner" },
  { id: "demo-risk", label: "Password, plus TOTP when the risk hook scores high", tenant: "risk" },
  ...["default", "mfa", "partner", "risk", "admin"].map((tenant) => ({
    id: `preview-${tenant}`,
    label: `Preview: tenant ${tenant}'s draft`,
    tenant,
  })),
];

export function redirectUri(): string {
  return `${oidcConfig().baseUrl}/callback`;
}

/** Cookie holding state, nonce and PKCE verifier between /login and /callback. */
export const PENDING_COOKIE = "demo_pending";
/** Cookie holding the last ID token, shown on the home page. */
export const ID_TOKEN_COOKIE = "demo_id_token";

/** @param clientId the client the code is redeemed for */
export type Pending = { state: string; nonce: string; verifier: string; clientId: string };

/** Decodes a JWT without verifying it: the token came straight from the token endpoint. */
export function decodeJwt(jwt: string): { header: Record<string, unknown>; claims: Record<string, unknown> } {
  const [header, claims] = jwt.split(".").slice(0, 2).map((part) => JSON.parse(Buffer.from(part, "base64url").toString("utf8")));
  return { header, claims };
}
