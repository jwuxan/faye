import { NextResponse } from "next/server";
import { ID_TOKEN_COOKIE, oidcConfig } from "../../lib/oidc";

/** Signs out of this app only: forgets the ID token. Hydra's session stays, as Faye has no logout screen yet. */
export function POST() {
  const res = NextResponse.redirect(new URL("/", oidcConfig().baseUrl), 303);
  res.cookies.delete(ID_TOKEN_COOKIE);
  return res;
}
