import { adminFetch } from "../../../../lib/faye";
import { currentSession } from "../../../../lib/session";

/**
 * Same origin proxy to the Faye admin API for a signed in operator. A state changing call must
 * carry x-faye-admin: 1, which a cross site form cannot send and a cross site fetch cannot send
 * without a CORS preflight this app never answers. Server sent events (the preview trace) stream
 * through, and the upstream request ends when the browser disconnects.
 */
async function forward(request: Request, { params }: { params: Promise<{ path: string[] }> }) {
  if (!(await currentSession())) return Response.json({ error: "signed_out" }, { status: 401 });
  if (request.method !== "GET" && request.headers.get("x-faye-admin") !== "1") {
    return Response.json({ error: "missing x-faye-admin header" }, { status: 403 });
  }
  const { path } = await params;
  const headers: Record<string, string> = {};
  const type = request.headers.get("content-type");
  if (type) headers["content-type"] = type;
  const lastEventId = request.headers.get("last-event-id");
  if (lastEventId) headers["last-event-id"] = lastEventId;
  const res = await adminFetch(path.map(encodeURIComponent).join("/"), {
    method: request.method,
    headers,
    body: request.method === "GET" ? undefined : await request.text(),
    signal: request.signal,
  });
  const contentType = res.headers.get("content-type") ?? "application/json";
  return new Response(res.body, {
    status: res.status,
    headers: contentType.startsWith("text/event-stream")
      ? // no-transform keeps compression from buffering the stream
        { "content-type": contentType, "cache-control": "no-cache, no-transform", "x-accel-buffering": "no" }
      : { "content-type": contentType },
  });
}

export { forward as GET, forward as POST, forward as PUT };
