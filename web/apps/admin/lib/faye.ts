/** Calls the Faye server's admin API with the bearer token, which never leaves this server. */
export function fayeServerUrl(): string {
  return process.env.FAYE_SERVER_URL ?? "http://localhost:8080";
}

/** The demo client, which Preview opens with the tenant's preview client. */
export function demoClientUrl(): string {
  return process.env.DEMO_CLIENT_URL ?? "http://localhost:3001";
}

export function adminFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${process.env.FAYE_ADMIN_TOKEN ?? ""}`);
  return fetch(`${fayeServerUrl()}/admin/${path}`, { ...init, headers, cache: "no-store" });
}
