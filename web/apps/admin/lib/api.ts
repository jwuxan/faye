/** The admin API as the browser sees it, through this app's proxy. */
export type Problem = { source: string; line: number; column: number; message: string };
export type PathView = {
  /** The id of the entry the path runs through. */
  entry: string;
  path: string;
  succeeds: boolean;
  amr: string[];
  acr: string | null;
  /** Visit keys (see visitKeys in flow.ts) of the steps and options the path goes through. */
  visits: string[];
};
export type Validation = { ok: boolean; errors: Problem[]; warnings: Problem[]; summary: string; paths: PathView[] };
export type Catalog = {
  handlers: { name: string; amr: string[]; bindsSubject: boolean }[];
  screens: { type: string; options: string[] }[];
  egress: string[];
};
export type Draft = { yaml: string; origin: "draft" | "current" | "config"; files: Record<string, string> };
export type Branding = { name?: string; logoUrl?: string; primaryColor?: string; radius?: number };
export type Settings = { mfaRequired: boolean; handlers: string[] | null; screens: string[] | null; branding: Branding };
export type SettingsView = { settings: Settings; configured: Settings; saved: boolean; egress: string[] };
export type Versions = { current: string | null; versions: string[] };
export type VersionView = { hash: string; yaml: string; ir: string; current: boolean };
export type HookRun = {
  result: unknown;
  error: string | null;
  accepted: boolean;
  effect: string;
  logs: string[];
  millis: number;
};
export type Published = { versionHash: string; summary: string; warnings: Problem[] };
export type Scenario = { request: { ip?: string; user_agent?: string } };
export type PreviewView = { enabled: boolean; scenario: Scenario };
export type Seeded = { identifier: string; password: string; totpSecret: string | null };
/** GET tenants/{t}/preview/flows/{flowId}: what a preview run did, and the paths the checker predicted for it. */
export type PreviewRun = {
  flowId: string;
  versionHash: string;
  draft: boolean;
  visits: string[];
  result: { succeeded: boolean; failure: string | null; amr: string[]; acr: string | null } | null;
  expected: PathView[];
  matches: boolean | null;
};

/** Calls the admin API; resolves with the status and JSON body for 2xx, 400 and 422 alike. */
export async function api<T>(
  path: string,
  init: { method?: string; json?: unknown; yaml?: string } = {},
): Promise<{ status: number; body: T }> {
  const headers: Record<string, string> = {};
  let body: string | undefined;
  if (init.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(init.json);
  } else if (init.yaml !== undefined) {
    headers["content-type"] = "application/yaml";
    body = init.yaml;
  }
  if ((init.method ?? "GET") !== "GET") headers["x-faye-admin"] = "1";
  const res = await fetch(`/api/admin/${path}`, { method: init.method ?? "GET", headers, body, cache: "no-store" });
  const text = await res.text();
  const parsed = text ? (JSON.parse(text) as T) : ({} as T);
  if (res.status >= 500 || res.status === 403 || res.status === 404 || res.status === 413) {
    throw new Error((parsed as { error?: string; message?: string }).message ?? (parsed as { error?: string }).error ?? `HTTP ${res.status}`);
  }
  return { status: res.status, body: parsed };
}
/** GET tenants/{t}/clients: an OAuth client as the authorization server holds it. Read only; no secret. */
export type Client = {
  id: string;
  name: string;
  tenant: string | null;
  firstParty: boolean;
  preview: boolean;
  redirectUris: string[];
  grantTypes: string[];
  /** "none" for a public client, which has no secret. */
  authMethod: string;
  createdAt: string | null;
};
/** GET tenants/{t}/users: an account the tenant's flows can sign in, without its secrets. */
export type UserView = {
  subject: string;
  /** The tenant it belongs to; null for an account every tenant shares. */
  tenant: string | null;
  identifier: string;
  name: string | null;
  emailVerified: boolean;
  password: boolean;
  totp: boolean;
  passkeys: number;
  mfaRequired: boolean;
};
