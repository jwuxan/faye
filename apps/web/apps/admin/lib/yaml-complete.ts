/**
 * Completions for the flow YAML, from where the cursor is in the document's outline: the keys a
 * step, a hook or the document takes, block ids after `block:`, the flow's hooks after `before:`,
 * `after:` and `pick:`, a block's settings under `with:`, and paths and operators in an `if:`.
 * Line based, so it works while the YAML does not parse; the server still checks what is typed.
 */
import { OPERATORS } from "@faye/contract";
import { PALETTE } from "./flow.ts";
import { KNOWN_PATHS } from "./predicate.ts";

export type Option = { label: string; detail?: string; type: "property" | "keyword" | "variable" | "constant"; apply?: string };
export type Completions = { from: number; options: Option[] };

const TOP: Option[] = [
  { label: "flow", detail: "name", type: "property", apply: "flow: " },
  { label: "version", detail: "integer", type: "property", apply: "version: " },
  { label: "hooks", detail: "id: hook", type: "property", apply: "hooks:" },
  { label: "login", detail: "the sign in", type: "property", apply: "login:" },
];

/** A step's or an option's keys, in the order they run (lib/flow.ts Body). */
const BODY: Option[] = [
  { label: "if", detail: "runs only when", type: "property", apply: "if: " },
  { label: "before", detail: "hook before", type: "property", apply: "before: " },
  { label: "set", detail: "vars", type: "property", apply: "set:" },
  { label: "block", detail: "built in block", type: "property", apply: "block: " },
  { label: "with", detail: "block settings", type: "property", apply: "with:" },
  { label: "path", detail: "screen path", type: "property", apply: "path: " },
  { label: "pick", detail: "hook that picks", type: "property", apply: "pick: " },
  { label: "alternative", detail: "one of", type: "property", apply: "alternative:" },
  { label: "steps", detail: "in order", type: "property", apply: "steps:" },
  { label: "fail", detail: "end with an error", type: "property", apply: "fail: " },
  { label: "after", detail: "hook after", type: "property", apply: "after: " },
];

const HOOK: Option[] = [
  { label: "file", detail: "next to the flow", type: "property", apply: "file: " },
  { label: "source", detail: "inline JavaScript", type: "property", apply: "source: " },
  { label: "capabilities", detail: "http egress", type: "property", apply: "capabilities:" },
  { label: "timeout_ms", detail: "integer", type: "property", apply: "timeout_ms: " },
];

type Line = { indent: number; key: string | null; value: string; at: number };

