import { ENTRY_KINDS } from "@faye/contract";

/**
 * The entries a flow's YAML can have: each top level key, the IR entry kind it lowers to
 * (docs/roadmap/dsl-and-ir.md), and how the console names it. The one place the console spells
 * them; elsewhere a `Section` is one of these keys.
 */
export const ENTRIES = {
  login: { kind: "authenticate", title: "Sign in", done: "signed in" },
  consent: { kind: "consent", title: "Consent", done: "consented" },
} as const satisfies Record<string, { kind: (typeof ENTRY_KINDS)[number]; title: string; done: string }>;

export type Section = keyof typeof ENTRIES;

/** In document order; every flow has the first. */
export const SECTIONS = Object.keys(ENTRIES) as Section[];

/** The entry every flow has: the sign in itself. */
export const AUTHENTICATE: Section = "login";

export function isSection(name: string): name is Section {
  return Object.hasOwn(ENTRIES, name);
}
