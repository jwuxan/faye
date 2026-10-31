import { fayeServerUrl } from "../../../../../lib/faye-server";

/** Same origin endpoint for the browser; forwards the submission to the Faye server unchanged. */
export async function POST(request: Request, { params }: { params: Promise<{ flowId: string }> }) {
  const { flowId } = await params;
  const res = await fetch(`${fayeServerUrl()}/api/flows/${encodeURIComponent(flowId)}/submit`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: await request.text(),
    cache: "no-store",
  });
  return new Response(res.body, { status: res.status, headers: { "content-type": "application/json" } });
}
