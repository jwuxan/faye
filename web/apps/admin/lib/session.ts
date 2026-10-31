/**
 * The operator's session: an HMAC signed cookie naming the subject Hydra signed in. Server only.
 * Without ADMIN_SESSION_SECRET each process makes its own secret, so sessions end on restart.
 */
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";

export const SESSION_COOKIE = "faye_admin";
/** Hours an operator stays signed in. */
const LIFETIME_HOURS = 8;

export type Session = { sub: string; exp: number };

const holder = globalThis as { fayeAdminSecret?: string };

function secret(): string {
  holder.fayeAdminSecret ??= process.env.ADMIN_SESSION_SECRET || randomBytes(32).toString("hex");
  return holder.fayeAdminSecret;
}

function mac(payload: string): string {
  return createHmac("sha256", secret()).update(payload).digest("base64url");
}

export function seal(sub: string): { value: string; maxAge: number } {
  const maxAge = LIFETIME_HOURS * 3600;
  const payload = Buffer.from(JSON.stringify({ sub, exp: Math.floor(Date.now() / 1000) + maxAge })).toString("base64url");
  return { value: `${payload}.${mac(payload)}`, maxAge };
}

export function unseal(value: string | undefined): Session | null {
  if (!value) return null;
  const [payload, tag] = value.split(".");
  if (!payload || !tag) return null;
  const expected = Buffer.from(mac(payload));
  const given = Buffer.from(tag);
  if (expected.length !== given.length || !timingSafeEqual(expected, given)) return null;
  const session = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Session;
  return session.exp > Date.now() / 1000 ? session : null;
}

export async function currentSession(): Promise<Session | null> {
  return unseal((await cookies()).get(SESSION_COOKIE)?.value);
}
