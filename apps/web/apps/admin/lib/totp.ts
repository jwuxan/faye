/**
 * RFC 6238 codes (SHA-1, 30 s, 6 digits), so an operator can answer the otp screen of a preview
 * with a seeded account's secret, or one shown on otp_enroll, without an authenticator app. This is
 * a test authenticator, not a check: the server verifies codes.
 */
export async function totp(secret: string, at = Date.now()): Promise<string> {
  const key = await crypto.subtle.importKey("raw", base32(secret), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const counter = new DataView(new ArrayBuffer(8));
  counter.setBigUint64(0, BigInt(Math.floor(at / 1000 / 30)));
  const h = new Uint8Array(await crypto.subtle.sign("HMAC", key, counter.buffer));
  const o = h[h.length - 1] & 15;
  const code = (new DataView(h.buffer).getUint32(o) & 0x7fffffff) % 1_000_000;
  return code.toString().padStart(6, "0");
}

/** Seconds until the code for `at` changes. */
export function remaining(at = Date.now()): number {
  return 30 - (Math.floor(at / 1000) % 30);
}

function base32(text: string): Uint8Array<ArrayBuffer> {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = text.toUpperCase().replace(/[\s=]/g, "");
  const out = new Uint8Array(new ArrayBuffer(Math.floor((clean.length * 5) / 8)));
  let bits = 0;
  let value = 0;
  let i = 0;
  for (const c of clean) {
    const v = alphabet.indexOf(c);
    if (v < 0) throw new Error(`not base32: ${c}`);
    value = (value << 5) | v;
    bits += 5;
    if (bits >= 8) {
      out[i++] = (value >>> (bits - 8)) & 0xff;
      bits -= 8;
    }
  }
  return out;
}
