/**
 * The flow document the editor works on: the YAML surface (see FlowCompiler) as ordered data.
 * The block editor and the YAML tab are two views of it; the server compiles and checks the YAML.
 * Everything here is pure, so the tests can build flows exactly as the UI does.
 */
import { Document, isMap, isPair, isScalar, isSeq, parse, parseDocument } from "yaml";
import { ID_PATTERN, KEY_PATTERN } from "@faye/contract";
import { CONSENT, CONSENT_HANDLER, SECTIONS, type Section } from "./entries.ts";

export type Literal = string | number | boolean;

/** IR predicate form: a bare path tests truthiness. */
export type Predicate =
  | string
  | { and: Predicate[] }
  | { or: Predicate[] }
  | { not: Predicate }
  | { exists: string }
  | { eq: [string, Literal] }
  | { ne: [string, Literal] }
  | { has: [string, Literal] }
  | { gt: [string, number] }
  | { lt: [string, number] };

export type ScreenSpec = { type: string; opts: Record<string, Literal> };

/** A step or an option: both take the same keys, run in this order. */
export type Body = {
  if?: Predicate;
  retry?: number;
  set?: [string, Literal][];
  hook?: string;
  screen?: ScreenSpec;
  use?: string;
  pick?: string;
  alternative?: Entry[];
  steps?: Entry[];
  fail?: string;
};

export type Entry = { id: string; body: Body };

/** A hook as declared in the flow: inline source, or a file next to the configured flow. */
export type HookSpec = { id: string; source?: string; file?: string; http: string[]; timeoutMs?: number };

export type { Section };

export type FlowDoc = {
  flow: string;
  version: number;
  hooks: HookSpec[];
  /** Every step, sign in first; from `consentStart` on, the steps run on the consent challenge. */
  login: Entry[];
  /** The comment above the document; other comments do not survive a block edit. */
  comment?: string;
};

/** Ids and vars names, as schema/ir.schema.json defines them. */
export const ID = new RegExp(ID_PATTERN);
export const KEY = new RegExp(KEY_PATTERN);

// ---- YAML in ----

export class FlowShapeError extends Error {}

/** Reads YAML into a document. Throws on YAML syntax errors and on shapes the editor cannot show. */
export function fromYaml(text: string): FlowDoc {
  const doc = parseDocument(text);
  if (doc.errors.length > 0) throw new FlowShapeError(doc.errors[0].message);
  const raw = doc.toJS() as unknown;
  if (!isObject(raw)) throw new FlowShapeError("expected a mapping");
  const out: FlowDoc = {
    flow: typeof raw.flow === "string" ? raw.flow : "",
    version: typeof raw.version === "number" ? raw.version : 1,
    hooks: [],
    login: [],
  };
  // A comment at the top lands on the document, or on the first key when no blank line follows it
  const first = isMap(doc.contents) ? doc.contents.items[0]?.key : null;
  const comment = doc.commentBefore ?? (isScalar(first) ? first.commentBefore : null);
  if (comment) out.comment = comment;
  if (raw.hooks !== undefined) {
    if (!isObject(raw.hooks)) throw new FlowShapeError("'hooks' must be a mapping");
    for (const [id, h] of Object.entries(raw.hooks)) {
      if (!isObject(h)) throw new FlowShapeError(`hook '${id}' must be a mapping`);
      const caps = isObject(h.capabilities) ? h.capabilities : {};
      out.hooks.push({
        id,
        ...(typeof h.source === "string" ? { source: h.source } : {}),
        ...(typeof h.file === "string" ? { file: h.file } : {}),
        http: Array.isArray(caps.http) ? caps.http.map(String) : [],
        ...(typeof h.timeout_ms === "number" ? { timeoutMs: h.timeout_ms } : {}),
      });
    }
  }
  for (const section of SECTIONS) {
    const s = raw[section];
    if (s === undefined) continue;
    if (!isObject(s)) throw new FlowShapeError(`'${section}' must be a mapping with 'steps'`);
    out[section] = entries(s.steps, `${section}.steps`);
  }
  // Before ADR 0026 consent was a section of its own; its steps now end the steps, so an old
  // version opens migrated. A clashing id gets a suffix rather than overwriting a step.
  if (raw.consent !== undefined) {
    if (!isObject(raw.consent)) throw new FlowShapeError("'consent' must be a mapping with 'steps'");
    const taken = new Set(out.login.map((e) => e.id));
    const moved = entries(raw.consent.steps, "consent.steps");
    // Moved steps run on the consent challenge only from one that asks for consent; refuse rather
    // than silently move a step before it into the sign in
    if (moved.length > 0 && !recordsConsent(moved[0].body)) {
      throw new FlowShapeError("move the 'consent' steps to the end of 'login.steps', starting with the one that asks for consent");
    }
    for (const e of moved) {
      let id = e.id;
      for (let n = 2; taken.has(id); n++) id = `${e.id}-${n}`;
      taken.add(id);
      out.login.push({ ...e, id });
    }
  }
  return out;
}

