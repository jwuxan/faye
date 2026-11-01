import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

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
    baseUrl: process.env.DEMO_CLIENT_URL ?? "http://localhost:3001",
    scope: "openid profile email offline_access",
  };
}

/** @param secret null for a public client, which redeems codes with PKCE alone */
/** thirdParty: registered without metadata.first_party, so the tenant's flow may ask for consent. */
/** preview: the tenant's draft client; signing out of it also ends the Hydra login session. */
export type Client = { id: string; label: string; tenant: string; secret: string | null; thirdParty: boolean; preview: boolean };

/** Where deploy/bootstrap.mjs appends Hydra's response for each application it registers. */
function registrations(): string {
  if (process.env.CLIENT_REGISTRATIONS) return process.env.CLIENT_REGISTRATIONS;
  const state = process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state");
  return join(state, "faye", "secrets", "clients.jsonl");
}

/**
 * The clients deploy/bootstrap.mjs registers, all sharing this app's redirect URI: the preview
 * client of each tenant (metadata.preview = true), which runs the tenant's draft, and the ten
 * applications whose ids and secrets Hydra generated. This app is their relying party, so it reads
 * their secrets from CLIENT_REGISTRATIONS (the secrets volume in compose); none is in the repo.
 * Read per request, so applications registered after start appear.
 */
export function clients(): Client[] {
  const previews: Client[] = ["acme", "bank"].map((tenant) => ({
    id: `preview-${tenant}`,
    label: `Preview: tenant ${tenant}'s draft`,
    tenant,
    secret: null,
    thirdParty: false,
    preview: true,
  }));
  let lines: string[] = [];
  try {
    lines = readFileSync(registrations(), "utf8").split("\n");
  } catch {
    // No applications registered yet
  }
  const apps = lines
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line) as { client_id: string; client_name: string; client_secret?: string; metadata?: { tenant?: string; first_party?: boolean } })
    .map((c) => ({
      id: c.client_id,
      label: c.client_name,
      tenant: c.metadata?.tenant ?? "",
      secret: c.client_secret ?? null,
      thirdParty: c.metadata?.first_party !== true,
      preview: false,
    }));
  return [...apps.sort((a, b) => a.tenant.localeCompare(b.tenant) || a.label.localeCompare(b.label)), ...previews];
}

export function client(id: string | null): Client | undefined {
  return clients().find((c) => c.id === id);
}

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
