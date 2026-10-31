/** How each kind of block looks: an icon and a category color, derived from what its body does. */
import {
  AtSign,
  ClipboardCheck,
  FingerprintPattern,
  KeyRound,
  ListOrdered,
  type LucideIcon,
  OctagonX,
  RectangleEllipsis,
  ShieldPlus,
  Split,
  SquareCode,
  TimerReset,
  Variable,
} from "lucide-react";
import type { Body } from "./flow";

/** Three low chroma tints: built in handlers (accent), structure (neutral), side effects and ends (warm). */
export type Category = "handler" | "logic" | "effect";

export const TILE: Record<Category, string> = {
  handler: "bg-muted text-accent-ui",
  logic: "bg-muted text-muted-foreground",
  effect: "bg-muted text-warm",
};

const HANDLERS: Record<string, { icon: LucideIcon; category: Category; label: string }> = {
  "identity.resolve": { icon: AtSign, category: "handler", label: "Identifier" },
  "password.verify": { icon: KeyRound, category: "handler", label: "Password" },
  "webauthn.assert": { icon: FingerprintPattern, category: "handler", label: "Passkey" },
  "webauthn.register": { icon: ShieldPlus, category: "handler", label: "Add passkey" },
  "totp.verify": { icon: TimerReset, category: "handler", label: "TOTP" },
  "totp.enroll": { icon: RectangleEllipsis, category: "handler", label: "Enroll TOTP" },
  "consent.record": { icon: ClipboardCheck, category: "handler", label: "Consent" },
};

/** Icons of the palette blocks, by block name. */
export const BLOCK_ICONS: Record<string, { icon: LucideIcon; category: Category }> = {
  Identifier: HANDLERS["identity.resolve"],
  Password: HANDLERS["password.verify"],
  Passkey: HANDLERS["webauthn.assert"],
  "Add passkey": HANDLERS["webauthn.register"],
  TOTP: HANDLERS["totp.verify"],
  "Enroll TOTP": HANDLERS["totp.enroll"],
  Consent: HANDLERS["consent.record"],
  Choose: { icon: Split, category: "logic" },
  Group: { icon: ListOrdered, category: "logic" },
  Hook: { icon: SquareCode, category: "effect" },
  Set: { icon: Variable, category: "effect" },
  Fail: { icon: OctagonX, category: "effect" },
};

export function kindOf(b: Body): { icon: LucideIcon; category: Category; label: string } {
  if (b.use && HANDLERS[b.use]) return HANDLERS[b.use];
  if (b.fail !== undefined) return { icon: OctagonX, category: "effect", label: "Fail" };
  if (b.alternative) return { icon: Split, category: "logic", label: b.pick ? "Hook picks" : "Choose" };
  if (b.hook !== undefined) return { icon: SquareCode, category: "effect", label: "Hook" };
  if (b.steps) return { icon: ListOrdered, category: "logic", label: "Group" };
  if (b.set) return { icon: Variable, category: "effect", label: "Set" };
  return { icon: ListOrdered, category: "logic", label: "Empty" };
}