/** Whether a body asks for consent (shows its screen or calls its handler), itself or nested, as the compiler decides. */
export function recordsConsent(b: Body): boolean {
  return (
    b.use === CONSENT_HANDLER ||
    b.screen?.type === CONSENT.kind ||
    [...(b.alternative ?? []), ...(b.steps ?? [])].some((e) => recordsConsent(e.body))
  );
}

/**
 * The index of the first step that records consent: it and every step after it run on the
 * consent challenge, once the sign in was accepted. The length of the list if none does.
 */
export function consentStart(steps: Entry[]): number {
  const at = steps.findIndex((e) => recordsConsent(e.body));
  return at < 0 ? steps.length : at;
}

function entries(raw: unknown, at: string): Entry[] {
  if (raw === undefined || raw === null) return [];
  if (!isObject(raw)) throw new FlowShapeError(`'${at}' must be a mapping of id to body`);
  return Object.entries(raw).map(([id, b]) => ({ id, body: body(b, `${at}.${id}`) }));
}

function body(raw: unknown, at: string): Body {
  if (!isObject(raw)) throw new FlowShapeError(`'${at}' must be a mapping`);
  const out: Body = {};
  if (raw.if !== undefined) out.if = raw.if as Predicate;
  if (typeof raw.retry === "number") out.retry = raw.retry;
  if (isObject(raw.set)) out.set = Object.entries(raw.set).map(([k, v]) => [k, v as Literal]);
  if (typeof raw.hook === "string") out.hook = raw.hook;
  if (typeof raw.screen === "string") out.screen = { type: raw.screen, opts: {} };
  else if (isObject(raw.screen)) {
    const { type, ...opts } = raw.screen;
    out.screen = { type: String(type ?? ""), opts: opts as Record<string, Literal> };
  }
  if (typeof raw.use === "string") out.use = raw.use;
  if (typeof raw.pick === "string") out.pick = raw.pick;
  if (raw.alternative !== undefined) out.alternative = entries(raw.alternative, `${at}.alternative`);
  if (raw.steps !== undefined) out.steps = entries(raw.steps, `${at}.steps`);
  if (typeof raw.fail === "string") out.fail = raw.fail;
  return out;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

// ---- YAML out ----

/** The document as plain data, in the key order the YAML surface documents. */
export function toPlain(doc: FlowDoc): Record<string, unknown> {
  const out: Record<string, unknown> = { flow: doc.flow, version: doc.version };
  if (doc.hooks.length > 0) {
    out.hooks = Object.fromEntries(
      doc.hooks.map((h) => {
        const spec: Record<string, unknown> = {};
        if (h.file !== undefined) spec.file = h.file;
        else spec.source = h.source ?? "";
        if (h.http.length > 0) spec.capabilities = { http: h.http };
        if (h.timeoutMs !== undefined) spec.timeout_ms = h.timeoutMs;
        return [h.id, spec];
      }),
    );
  }
  out.login = { steps: plainEntries(doc.login) };
  return out;
}

function plainEntries(list: Entry[]): Record<string, unknown> {
  return Object.fromEntries(list.map((e) => [e.id, plainBody(e.body)]));
}

function plainBody(b: Body): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (b.if !== undefined) out.if = b.if;
  if (b.retry !== undefined) out.retry = b.retry;
  if (b.set !== undefined) out.set = Object.fromEntries(b.set);
  if (b.hook !== undefined) out.hook = b.hook;
  if (b.screen !== undefined) {
    out.screen = Object.keys(b.screen.opts).length === 0 ? b.screen.type : { type: b.screen.type, ...b.screen.opts };
  }
  if (b.use !== undefined) out.use = b.use;
  if (b.pick !== undefined) out.pick = b.pick;
  if (b.alternative !== undefined) out.alternative = plainEntries(b.alternative);
  if (b.steps !== undefined) out.steps = plainEntries(b.steps);
  if (b.fail !== undefined) out.fail = b.fail;
  return out;
}

