import type { ErrorResponse, ScreenResponse } from "@faye/contract";
import { Card, CardContent, CardHeader, CardTitle } from "@faye/ui/components/card";
import { cookies } from "next/headers";
import { bindingHeader, fayeServerUrl, flowCookie } from "../../../lib/faye-server";
import { errorMessage } from "../../../lib/messages";
import { FlowScreen } from "./flow-screen";

/** The value if it is exactly one origin (scheme, host, port); anything else, `*` included, is null. */
function singleOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    return new URL(value).origin === value ? value : null;
  } catch {
    return null;
  }
}

export default async function FlowPage({ params }: { params: Promise<{ flowId: string }> }) {
  const { flowId } = await params;
  // Faye answers only the browser that started the flow, by the cookie its /login set
  const binder = (await cookies()).get(flowCookie(flowId))?.value;
  const res = await fetch(`${fayeServerUrl()}/api/flows/${encodeURIComponent(flowId)}`, {
    cache: "no-store",
    headers: bindingHeader(flowId, binder),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({ error: "flow_not_found" }))) as ErrorResponse;
    return (
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-destructive" role="alert">{errorMessage(body.error)}</p>
        </CardContent>
      </Card>
    );
  }
  // The page that opened this one for a host that hands off (ADR 0025); unset, a result is never posted
  const embedOrigin = singleOrigin(process.env.FAYE_EMBED_ORIGIN);
  return <FlowScreen initial={(await res.json()) as ScreenResponse} embedOrigin={embedOrigin} />;
}
