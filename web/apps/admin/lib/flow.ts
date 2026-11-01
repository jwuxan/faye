/**
 * The flow document the editor works on: the YAML surface (see FlowCompiler) as ordered data.
 * The block editor and the YAML tab are two views of it; the server compiles and checks the YAML.
 * Everything here is pure, so the tests can build flows exactly as the UI does.
 */
import { Document, isMap, isPair, isScalar, isSeq, parse, parseDocument } from "yaml";
import { ID_PATTERN, KEY_PATTERN } from "@faye/contract";
import { AUTHENTICATE, CONSENT_HANDLER, SECTIONS, type Section } from "./entries.ts";

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

/**
 * A step or an option: both take the same keys, run in this order. A body is one kind of block
 * (ADR 0030), which takes only some of them; `blockOf` says which, and the server checks it. A
 * built in block brings its own screen and handler: a flow names it and changes only its settings.
 */
export type Body = {
  if?: Predicate;
  /** A hook that runs before the block, once, around its attempts. */
  before?: string;
  set?: [string, Literal][];
  /** A built in block's id (`password`), and the settings that differ from its defaults. */
  block?: string;
  with?: Record<string, Literal>;
  /** Where the Login UI shows the block's screen (ADR 0029); the server checks it. */
  path?: string;
  pick?: string;
  alternative?: Entry[];
  steps?: Entry[];
  fail?: string;
  /** A hook that runs after the block. */
  after?: string;
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
    PALETTE.find((p) => p.id === b.block)?.use === CONSENT_HANDLER ||
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
  const out: Entry[] = [];
  // Before ADR 0030 a hook could be a step of its own: it now runs after the block before it, or
  // when that already has one, before the block after it
  let waiting: [string, string] | null = null;
  const alone = (id: string, hook: string) =>
    new FlowShapeError(`'${at}.${id}' runs hook '${hook}' on its own: make it the 'before' or 'after' hook of a block`);
  // Moving a hook onto a neighbour must not change when it runs: never under the neighbour's
  // condition, never after a fail, never out of a choice's options
  const steps = at.endsWith("steps");
  for (const [id, b] of Object.entries(raw)) {
    if (isObject(b) && typeof b.hook === "string" && Object.keys(b).every((k) => k === "hook" || k === "if")) {
      if (!steps || b.if !== undefined || waiting) throw alone(...(waiting ?? [id, b.hook]));
      const previous = out.at(-1);
      if (previous && previous.body.after === undefined && previous.body.if === undefined && previous.body.fail === undefined) {
        previous.body.after = b.hook;
      } else waiting = [id, b.hook];
      continue;
    }
    const entry = { id, body: body(b, `${at}.${id}`) };
    if (waiting) {
      if (entry.body.before !== undefined || entry.body.if !== undefined) throw alone(...waiting);
      entry.body.before = waiting[1];
      waiting = null;
    }
    out.push(entry);
  }
  if (waiting) throw alone(...waiting);
  return out;
}

