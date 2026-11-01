import type { ErrorResponse, ScreenResponse } from "@faye/contract";
import { Separator } from "@faye/ui/components/separator";
import { CircleAlert } from "lucide-react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { bindingHeader, fayeServerUrl, flowCookie } from "../lib/faye-server";
import { errorMessage, terminalErrorMessage } from "../lib/messages";
import { FlowScreen } from "./flow-screen";
import { Restart } from "./restart";

export type FlowPageProps = { searchParams: Promise<Record<string, string | string[] | undefined>> };

/**
 * The value if it is exactly one origin (scheme, host, port; a trailing slash is allowed); anything
 * else, `*` included, is null, and logged so a misconfiguration does not fail silently.
 */
function singleOrigin(value: string | undefined): string | null {
  if (!value) return null;
  const trimmed = value.replace(/\/$/, "");
  try {
    if (new URL(trimmed).origin === trimmed) return trimmed;
  } catch {
    // not a URL
  }
  console.error(`FAYE_EMBED_ORIGIN is not a single origin (${value}); handoff results will not be posted`);
  return null;
}

/** The flow ids Faye issues; anything else is no flow. */
const FLOW_ID = /^f_[A-Za-z0-9_-]+$/;

/**
 * Every screen of a flow, whichever path the browser is at (ADR 0029): the flow, named by
 * `?flow=`, decides the screen, and the screen moves the address bar to its own path. A flow that
 * cannot be shown, and a sign in that failed (?reason=failed, ADR 0031), is shown at /error, which
 * moves on to /login after a few seconds when /login can start a new sign in.
 */
export async function FlowPage({ searchParams, at }: FlowPageProps & { at: string }) {
  const { flow, reason } = await searchParams;
  // Opened directly, with no flow: with the demo running, start a sign in through its client, as
  // Preview does; it comes back here at /login?flow= once Faye has the login challenge
  const start = process.env.DEMO_SIGN_IN_URL;
  // Without it, /login with no flow is only /error again, so /error stays put
  if (flow === undefined && at === "/login" && start) redirect(start);
  const flowId = typeof flow === "string" && FLOW_ID.test(flow) ? flow : null;
  let failure: ErrorResponse["error"] = "flow_not_found";
  if (flowId !== null) {
    // Faye answers only the browser that started the flow, by the cookie its /login set
    const binder = (await cookies()).get(flowCookie(flowId))?.value;
    const res = await fetch(`${fayeServerUrl()}/api/flows/${encodeURIComponent(flowId)}`, {
      cache: "no-store",
      headers: bindingHeader(flowId, binder),
    });
    if (res.ok) {
      // The page that opened this one for a host that hands off (ADR 0025); unset, a result is never posted
      const embedOrigin = singleOrigin(process.env.FAYE_EMBED_ORIGIN);
      return <FlowScreen initial={(await res.json()) as ScreenResponse} embedOrigin={embedOrigin} />;
    }
    failure = ((await res.json().catch(() => ({ error: "flow_not_found" }))) as ErrorResponse).error;
  }
  if (at !== "/error") redirect(flowId === null ? "/error" : `/error?flow=${encodeURIComponent(flowId)}`);
  return (
    <div className="grid w-full max-w-100 gap-6">
      <header className="grid justify-items-center gap-3 text-center">
        <span className="grid size-12 place-items-center rounded-full bg-destructive/8 text-destructive" aria-hidden>
          <CircleAlert className="size-6" strokeWidth={1.75} />
        </span>
        <h1 className="text-xl font-bold tracking-tight">Sign in</h1>
        <p className="text-muted-foreground" role="alert">
          {at === "/error" && reason === "failed" ? terminalErrorMessage("failed") : errorMessage(failure)}
        </p>
      </header>
      {start && (
        <>
          <Separator />
          <Restart />
        </>
      )}
    </div>
  );
}
