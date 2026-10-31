/** Building blocks of the condition builder; predicates are IR data, never strings to parse. */
import type { Literal, Predicate } from "./flow.ts";

export type Op = "eq" | "ne" | "has" | "gt" | "lt";
export type Kind = "path" | "compare" | "exists" | "and" | "or" | "not";

export const OPS: { op: Op; label: string }[] = [
  { op: "eq", label: "equals" },
  { op: "ne", label: "does not equal" },
  { op: "has", label: "contains" },
  { op: "gt", label: "greater than" },
  { op: "lt", label: "less than" },
];

export const NAMESPACES = ["user", "auth", "hydra", "client", "tenant", "vars", "request"] as const;

/** Paths flows commonly read, with what they hold; any `vars.*` name works too. */
export const KNOWN_PATHS: { path: string; type: "boolean" | "string" | "list" | "integer"; about: string }[] = [
  { path: "user.exists", type: "boolean", about: "the identifier matched an account" },
  { path: "user.identified_by", type: "string", about: "identifier, or passkey from autofill" },
  { path: "user.has_password", type: "boolean", about: "the account has a password" },
  { path: "user.has_passkey", type: "boolean", about: "the account has a passkey" },
  { path: "user.has_totp", type: "boolean", about: "the account has TOTP" },
  { path: "user.mfa_required", type: "boolean", about: "the account must use two factors" },
  { path: "auth.amr", type: "list", about: "credentials verified so far: pwd, hwk, otp" },
  { path: "auth.acr", type: "string", about: "faye:1fa or faye:2fa so far" },
  { path: "hydra.acr_values", type: "list", about: "assurance the client asked for" },
  { path: "hydra.login_hint", type: "string", about: "identifier the client suggested" },
  { path: "client.first_party", type: "boolean", about: "the client is the tenant's own app" },
  { path: "client.client_id", type: "string", about: "the OAuth client" },
  { path: "tenant.mfa_required", type: "boolean", about: "the tenant requires two factors" },
  { path: "request.ip", type: "string", about: "the browser's IP address" },
];

/** Values a path is commonly compared with, offered as suggestions. */
export const KNOWN_VALUES: Record<string, string[]> = {
  "user.identified_by": ["identifier", "passkey"],
  "auth.acr": ["faye:1fa", "faye:2fa"],
  "hydra.acr_values": ["faye:1fa", "faye:2fa"],
  "auth.amr": ["pwd", "hwk", "otp"],
};

export const PATH = /^(request|client|hydra|tenant|user|vars|auth)(\.[a-z][a-z0-9_]*)+$/;

export function kindOf(p: Predicate): Kind {
  if (typeof p === "string") return "path";
  if ("and" in p) return "and";
  if ("or" in p) return "or";
  if ("not" in p) return "not";
  if ("exists" in p) return "exists";
  return "compare";
}

export function compare(op: Op, path: string, value: Literal): Predicate {
  if (op === "gt" || op === "lt") return { [op]: [path, typeof value === "number" ? value : Number(value) || 0] } as Predicate;
  return { [op]: [path, value] } as Predicate;
}

/** The parts of a comparison. */
export function comparison(p: Predicate): { op: Op; path: string; value: Literal } | null {
  if (typeof p !== "object") return null;
  for (const { op } of OPS) {
    const pair = (p as Record<string, unknown>)[op];
    if (Array.isArray(pair)) return { op, path: String(pair[0]), value: pair[1] as Literal };
  }
  return null;
}

export const all = (...ps: Predicate[]): Predicate => ({ and: ps });
export const any = (...ps: Predicate[]): Predicate => ({ or: ps });
export const not = (p: Predicate): Predicate => ({ not: p });

/** The members of an and / or, or the predicate negated by a not. */
export function children(p: Predicate): Predicate[] {
  if (typeof p !== "object") return [];
  if ("and" in p) return p.and;
  if ("or" in p) return p.or;
  if ("not" in p) return [p.not];
  return [];
}

/** Turns a predicate into another kind, keeping what carries over. */
export function convert(p: Predicate, kind: Kind): Predicate {
  const path = typeof p === "string" ? p : (comparison(p)?.path ?? ("exists" in (p as object) ? (p as { exists: string }).exists : "user.exists"));
  switch (kind) {
    case "path":
      return path;
    case "exists":
      return { exists: path };
    case "compare":
      return compare("eq", path, "");
    case "and":
    case "or":
      return { [kind]: kindOf(p) === "and" || kindOf(p) === "or" ? children(p) : [p] } as Predicate;
    case "not":
      return not(p);
  }
}

/** Plain words for a predicate, for the tree and the inspector header. */
export function describe(p: Predicate): string {
  switch (kindOf(p)) {
    case "path":
      return p as string;
    case "exists":
      return `${(p as { exists: string }).exists} exists`;
    case "not": {
      const inner = (p as { not: Predicate }).not;
      return typeof inner === "string" ? `not ${inner}` : `not (${describe(inner)})`;
    }
    case "and":
    case "or": {
      const kind = kindOf(p);
      // A nested and / or is bracketed, so "a and (b or c)" reads as written
      return children(p)
        .map((c) => (kindOf(c) === "and" || kindOf(c) === "or" ? `(${describe(c)})` : describe(c)))
        .join(` ${kind} `);
    }
    case "compare": {
      const c = comparison(p)!;
      return `${c.path} ${OPS.find((o) => o.op === c.op)!.label} ${JSON.stringify(c.value)}`;
    }
  }
}