/** Short collections (predicates, screen options) are written inline, like the shipped flows. */
const INLINE = 64;

export function toYaml(doc: FlowDoc): string {
  const out = new Document(toPlain(doc));
  if (doc.comment) out.commentBefore = doc.comment;
  inline(out.contents);
  return out.toString({ lineWidth: 0 });
}

function inline(node: unknown) {
  if (!isMap(node)) return;
  for (const pair of node.items) {
    const key = isScalar(pair.key) ? String(pair.key.value) : "";
    if (key === "if" || key === "screen" || key === "set" || key === "capabilities") flowify(pair.value);
    else inline(pair.value);
  }
}

/** Writes a collection inline when it is short, else its members, and so on down. */
function flowify(node: unknown) {
  if (!isMap(node) && !isSeq(node)) return;
  if (JSON.stringify(node.toJSON()).length <= INLINE) {
    node.flow = true;
    return;
  }
  for (const item of node.items) flowify(isPair(item) ? item.value : item);
}

/**
 * A YAML flow with only what the compiler reads: steps and options keep their order, every other
 * mapping is sorted. Two flows with the same canonical form compile to the same IR.
 */
export function canonical(text: string): string {
  const walk = (v: unknown, ordered: boolean): unknown => {
    if (Array.isArray(v)) return v.map((x) => walk(x, false));
    if (!isObject(v)) return v;
    if (ordered) return Object.entries(v).map(([k, x]) => [k, walk(x, false)]);
    return Object.fromEntries(
      Object.keys(v)
        .sort()
        .map((k) => [k, walk(v[k], k === "steps" || k === "alternative")]),
    );
  };
  return JSON.stringify(walk(parse(text), false));
}

// ---- addressing ----

/**
 * Where an entry sits: its section and, from there, which list and index at each level. A list
 * is the steps of the section or of a body, or the options of a body's alternative.
 */
export type Address = { section: Section; path: [ListKind, number][] };
export type ListKind = "steps" | "alternative";

/** A list an entry can be dropped into: the steps of a section, or a list of the entry at `owner`. */
export type ListRef = { section: Section; owner: [ListKind, number][]; kind: ListKind };

export function addressKey(a: Address): string {
  return [a.section, ...a.path.map(([k, i]) => `${k}.${i}`)].join("/");
}

export function listKey(l: ListRef): string {
  return [l.section, ...l.owner.map(([k, i]) => `${k}.${i}`), l.kind].join("/");
}

export function parseListKey(key: string): ListRef {
  const parts = key.split("/");
  return {
    section: parts[0] as Section,
    kind: parts[parts.length - 1] as ListKind,
    owner: parts.slice(1, -1).map((p) => {
      const [kind, i] = p.split(".");
      return [kind as ListKind, Number(i)];
    }),
  };
}

export function listOf(doc: FlowDoc, ref: ListRef): Entry[] | undefined {
  let list: Entry[] | undefined = doc[ref.section];
  for (let level = 0; level < ref.owner.length; level++) {
    const [, i] = ref.owner[level];
    const kind = level + 1 < ref.owner.length ? ref.owner[level + 1][0] : ref.kind;
    list = list?.[i]?.body[kind];
  }
  return list;
}

