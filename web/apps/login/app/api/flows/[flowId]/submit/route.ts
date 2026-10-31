import { cookies } from "next/headers";
import { bindingHeader, fayeServerUrl, flowCookie } from "../../../../../lib/faye-server";

/**
 * Same origin endpoint for the browser; forwards the submission to the Faye server unchanged, with
 * the cookie that binds the flow to this browser, and passes back the cookie Faye expires when the
 * flow finishes.
 */
export async function POST(request: Request, { params }: { params: Promise<{ flowId: string }> }) {
  const { flowId } = await params;
  const binder = (await cookies()).get(flowCookie(flowId))?.value;
  const res = await fetch(`${fayeServerUrl()}/api/flows/${encodeURIComponent(flowId)}/submit`, {
    method: "POST",
    headers: { "content-type": "application/json", ...bindingHeader(flowId, binder) },
    body: await request.text(),
    cache: "no-store",
  });
  const headers = new Headers({ "content-type": "application/json" });
  for (const cookie of res.headers.getSetCookie()) {
    if (cookie.startsWith(`${flowCookie(flowId)}=`)) headers.append("set-cookie", cookie);
  }
  return new Response(res.body, { status: res.status, headers });
}