function outline(text: string): Line[] {
  return text.split("\n").map((raw, at) => {
    const m = /^(\s*)(?:([\w.-]+)\s*:(?:\s+|$))?(.*)$/.exec(raw)!;
    const blank = raw.trim() === "" || raw.trim().startsWith("#");
    return { indent: blank ? -1 : m[1].length, key: blank ? null : (m[2] ?? null), value: (m[3] ?? "").replace(/\s+#.*$/, "").trim(), at };
  });
}

/** The keys enclosing a line at `indent`, outermost first, with the line each was found on. */
function ancestors(lines: Line[], row: number, indent: number): Line[] {
  const out: Line[] = [];
  let limit = indent;
  for (let i = row - 1; i >= 0 && limit > 0; i--) {
    const l = lines[i];
    if (l.indent < 0 || l.key === null || l.indent >= limit) continue;
    out.unshift(l);
    limit = l.indent;
  }
  return out;
}

/** Whether a key path names a step or an option: login.steps.<id>(.alternative|.steps.<id>)*. */
function isBody(path: string[]): boolean {
  if (path.length < 3 || path[0] !== "login" || path[1] !== "steps" || path.length % 2 === 0) return false;
  for (let i = 3; i < path.length; i += 2) if (path[i] !== "alternative" && path[i] !== "steps") return false;
  return true;
}

/** The direct children of the key on `parent`: their keys and values. */
function children(lines: Line[], parent: Line): Line[] {
  const out: Line[] = [];
  let indent = -1;
  for (let i = parent.at + 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.indent < 0) continue;
    if (l.indent <= parent.indent) break;
    if (indent < 0) indent = l.indent;
    if (l.indent === indent && l.key) out.push(l);
  }
  return out;
}

function hookIds(lines: Line[]): Option[] {
  const hooks = lines.find((l) => l.indent === 0 && l.key === "hooks");
  return hooks ? children(lines, hooks).map((l) => ({ label: l.key!, detail: "hook", type: "variable" })) : [];
}

const BLOCKS: Option[] = PALETTE.filter((b) => b.id).map((b) => ({ label: b.id!, detail: b.name, type: "constant" }));

const CONDITION: Option[] = [
  ...KNOWN_PATHS.map((p) => ({ label: p.path, detail: p.about, type: "variable" as const })),
  ...OPERATORS.map((op) => ({ label: op, detail: "operator", type: "keyword" as const })),
];

/** Completions at `pos` in `text`, or null where there are none. `explicit`: asked for with Ctrl-Space. */
export function yamlCompletions(text: string, pos: number, explicit = false): Completions | null {
  const start = text.lastIndexOf("\n", pos - 1) + 1;
  const before = text.slice(start, pos);
  if (/^\s*#/.test(before)) return null;
  const lines = outline(text);
  const row = text.slice(0, start).split("\n").length - 1;

  // A key: only indentation and a word so far on the line
  const key = /^(\s*)(?:-\s+)?([\w-]*)$/.exec(before);
  if (key) {
    const word = key[2];
    if (!word && !explicit) return null;
    const indent = key[1].length;
    const up = ancestors(lines, row, indent);
    const path = up.map((l) => l.key!);
    const siblings = up.length > 0 ? new Set(children(lines, up[up.length - 1]).filter((l) => l.at !== row).map((l) => l.key)) : new Set(lines.filter((l) => l.indent === 0 && l.at !== row).map((l) => l.key));
    const fresh = (options: Option[]) => options.filter((o) => !siblings.has(o.label));
    let options: Option[] = [];
    if (path.length === 0) options = fresh(TOP);
    else if (path.length === 1 && path[0] === "login") options = fresh([{ label: "steps", detail: "in order", type: "property", apply: "steps:" }]);
    else if (path.length === 2 && path[0] === "hooks") options = fresh(HOOK);
    else if (path.length === 3 && path[0] === "hooks" && path[2] === "capabilities") options = fresh([{ label: "http", detail: "egress targets", type: "property", apply: "http: " }]);
    else if (isBody(path)) options = fresh(BODY);
    else if (path[path.length - 1] === "with" && isBody(path.slice(0, -1))) {
      const body = up[up.length - 2];
      const id = children(lines, body).find((l) => l.key === "block")?.value;
      const settings = PALETTE.find((b) => b.id === id)?.settings ?? {};
      options = fresh(Object.entries(settings).map(([k, v]) => ({ label: k, detail: `default ${String(v)}`, type: "property", apply: `${k}: ` })));
    }
    return options.length > 0 ? { from: pos - word.length, options } : null;
  }

  // A value: after `key: ` on the same line
  const value = /^\s*(?:-\s+)?([\w-]+):\s+(.*)$/.exec(before);
  if (!value) return null;
  const [, name, rest] = value;
  const word = /[\w.:-]*$/.exec(rest)![0];
  if (!word && !explicit) return null;
  const from = pos - word.length;
  if (name === "block" && rest === word) return { from, options: BLOCKS };
  if ((name === "before" || name === "after" || name === "pick") && rest === word) {
    const hooks = hookIds(lines);
    return hooks.length > 0 ? { from, options: hooks } : null;
  }
  if (name === "if") return { from, options: CONDITION };
  return null;
}