export function entryAt(doc: FlowDoc, a: Address): Entry | undefined {
  if (a.path.length === 0) return undefined;
  const [kind, index] = a.path[a.path.length - 1];
  return listOf(doc, { section: a.section, owner: a.path.slice(0, -1), kind })?.[index];
}

/** The list an entry is in, and its index there. */
export function parentOf(a: Address): { list: ListRef; index: number } {
  const [kind, index] = a.path[a.path.length - 1];
  return { list: { section: a.section, owner: a.path.slice(0, -1), kind }, index };
}

/** Copies the document along the way to a list, and hands the copy of that list to `edit`. */
function editList(doc: FlowDoc, ref: ListRef, edit: (list: Entry[]) => void): FlowDoc {
  const next: FlowDoc = { ...doc, hooks: doc.hooks };
  const root = next[ref.section];
  if (!root) {
    if (ref.owner.length > 0) return doc;
    next[ref.section] = [];
  }
  let list = [...(next[ref.section] ?? [])];
  next[ref.section] = list;
  for (let level = 0; level < ref.owner.length; level++) {
    const [, i] = ref.owner[level];
    const entry = list[i];
    if (!entry) return doc;
    const kind = level + 1 < ref.owner.length ? ref.owner[level + 1][0] : ref.kind;
    const inner = [...(entry.body[kind] ?? [])];
    list[i] = { ...entry, body: { ...entry.body, [kind]: inner } };
    list = inner;
  }
  edit(list);
  return next;
}

// ---- edits ----

/** Every step id in the document; the checker requires them unique across the flow. */
export function stepIds(doc: FlowDoc): Set<string> {
  const out = new Set<string>();
  const walk = (list: Entry[] | undefined, steps: boolean) => {
    for (const e of list ?? []) {
      if (steps) out.add(e.id);
      walk(e.body.steps, true);
      walk(e.body.alternative, false);
    }
  };
  walk(doc.login, true);
  return out;
}

/** `base`, or `base-2`, `base-3`, ... whichever is free in the list it goes to. */
export function freeId(doc: FlowDoc, ref: ListRef, base: string): string {
  const taken =
    ref.kind === "steps" ? stepIds(doc) : new Set((listOf(doc, ref) ?? []).map((e) => e.id));
  if (!taken.has(base)) return base;
  for (let n = 2; ; n++) if (!taken.has(`${base}-${n}`)) return `${base}-${n}`;
}

export function insert(doc: FlowDoc, ref: ListRef, index: number, entry: Entry): FlowDoc {
  const id = freeId(doc, ref, entry.id);
  return editList(doc, ref, (list) => list.splice(Math.min(index, list.length), 0, { ...entry, id }));
}

export function remove(doc: FlowDoc, a: Address): FlowDoc {
  const { list, index } = parentOf(a);
  return editList(doc, list, (l) => l.splice(index, 1));
}

/** True if `ref` is the list `a` owns, or a list inside it: an entry cannot move into itself. */
export function inside(ref: ListRef, a: Address): boolean {
  if (ref.section !== a.section || ref.owner.length < a.path.length) return false;
  return a.path.every(([k, i], n) => ref.owner[n][0] === k && ref.owner[n][1] === i);
}

/** Moves an entry to `index` of `to` (an index in the list as it is before the move). */
export function move(doc: FlowDoc, from: Address, to: ListRef, index: number): FlowDoc {
  const entry = entryAt(doc, from);
  if (!entry || inside(to, from)) return doc;
  const origin = parentOf(from);
  const same = listKey(origin.list) === listKey(to);
  // Removing first shifts later positions in the same list, and lists nested after it
  const removed = remove(doc, from);
  const target = shift(to, origin.list, origin.index);
  const at = same && index > origin.index ? index - 1 : index;
  // The id only has to change if it is taken where the entry goes
  const taken =
    to.kind === "steps"
      ? stepIds(removed).has(entry.id)
      : (listOf(removed, target) ?? []).some((e) => e.id === entry.id);
  const id = taken ? freeId(removed, target, entry.id) : entry.id;
  return editList(removed, target, (l) => l.splice(Math.min(at, l.length), 0, { ...entry, id }));
}

