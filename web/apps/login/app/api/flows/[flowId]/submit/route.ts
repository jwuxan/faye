import { cookies } from "next/headers";
import { bindingHeader, fayeServerUrl, flowCookie } from "../../../../../lib/faye-server";
import { MAX_SUBMIT_BYTES } from "../../../../../lib/redirect";

/**
 * Same origin endpoint for the browser; forwards the submission to the Faye server unchanged, with
 * the cookie that binds the flow to this browser, and passes back the cookie Faye expires when the
 * flow finishes. It forwards only what the server accepts: a JSON body (so a cross site text/plain
 * form post is not turned into JSON here) of at most MAX_SUBMIT_BYTES (ADR 0007, seed finding 6).
 */
export async function POST(request: Request, { params }: { params: Promise<{ flowId: string }> }) {
  const { flowId } = await params;
  const type = request.headers.get("content-type") ?? "";
  if (!/^application\/json(\s*;|$)/i.test(type)) return refuse(415);
  if (Number(request.headers.get("content-length") ?? 0) > MAX_SUBMIT_BYTES) return refuse(413);
  const body = await request.text();
  if (new TextEncoder().encode(body).length > MAX_SUBMIT_BYTES) return refuse(413);
  const binder = (await cookies()).get(flowCookie(flowId))?.value;
  const res = await fetch(`${fayeServerUrl()}/api/flows/${encodeURIComponent(flowId)}/submit`, {
    method: "POST",
    headers: { "content-type": "application/json", ...bindingHeader(flowId, binder) },
    body,
    cache: "no-store",
  });
  const headers = new Headers({ "content-type": "application/json" });
  for (const cookie of res.headers.getSetCookie()) {
    if (cookie.startsWith(`${flowCookie(flowId)}=`)) headers.append("set-cookie", cookie);
  }
  return new Response(res.body, { status: res.status, headers });
}

function refuse(status: number): Response {
  return Response.json({ error: "invalid_submission" }, { status });
}