function body(raw: unknown, at: string): Body {
  if (!isObject(raw)) throw new FlowShapeError(`'${at}' must be a mapping`);
  const out: Body = {};
  if (raw.if !== undefined) out.if = raw.if as Predicate;
  if (raw.use !== undefined && raw.set !== undefined) {
    throw new FlowShapeError(`'${at}' sets vars and calls a handler: make it a Set step and a block, under the same condition`);
  }
  if (raw.use === undefined) {
    if (raw.retry !== undefined) throw new FlowShapeError(`'${at}' retries a group or a choice; only a block has attempts`);
    if (raw.screen !== undefined) throw new FlowShapeError(`'${at}' shows a screen without a handler; a block brings both`);
  }
  if (typeof raw.before === "string") out.before = raw.before;
  // Before ADR 0030 a body's hook ran after its sets and before its screen
  else if (typeof raw.hook === "string" && raw.use === undefined && raw.set !== undefined) out.after = raw.hook;
  else if (typeof raw.hook === "string") out.before = raw.hook;
  if (isObject(raw.set)) out.set = Object.entries(raw.set).map(([k, v]) => [k, v as Literal]);
  if (typeof raw.block === "string") out.block = raw.block;
  if (isObject(raw.with)) out.with = { ...(raw.with as Record<string, Literal>) };
  // Before ADR 0030 a step named a handler and its screen; it names the handler's block now
  if (typeof raw.use === "string") Object.assign(out, legacyBlock(raw, at));
  if (typeof raw.path === "string") out.path = raw.path;
  if (typeof raw.pick === "string") out.pick = raw.pick;
  if (raw.alternative !== undefined) out.alternative = entries(raw.alternative, `${at}.alternative`);
  if (raw.steps !== undefined) out.steps = entries(raw.steps, `${at}.steps`);
  if (typeof raw.fail === "string") out.fail = raw.fail;
  if (typeof raw.after === "string") out.after = raw.after;
  return out;
}

