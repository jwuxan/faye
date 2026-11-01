/** How each kind of block looks: an icon and a category color, derived from what its body does. */
import {
  AtSign,
  ClipboardCheck,
  FingerprintPattern,
  KeyRound,
  ListOrdered,
  type LucideIcon,
  Mail,
  MessageSquareText,
  OctagonX,
  QrCode,
  RectangleEllipsis,
  ShieldPlus,
  Smartphone,
  SmartphoneNfc,
  Split,
  TimerReset,
  Variable,
} from "lucide-react";
import type { Body } from "./flow";

/** Built in handlers, structure, and side effects and ends. */
export type Category = "handler" | "logic" | "effect";

/** Each kind of block has its own hue, so a flow reads as a sequence of colored steps. */
export type Tone = "blue" | "violet" | "amber" | "teal" | "green" | "pink" | "orange" | "red" | "slate";

/** A filled tile in the block's hue, with a white glyph. */
export const TILE: Record<Tone, string> = {
  blue: "bg-tone-blue",
  violet: "bg-tone-violet",
  amber: "bg-tone-amber",
  teal: "bg-tone-teal",
  green: "bg-tone-green",
  pink: "bg-tone-pink",
  orange: "bg-tone-orange",
  red: "bg-tone-red",
  slate: "bg-tone-slate",
};

type Look = { icon: LucideIcon; category: Category; tone: Tone; label: string };

/** Built in blocks, by id. */
const BLOCKS: Record<string, Look> = {
  "identifier": { icon: AtSign, category: "handler", tone: "blue", label: "Identifier" },
  "password": { icon: KeyRound, category: "handler", tone: "amber", label: "Password" },
  "passkey": { icon: FingerprintPattern, category: "handler", tone: "violet", label: "Passkey" },
  "add-passkey": { icon: ShieldPlus, category: "handler", tone: "violet", label: "Add passkey" },
  "totp": { icon: TimerReset, category: "handler", tone: "teal", label: "TOTP" },
  "enroll-totp": { icon: RectangleEllipsis, category: "handler", tone: "teal", label: "Enroll TOTP" },
  "consent": { icon: ClipboardCheck, category: "handler", tone: "green", label: "Consent" },
  "email-code": { icon: Mail, category: "handler", tone: "teal", label: "Email code" },
  "sms-code": { icon: MessageSquareText, category: "handler", tone: "teal", label: "Text message code" },
  "add-phone": { icon: Smartphone, category: "handler", tone: "teal", label: "Add phone" },
  "qr": { icon: QrCode, category: "handler", tone: "violet", label: "QR code" },
  "qr-approve": { icon: SmartphoneNfc, category: "handler", tone: "violet", label: "Approve QR code" },
};

/** Icons of the palette blocks, by block name. */
export const BLOCK_ICONS: Record<string, { icon: LucideIcon; category: Category; tone: Tone }> = {
  Identifier: BLOCKS["identifier"],
  Password: BLOCKS["password"],
  Passkey: BLOCKS["passkey"],
  "Add passkey": BLOCKS["add-passkey"],
  TOTP: BLOCKS["totp"],
  "Enroll TOTP": BLOCKS["enroll-totp"],
  Consent: BLOCKS["consent"],
  "Email code": BLOCKS["email-code"],
  "Text message code": BLOCKS["sms-code"],
  "Add phone": BLOCKS["add-phone"],
  "QR code": BLOCKS["qr"],
  "Approve QR code": BLOCKS["qr-approve"],
  "Code by": { icon: Split, category: "logic", tone: "teal" },
  Choose: { icon: Split, category: "logic", tone: "pink" },
  Group: { icon: ListOrdered, category: "logic", tone: "slate" },
  Set: { icon: Variable, category: "effect", tone: "orange" },
  Fail: { icon: OctagonX, category: "effect", tone: "red" },
};

export function kindOf(b: Body): Look {
  if (b.block && BLOCKS[b.block]) return BLOCKS[b.block];
  if (b.fail !== undefined) return { icon: OctagonX, category: "effect", tone: "red", label: "Fail" };
  if (b.alternative) return { icon: Split, category: "logic", tone: "pink", label: b.pick ? "Hook picks" : "Choose" };
  if (b.steps) return { icon: ListOrdered, category: "logic", tone: "slate", label: "Group" };
  if (b.set) return { icon: Variable, category: "effect", tone: "orange", label: "Set" };
  return { icon: ListOrdered, category: "logic", tone: "slate", label: "Empty" };
}

const ID_TONES: Tone[] = ["blue", "violet", "amber", "teal", "green", "pink", "orange"];

/** A stable hue for a person or an application, so a list of them is easy to scan. */
export function toneOf(id: string): Tone {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return ID_TONES[h % ID_TONES.length];
}
