import { NextResponse } from "next/server";
import { fayeServerUrl } from "../../../lib/faye-server";

/**
 * Where a QR code sends the phone (ADR 0032): starts the phone's approve flow on the Faye server,
 * passes on the cookie that binds it to this browser, and goes to its first screen. A code that is
 * unknown, used or expired shows /error.
 */
export async function GET(request: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const headers: Record<string, string> = {};
  const userAgent = request.headers.get("user-agent");
  if (userAgent) headers["user-agent"] = userAgent;
  const res = await fetch(`${fayeServerUrl()}/api/approve/${encodeURIComponent(token)}`, { method: "POST", headers, cache: "no-store" });
  // The origin the browser used, which a server behind a proxy may not see as request.url
  const host = request.headers.get("host");
  const base = host ? new URL(`${new URL(request.url).protocol}//${host}`) : new URL(request.url);
  if (!res.ok) return NextResponse.redirect(new URL("/error?reason=failed", base), 303);
  const { location } = (await res.json()) as { location: string };
  // Only a path on this origin, as the server sends it
  const next = location.startsWith("/") && !location.startsWith("//") ? location : "/error?reason=failed";
  const out = NextResponse.redirect(new URL(next, base), 303);
  for (const cookie of res.headers.getSetCookie()) {
    if (cookie.startsWith("faye_flow_")) out.headers.append("set-cookie", cookie);
  }
  return out;
}