/** `use`, `screen` and `retry` as the block that has the handler, with the settings they meant. */
function legacyBlock(raw: Record<string, unknown>, at: string): Body {
  const b = PALETTE.find((p) => p.use === raw.use);
  if (!b) throw new FlowShapeError(`'${at}' uses handler '${String(raw.use)}', which no block has`);
  const settings: Record<string, Literal> = {};
  const screen = raw.screen;
  const type = isObject(screen) ? screen.type : screen;
  if (type === undefined) throw new FlowShapeError(`'${at}' calls '${String(raw.use)}' with no screen; its block always shows one`);
  if (type !== b.screen) throw new FlowShapeError(`'${at}' shows '${String(type)}', but the ${b.name} block shows '${b.screen}'`);
  if (isObject(screen)) {
    for (const [k, v] of Object.entries(screen)) if (k !== "type" && k in (b.settings ?? {}) && v !== false) settings[k] = v as Literal;
    if (typeof screen.path === "string" && typeof raw.path !== "string") raw.path = screen.path;
  }
  const attempts = typeof raw.retry === "number" ? raw.retry : 1;
  // An identifier that did not ask for the password never retried
  const retries = b.settings?.attempts !== undefined && (b.role !== "identifier" || settings.password === true);
  if (retries && attempts !== b.settings?.attempts) settings.attempts = attempts;
  return { block: b.id, ...(Object.keys(settings).length > 0 ? { with: settings } : {}) };
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
  if (b.before !== undefined) out.before = b.before;
  if (b.set !== undefined) out.set = Object.fromEntries(b.set);
  if (b.block !== undefined) out.block = b.block;
  if (b.with !== undefined && Object.keys(b.with).length > 0) out.with = b.with;
  if (b.path !== undefined) out.path = b.path;
  if (b.pick !== undefined) out.pick = b.pick;
  if (b.alternative !== undefined) out.alternative = plainEntries(b.alternative);
  if (b.steps !== undefined) out.steps = plainEntries(b.steps);
  if (b.fail !== undefined) out.fail = b.fail;
  if (b.after !== undefined) out.after = b.after;
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
    if (key === "if" || key === "with" || key === "set" || key === "capabilities") flowify(pair.value);
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

/**
 * Removes an entry; an option that leaves its choice with one option makes that option the step
 * (a choice needs two), under the choice's condition and hooks.
 */
export function removeEntry(doc: FlowDoc, a: Address): FlowDoc {
  const { list, index } = parentOf(a);
  const rest = (listOf(doc, list) ?? []).filter((_, i) => i !== index);
  if (list.kind !== "alternative" || rest.length !== 1) return remove(doc, a);
  const owner: Address = { section: a.section, path: list.owner };
  const choice = entryAt(doc, owner)!.body;
  const only = rest[0].body;
  const test = [choice.if, only.if].filter((p): p is Predicate => p !== undefined);
  const body: Body = {
    ...only,
    if: test.length === 0 ? undefined : test.length === 1 ? test[0] : { and: test },
    before: choice.before,
    after: choice.after,
  };
  for (const k of Object.keys(body) as (keyof Body)[]) if (body[k] === undefined) delete body[k];
  const { list: outer, index: at } = parentOf(owner);
  return editList(doc, outer, (l) => {
    l[at] = { ...l[at], body };
  });
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

/**
 * What a block is for, which decides where it may go and which keys it takes, as the server's
 * blocks say (faye-app Blocks, ADR 0030): the identifier first and only there; options are one
 * credential block or a fail; consent a step of its own.
 */
export type Role = "identifier" | "password" | "credential" | "action" | "consent" | "choose" | "group" | "set" | "fail";

export type Block = {
  name: string;
  group: "Steps" | "Logic";
  description: string;
  role: Role;
  /** A built in block's id, as the server's blocks name it (faye-identity IdentityBlock). */
  id?: string;
  /** Its handler, for opening YAML from before ADR 0030 only; the console never shows it. */
  use?: string;
  /** What a flow may change under `with`, and the defaults. */
  settings?: Record<string, Literal>;
  /** The screen it shows, for opening YAML from before ADR 0030 only. */
  screen?: string;
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
    description: "Ask who is signing in, then find the account; always the first step",
    role: "identifier",
    use: "identity.resolve",
    id: "identifier",
    screen: "identifier",
    settings: { passkey: false, password: false, attempts: 3 },
    stepId: "identify",
    optionId: "identify",
    body: () => ({ block: "identifier" }),
  },
  {
    name: "Password",
    group: "Steps",
    description: "Verify a password",
    role: "password",
    use: "password.verify",
    id: "password",
    screen: "password",
    settings: { attempts: 3 },
    stepId: "credential",
    optionId: "password",
    body: () => ({ block: "password" }),
  },
  {
    name: "Passkey",
    group: "Steps",
    description: "Sign in with a passkey",
    role: "credential",
    use: "webauthn.assert",
    id: "passkey",
    screen: "passkey",
    settings: { attempts: 1 },
    stepId: "passkey",
    optionId: "passkey",
    body: () => ({ block: "passkey" }),
  },
  {
    name: "Add passkey",
    group: "Steps",
    description: "Offer to create a passkey, once signed in",
    role: "action",
    use: "webauthn.register",
    id: "add-passkey",
    screen: "passkey",
    settings: {},
    stepId: "add-passkey",
    optionId: "add-passkey",
    body: () => ({ block: "add-passkey" }),
  },
  {
    name: "TOTP",
    group: "Steps",
    description: "Verify a code from an authenticator app",
    role: "credential",
    use: "totp.verify",
    id: "totp",
    screen: "otp",
    settings: { attempts: 3 },
    stepId: "second-factor",
    optionId: "totp",
    body: () => ({ block: "totp" }),
  },
  {
    name: "Enroll TOTP",
    group: "Steps",
    description: "Set up an authenticator app",
    role: "credential",
    use: "totp.enroll",
    id: "enroll-totp",
    screen: "otp_enroll",
    settings: { attempts: 3 },
    stepId: "enroll",
    optionId: "enroll",
    body: () => ({ block: "enroll-totp" }),
  },
  {
    name: "Consent",
    group: "Steps",
    description: "Ask to share data with the client; runs after sign in, on the consent challenge",
    role: "consent",
    use: "consent.record",
    id: "consent",
    screen: "consent",
    settings: {},
    stepId: "consent",
    optionId: "consent",
    body: () => ({ block: "consent" }),
  },
  {
    name: "Choose",
    group: "Logic",
    description: "Offer two or more credential blocks; the person (or a hook) picks one",
    role: "choose",
    stepId: "choose",
    optionId: "choose",
    body: () => ({ alternative: [] }),
  },
  {
    name: "Group",
    group: "Logic",
    description: "Nested steps, e.g. under one condition",
    role: "group",
    stepId: "group",
    optionId: "group",
    body: () => ({ steps: [] }),
  },
  {
    name: "Set",
    group: "Logic",
    description: "Write a value into vars",
    role: "set",
    stepId: "set",
    optionId: "set",
    body: () => ({ set: [["name", "value"]] }),
  },
  {
    name: "Fail",
    group: "Logic",
    description: "End the sign in with a reason",
    role: "fail",
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

/** An entry for a palette block, named for the kind of list it goes to. */
export function fromBlock(b: Block, kind: ListKind): Entry {
  return { id: kind === "steps" ? b.stepId : b.optionId, body: b.body() };
}

/**
 * The palette block a body is, as the compiler decides: by its handler, else by what it does.
 * Undefined for a handler the console has no block for, and for a body that is no block yet.
 */
export function blockOf(b: Body): Block | undefined {
  if (b.block !== undefined) return PALETTE.find((p) => p.id === b.block);
  const role: Role | undefined =
    b.alternative !== undefined ? "choose" : b.steps !== undefined ? "group" : b.set !== undefined ? "set" : b.fail !== undefined ? "fail" : undefined;
  return PALETTE.find((p) => p.role === role);
}

/** The roles an option of a choice may have: one credential block, or a fail. */
const OPTION_ROLES: Role[] = ["password", "credential", "action", "fail"];

/** The identifier's setting with which it asks for the password on its own screen. */
export const PASSWORD_VIEW = "password";

/** Whether the flow's identifier asks for the password, so no Password block may. */
export function asksPassword(doc: FlowDoc): boolean {
  const first = doc.login[0]?.body;
  return !!first && blockOf(first)?.role === "identifier" && first.with?.[PASSWORD_VIEW] === true;
}

/** Defaults a tenant sets for its blocks' settings, by block id (ADR 0030); they replace the blocks' own. */
export type Defaults = Record<string, Record<string, Literal>>;

/** A setting's default for this step: the tenant's, else the block's. */
export function byDefault(b: Body, name: string, defaults: Defaults = {}): Literal | undefined {
  const block = blockOf(b);
  return (block?.id !== undefined ? defaults[block.id]?.[name] : undefined) ?? block?.settings?.[name];
}

/** A setting of a block step: what the flow says, else the default. */
export function setting(b: Body, name: string, defaults: Defaults = {}): Literal | undefined {
  return b.with?.[name] ?? byDefault(b, name, defaults);
}

/** The step's settings with `name` at `value`, leaving out what equals the default. */
export function withSetting(b: Body, name: string, value: Literal | undefined, defaults: Defaults = {}): Record<string, Literal> | undefined {
  const next = { ...(b.with ?? {}) };
  if (value === undefined || value === byDefault(b, name, defaults)) delete next[name];
  else next[name] = value;
  return Object.keys(next).length > 0 ? next : undefined;
}

/**
 * Why a block of this role cannot go at `index` of `ref`, or null if it can: the rules the
 * compiler enforces, so the console offers only what will compile.
 */
export function misfit(doc: FlowDoc, ref: ListRef, index: number, role: Role | undefined): string | null {
  const top = ref.owner.length === 0 && ref.kind === "steps";
  const identified = doc.login[0] !== undefined && blockOf(doc.login[0].body)?.role === "identifier";
  if (role === "identifier") {
    if (identified) return "The flow already starts with the Identifier";
    return top && index === 0 ? null : "The Identifier is always the first step";
  }
  if (top && index === 0) return identified ? "The Identifier stays the first step" : "Start with the Identifier";
  if (ref.kind === "alternative" && role !== undefined && !OPTION_ROLES.includes(role)) {
    return "An option is one credential block, or Fail";
  }
  if (role === "consent" && !top) return "Consent is a step of its own";
  if (role === "password" && asksPassword(doc)) return "The Identifier already asks for the password";
  // Steps from Consent on run after sign in, on the consent challenge (ADR 0026)
  const split = consentStart(doc.login);
  const at = top ? index : ref.owner[0][1];
  if (role !== undefined && CREDENTIAL_ROLES.includes(role) && split < doc.login.length && (top ? at > split : at >= split)) {
    return "Steps after Consent run once signed in; put credentials before it";
  }
  if (role === "consent" && doc.login.slice(index).some((e) => proves(e.body))) return "Consent comes after every credential step";
  return null;
}

/** The roles that verify a credential, which a consent entry may not (ADR 0026). */
const CREDENTIAL_ROLES: Role[] = ["password", "credential"];

/** Whether a body, or one nested in it, verifies a credential. */
function proves(b: Body): boolean {
  const role = blockOf(b)?.role;
  return (role !== undefined && CREDENTIAL_ROLES.includes(role)) || [...(b.alternative ?? []), ...(b.steps ?? [])].some((e) => proves(e.body));
}

/** How many blocks of the document sit where the compiler refuses them; a good edit adds none. */
export function misplaced(doc: FlowDoc): number {
  let count = 0;
  const password = asksPassword(doc);
  const split = consentStart(doc.login);
  const walk = (list: Entry[] | undefined, ref: ListRef) =>
    (list ?? []).forEach((e, i) => {
      const role = blockOf(e.body)?.role;
      const top = ref.owner.length === 0 && ref.kind === "steps";
      if ((role === "identifier") !== (top && i === 0)) count++;
      else if (ref.kind === "alternative" && role !== undefined && !OPTION_ROLES.includes(role)) count++;
      else if (role === "consent" && !top) count++;
      else if (role === "password" && password) count++;
      else if (role !== undefined && CREDENTIAL_ROLES.includes(role) && (ref.owner[0]?.[1] ?? i) >= split) count++;
      const owner: [ListKind, number][] = [...ref.owner, [ref.kind, i]];
      walk(e.body.steps, { ...ref, owner, kind: "steps" });
      walk(e.body.alternative, { ...ref, owner, kind: "alternative" });
    });
  walk(doc.login, { section: AUTHENTICATE, owner: [], kind: "steps" });
  return count;
}

/** Whether YAML still uses the keys ADR 0030 replaced, which the server refuses until it is rewritten. */
export function isLegacy(text: string): boolean {
  const walk = (v: unknown, inBody: boolean): boolean => {
    if (Array.isArray(v)) return false;
    if (!isObject(v)) return false;
    return Object.entries(v).some(([k, x]) =>
      inBody && ["use", "screen", "retry", "hook"].includes(k) ? true : k === "steps" || k === "alternative" ? isObject(x) && Object.values(x).some((b) => walk(b, true)) : k === "login" || k === "consent" ? walk(x, false) : false,
    );
  };
  try {
    const raw = parse(text) as unknown;
    // The consent section of before ADR 0026 opens moved into the steps, too
    if (isObject(raw) && raw.consent !== undefined) return true;
    return walk(raw, false);
  } catch {
    return false;
  }
}

export function emptyFlow(id: string): FlowDoc {
  return { flow: id, version: 1, hooks: [], login: [] };
}

// ---- summaries ----

/** One line for the tree: what a body does. */
export function summary(b: Body): string {
  const parts: string[] = [];
  if (b.before !== undefined) parts.push(`before ${b.before || "?"}`);
  if (b.set) parts.push(`set ${b.set.map(([k]) => k).join(", ")}`);
  if (b.block) parts.push(b.block);
  for (const [k, v] of Object.entries(b.with ?? {})) parts.push(`${k} ${String(v)}`);
  if (b.alternative) parts.push(`choose ${b.alternative.length}${b.pick ? `, ${b.pick} picks` : ""}`);
  if (b.steps) parts.push(`${b.steps.length} steps`);
  if (b.fail !== undefined) parts.push(`fail ${b.fail}`);
  if (b.after !== undefined) parts.push(`after ${b.after || "?"}`);
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