/** A list address after the entry at `index` of `removedFrom` is gone. */
function shift(ref: ListRef, removedFrom: ListRef, index: number): ListRef {
  const depth = removedFrom.owner.length;
  if (ref.section !== removedFrom.section || ref.owner.length <= depth) return ref;
  const prefix = ref.owner.slice(0, depth);
  if (!prefix.every(([k, i], n) => removedFrom.owner[n][0] === k && removedFrom.owner[n][1] === i)) return ref;
  const [kind, i] = ref.owner[depth];
  if (kind !== removedFrom.kind || i <= index) return ref;
  const owner = [...ref.owner];
  owner[depth] = [kind, i - 1];
  return { ...ref, owner };
}

export function updateBody(doc: FlowDoc, a: Address, patch: Partial<Body>): FlowDoc {
  const { list, index } = parentOf(a);
  return editList(doc, list, (l) => {
    const body: Body = { ...l[index].body, ...patch };
    for (const k of Object.keys(patch) as (keyof Body)[]) if (patch[k] === undefined) delete body[k];
    l[index] = { ...l[index], body };
  });
}

export function rename(doc: FlowDoc, a: Address, id: string): FlowDoc {
  const { list, index } = parentOf(a);
  return editList(doc, list, (l) => {
    l[index] = { ...l[index], id };
  });
}

// ---- hooks ----

export function setHook(doc: FlowDoc, hook: HookSpec, previousId?: string): FlowDoc {
  const at = doc.hooks.findIndex((h) => h.id === (previousId ?? hook.id));
  const hooks = [...doc.hooks];
  if (at < 0) hooks.push(hook);
  else hooks[at] = hook;
  return { ...doc, hooks };
}

export function removeHook(doc: FlowDoc, id: string): FlowDoc {
  return { ...doc, hooks: doc.hooks.filter((h) => h.id !== id) };
}

// ---- palette ----

export type Block = {
  name: string;
  group: "Steps" | "Logic";
  description: string;
  /** Default id when added as a step, and as an option. */
  stepId: string;
  optionId: string;
  body: () => Body;
};

/** High level blocks expand to the YAML a person would write for them, with sensible defaults. */
export const PALETTE: Block[] = [
  {
    name: "Identifier",
    group: "Steps",
    description: "Ask who is signing in, then resolve the account",
    stepId: "identify",
    optionId: "identify",
    body: () => ({ screen: { type: "identifier", opts: {} }, use: "identity.resolve" }),
  },
  {
    name: "Password",
    group: "Steps",
    description: "Verify a password, three attempts",
    stepId: "credential",
    optionId: "password",
    body: () => ({ retry: 3, screen: { type: "password", opts: {} }, use: "password.verify" }),
  },
  {
    name: "Passkey",
    group: "Steps",
    description: "Sign in with a passkey",
    stepId: "passkey",
    optionId: "passkey",
    body: () => ({ screen: { type: "passkey", opts: {} }, use: "webauthn.assert" }),
  },
  {
    name: "Add passkey",
    group: "Steps",
    description: "Offer to create a passkey",
    stepId: "add-passkey",
    optionId: "add-passkey",
    body: () => ({ screen: { type: "passkey", opts: { register: true } }, use: "webauthn.register" }),
  },
  {
    name: "TOTP",
    group: "Steps",
    description: "Verify a code from an authenticator app, three attempts",
    stepId: "second-factor",
    optionId: "totp",
    body: () => ({ retry: 3, screen: { type: "otp", opts: {} }, use: "totp.verify" }),
  },
  {
    name: "Enroll TOTP",
    group: "Steps",
    description: "Set up an authenticator app, three attempts",
    stepId: "enroll",
    optionId: "enroll",
    body: () => ({ retry: 3, screen: { type: "otp_enroll", opts: {} }, use: "totp.enroll" }),
  },
  {
    name: "Consent",
    group: "Steps",
    description: "Ask to share data with the client; runs after sign in, on the consent challenge",
    stepId: "consent",
    optionId: "consent",
    body: () => ({ screen: { type: "consent", opts: {} }, use: "consent.record" }),
  },
  {
    name: "Choose",
    group: "Logic",
    description: "Offer alternatives; the person (or a hook) picks one",
    stepId: "choose",
    optionId: "choose",
    body: () => ({ alternative: [] }),
  },
  {
    name: "Group",
    group: "Logic",
    description: "Nested steps, e.g. under one condition",
    stepId: "group",
    optionId: "group",
    body: () => ({ steps: [] }),
  },
  {
    name: "Hook",
    group: "Logic",
    description: "Run a JavaScript hook: it may set vars or fail",
    stepId: "hook",
    optionId: "hook",
    body: () => ({ hook: "" }),
  },
  {
    name: "Set",
    group: "Logic",
    description: "Write a value into vars",
    stepId: "set",
    optionId: "set",
    body: () => ({ set: [["name", "value"]] }),
  },
  {
    name: "Fail",
    group: "Logic",
    description: "End the section with a reason",
    stepId: "deny",
    optionId: "deny",
    body: () => ({ fail: "denied" }),
  },
];

