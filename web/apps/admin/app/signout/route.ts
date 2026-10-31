import { NextResponse } from "next/server";
import { oidcConfig } from "../../lib/oidc";
import { SESSION_COOKIE } from "../../lib/session";

/** Ends the Admin UI session. Hydra's own session stays; the next sign in still asks for both factors. */
export function POST() {
  const res = NextResponse.redirect(new URL("/", oidcConfig().baseUrl), 303);
  res.cookies.delete(SESSION_COOKIE);
  return res;
}
