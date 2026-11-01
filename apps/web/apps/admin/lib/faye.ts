import { request } from "node:http";
import { Readable } from "node:stream";
import { connection } from "next/server";

/** The demo client, which Preview opens with the tenant's preview client. */
export function demoClientUrl(): string {
  return process.env.DEMO_CLIENT_URL ?? "http://localhost:3001";
}

export interface AdminInit {
  method?: string;
  headers?: HeadersInit;
  body?: string;
  signal?: AbortSignal;
}

/**
 * Calls the Faye server's admin API, which it serves over HTTP on the Unix domain socket
 * FAYE_ADMIN_SOCKET and on no network port (ADR 0028), with the bearer token, which never leaves
 * this server. The response body streams, so server sent events pass through as they arrive; aborting
 * the signal ends the upstream request.
 */
export async function adminFetch(path: string, init: AdminInit = {}): Promise<Response> {
  // Every call reads live state: never during prerendering
  await connection();
  const socketPath = process.env.FAYE_ADMIN_SOCKET;
  if (!socketPath) throw new Error("FAYE_ADMIN_SOCKET is not set");
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${process.env.FAYE_ADMIN_TOKEN ?? ""}`);
  if (init.body !== undefined) headers.set("content-length", String(Buffer.byteLength(init.body)));
  return new Promise((resolve, reject) => {
    const req = request(
      { socketPath, path: `/admin/${path}`, method: init.method ?? "GET", headers: Object.fromEntries(headers), signal: init.signal },
      (res) => {
        const out = new Headers();
        for (const [name, value] of Object.entries(res.headers)) {
          for (const v of Array.isArray(value) ? value : value === undefined ? [] : [value]) out.append(name, v);
        }
        const status = res.statusCode ?? 502;
        const empty = status === 204 || status === 205 || status === 304;
        if (empty) res.resume();
        resolve(new Response(empty ? null : (Readable.toWeb(res) as ReadableStream<Uint8Array>), { status, headers: out }));
      },
    );
    req.on("error", reject);
    req.end(init.body);
  });
}