export function block(name: string): Block {
  const b = PALETTE.find((p) => p.name === name);
  if (!b) throw new Error(`no block ${name}`);
  return b;
}

/**
 * An entry for a palette block, named for the kind of list it goes to. A Hook block starts on the
 * flow's first declared hook, and takes its id.
 */
export function fromBlock(b: Block, kind: ListKind, doc?: FlowDoc): Entry {
  const hook = b.name === "Hook" ? doc?.hooks[0]?.id : undefined;
  if (hook) return { id: hook, body: { hook } };
  return { id: kind === "steps" ? b.stepId : b.optionId, body: b.body() };
}

export function emptyFlow(id: string): FlowDoc {
  return { flow: id, version: 1, hooks: [], login: [] };
}

// ---- summaries ----

/** One line for the tree: what a body does. */
export function summary(b: Body): string {
  const parts: string[] = [];
  if (b.hook !== undefined) parts.push(`hook ${b.hook || "?"}`);
  if (b.set) parts.push(`set ${b.set.map(([k]) => k).join(", ")}`);
  if (b.screen) parts.push(`${b.screen.type} screen`);
  if (b.use) parts.push(b.use);
  if (b.retry !== undefined) parts.push(`retry ${b.retry}`);
  if (b.alternative) parts.push(`choose ${b.alternative.length}${b.pick ? `, ${b.pick} picks` : ""}`);
  if (b.steps) parts.push(`${b.steps.length} steps`);
  if (b.fail !== undefined) parts.push(`fail ${b.fail}`);
  return parts.join(" · ");
}

/**
 * The key the server uses for an entry in a path's visits: a step's id, or an option's id after
 * the key of the step or option it belongs to.
 */
export function visitKeys(doc: FlowDoc): Map<string, string> {
  const out = new Map<string, string>();
  const walk = (list: Entry[] | undefined, section: Section, path: [ListKind, number][], kind: ListKind, owner: string) => {
    (list ?? []).forEach((e, i) => {
      const here: [ListKind, number][] = [...path, [kind, i]];
      const key = kind === "steps" ? e.id : `${owner}/${e.id}`;
      out.set(addressKey({ section, path: here }), key);
      walk(e.body.alternative, section, here, "alternative", key);
      walk(e.body.steps, section, here, "steps", key);
    });
  };
  for (const section of SECTIONS) walk(doc[section], section, [], "steps", "");
  return out;
}

export function parseAddress(key: string): Address {
  const [section, ...rest] = key.split("/");
  return {
    section: section as Section,
    path: rest.map((p) => {
      const [kind, i] = p.split(".");
      return [kind as ListKind, Number(i)];
    }),
  };
}
