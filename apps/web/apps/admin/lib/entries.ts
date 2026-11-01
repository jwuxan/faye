import { ENTRY_KINDS } from "@faye/contract";

/**
 * The sections a flow's YAML can have: each top level key, the IR entry kind it lowers to,
 * and how the console names it. The one place the console spells
 * them; elsewhere a `Section` is one of these keys. Consent is not a section: it is a step of
 * `login`, and the compiler lowers that step and every step after it to the consent entry
 * (ADR 0026, `consentStart`).
 */
export const ENTRIES = {
  login: { kind: "authenticate", title: "Sign in", done: "signed in" },
  // The phone's side of a QR code sign in (ADR 0032)
  approve: { kind: "approve", title: "Approve", done: "approved" },
} as const satisfies Record<string, { kind: (typeof ENTRY_KINDS)[number]; title: string; done: string }>;

export type Section = keyof typeof ENTRIES;

/** In document order; every flow has the first. */
export const SECTIONS = Object.keys(ENTRIES) as Section[];

/** How the console names the steps that run on the consent challenge. */
export const CONSENT = { kind: "consent", title: "Consent", done: "consented" } as const satisfies { kind: (typeof ENTRY_KINDS)[number]; title: string; done: string };

/** The handler whose step starts the consent steps, as the compiler spells it. */
export const CONSENT_HANDLER = "consent.record";

/** The entry every flow has: the sign in itself. */
export const AUTHENTICATE: Section = "login";

/** The steps a phone runs to approve a QR code sign in waiting on another device (ADR 0032). */
export const APPROVE: Section = "approve";

export function isSection(name: string): name is Section {
  return Object.hasOwn(ENTRIES, name);
}
