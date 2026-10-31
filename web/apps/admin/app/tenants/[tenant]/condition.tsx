"use client";

import { cn } from "@faye/ui/lib/utils";
import { ListPlus, Plus, X } from "lucide-react";
import { createContext, useContext, useId } from "react";
import type { Literal, Predicate } from "../../../lib/flow";
import { children, comparison, compare, describe, KNOWN_PATHS, KNOWN_VALUES, kindOf, PATH } from "../../../lib/predicate";
import { field, IconButton, Segmented, Select } from "../../ui";

/** Names and values the flow's own set blocks write to vars, for suggestions. */
export const FlowVars = createContext<Map<string, string[]>>(new Map());

/** Kept for the panels that still use the plain field style. */
export const FIELD = field;

/**
 * Leaf operators as a person reads them. Negations of a test are offered as operators of their
 * own ("is false", "does not contain"), so a condition is a list of rows, not a tree of nots.
 */
const LEAF_OPS = [
  { op: "true", label: "is true" },
  { op: "false", label: "is false" },
  { op: "eq", label: "=" },
  { op: "ne", label: "≠" },
  { op: "has", label: "contains" },
  { op: "lacks", label: "excludes" },
  { op: "gt", label: ">" },
  { op: "lt", label: "<" },
  { op: "set", label: "is set" },
  { op: "unset", label: "is not set" },
] as const;
type LeafOp = (typeof LEAF_OPS)[number]["op"];
type Leaf = { op: LeafOp; path: string; value: Literal };

/** A predicate as one row, if it is a (possibly negated) test of one path. */
function asLeaf(p: Predicate): Leaf | null {
  if (typeof p === "string") return { op: "true", path: p, value: "" };
  if ("exists" in p) return { op: "set", path: p.exists, value: "" };
  if ("not" in p) {
    const inner = p.not;
    if (typeof inner === "string") return { op: "false", path: inner, value: "" };
    if ("exists" in inner) return { op: "unset", path: inner.exists, value: "" };
    if ("has" in inner) return { op: "lacks", path: inner.has[0], value: inner.has[1] };
    return null;
  }
  const c = comparison(p);
  return c ? { op: c.op, path: c.path, value: c.value } : null;
}

function fromLeaf({ op, path, value }: Leaf): Predicate {
  switch (op) {
    case "true":
      return path;
    case "false":
      return { not: path };
    case "set":
      return { exists: path };
    case "unset":
      return { not: { exists: path } };
    case "lacks":
      return { not: { has: [path, value] } };
    default:
      return compare(op, path, value);
  }
}

type GroupMode = "all" | "any" | "none" | "notall";

/** A predicate as a group of rows, if it is an and / or (possibly negated). */
function asGroup(p: Predicate): { mode: GroupMode; items: Predicate[] } | null {
  const k = kindOf(p);
  if (k === "and") return { mode: "all", items: children(p) };
  if (k === "or") return { mode: "any", items: children(p) };
  if (k === "not") {
    const inner = (p as { not: Predicate }).not;
    if (kindOf(inner) === "or") return { mode: "none", items: children(inner) };
    if (kindOf(inner) === "and") return { mode: "notall", items: children(inner) };
  }
  return null;
}

function fromGroup(mode: GroupMode, items: Predicate[]): Predicate {
  switch (mode) {
    case "all":
      return { and: items };
    case "any":
      return { or: items };
    case "none":
      return { not: { or: items } };
    case "notall":
      return { not: { and: items } };
  }
}

/** Edits a predicate; the result is IR predicate data, never an expression string. */
export function ConditionBuilder({ value, onChange }: { value: Predicate; onChange: (p: Predicate) => void }) {
  // The top level is always shown as a group, so adding a second test is one click
  const group = asGroup(value) ?? { mode: "all" as GroupMode, items: [value] };
  return (
    <div className="grid gap-2">
      <Group mode={group.mode} items={group.items} onChange={(mode, items) => onChange(items.length === 1 && mode === "all" ? items[0] : fromGroup(mode, items))} />
      <code className="block rounded-md bg-surface-2 px-2 py-1 font-mono text-[11px] leading-4 break-words text-muted-foreground">{describe(value)}</code>
    </div>
  );
}

function Group({ mode, items, onChange, onRemove }: { mode: GroupMode; items: Predicate[]; onChange: (mode: GroupMode, items: Predicate[]) => void; onRemove?: () => void }) {
  const set = (i: number, p: Predicate) => onChange(mode, items.map((x, j) => (j === i ? p : x)));
  const drop = (i: number) => onChange(mode, items.filter((_, j) => j !== i));
  return (
    <div className="grid gap-1.5">
      <div className="flex items-center gap-1">
        <Segmented
          label="Match"
          value={mode}
          onChange={(m) => onChange(m, items)}
          options={[
            { value: "all", label: "all" },
            { value: "any", label: "any" },
            { value: "none", label: "none" },
          ]}
        />
        <span className="text-xs text-muted-foreground">of</span>
        <span className="ml-auto flex">
          <IconButton icon={Plus} label="Add condition" onClick={() => onChange(mode, [...items, "user.exists"])} />
          <IconButton icon={ListPlus} label="Add group" onClick={() => onChange(mode, [...items, { or: ["user.exists"] }])} />
          {onRemove && <IconButton icon={X} label="Remove group" onClick={onRemove} />}
        </span>
      </div>
      <div className="relative grid gap-1.5 pl-3">
        <span aria-hidden className="absolute top-1 bottom-1 left-0 w-px bg-border" />
        {items.map((item, i) => {
          const leaf = asLeaf(item);
          const sub = asGroup(item);
          const remove = items.length > 1 ? () => drop(i) : undefined;
          if (leaf) return <Row key={i} leaf={leaf} onChange={(l) => set(i, fromLeaf(l))} onRemove={remove} />;
          if (sub) return <Group key={i} mode={sub.mode} items={sub.items} onChange={(m, xs) => set(i, fromGroup(m, xs))} onRemove={remove} />;
          // A shape the rows cannot show (e.g. not of a comparison): shown as text, removable
          return (
            <div key={i} className="flex items-center gap-1">
              <code className="flex-1 font-mono text-xs">{describe(item)}</code>
              {remove && <IconButton icon={X} label="Remove condition" onClick={remove} />}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Row({ leaf, onChange, onRemove }: { leaf: Leaf; onChange: (l: Leaf) => void; onRemove?: () => void }) {
  const unary = leaf.op === "true" || leaf.op === "false" || leaf.op === "set" || leaf.op === "unset";
  const numeric = leaf.op === "gt" || leaf.op === "lt";
  return (
    <div className="grid grid-cols-[1fr_auto] items-start gap-1">
      <div className="grid grid-cols-[auto_1fr] items-center gap-1">
        <div className="col-span-2">
          <PathInput value={leaf.path} onChange={(path) => onChange({ ...leaf, path })} label="Path" />
        </div>
        <Select
          aria-label="Operator"
          value={leaf.op}
          className="w-[112px] text-xs"
          onChange={(e) => {
            const op = e.target.value as LeafOp;
            onChange({ ...leaf, op, value: op === "gt" || op === "lt" ? Number(leaf.value) || 0 : leaf.value });
          }}
        >
          {LEAF_OPS.map((o) => (
            <option key={o.op} value={o.op}>
              {o.label}
            </option>
          ))}
        </Select>
        {!unary && (
          <div>
            <ValueInput value={leaf.value} numeric={numeric} path={leaf.path} onChange={(value) => onChange({ ...leaf, value })} />
          </div>
        )}
      </div>
      {onRemove ? <IconButton icon={X} label="Remove condition" onClick={onRemove} /> : <span className="size-7" />}
    </div>
  );
}

/** A path in the flow environment, with the common ones suggested. */
export function PathInput({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const list = useId();
  const vars = useContext(FlowVars);
  return (
    <>
      <input
        aria-label={label}
        aria-invalid={!PATH.test(value)}
        spellCheck={false}
        title={value}
        className={cn(field, "w-full font-mono text-xs [&::-webkit-calendar-picker-indicator]:hidden")}
        list={list}
        value={value}
        placeholder="user.has_totp"
        onChange={(e) => onChange(e.target.value.trim())}
      />
      <datalist id={list}>
        {[...vars.keys()].map((name) => (
          <option key={name} value={`vars.${name}`}>set in this flow</option>
        ))}
        {KNOWN_PATHS.map((p) => (
          <option key={p.path} value={p.path}>{`${p.type} · ${p.about}`}</option>
        ))}
      </datalist>
    </>
  );
}

/** A literal: text, a number, or true / false (typed as such). */
function ValueInput({ value, onChange, numeric, path }: { value: Literal; onChange: (v: Literal) => void; numeric: boolean; path: string }) {
  const list = useId();
  const vars = useContext(FlowVars);
  const suggestions = KNOWN_VALUES[path] ?? (path.startsWith("vars.") ? (vars.get(path.slice(5)) ?? []) : []);
  if (typeof value === "boolean") {
    return (
      <Segmented
        label="Value"
        value={String(value)}
        onChange={(v) => onChange(v === "true")}
        options={[
          { value: "true", label: "true" },
          { value: "false", label: "false" },
        ]}
      />
    );
  }
  return (
    <>
      <input
        aria-label="Value"
        className={cn(field, "w-full font-mono text-xs")}
        type={numeric ? "number" : "text"}
        list={list}
        spellCheck={false}
        value={String(value)}
        onChange={(e) => {
          const t = e.target.value;
          onChange(numeric ? Number(t) : t === "true" || t === "false" ? t === "true" : t);
        }}
      />
      <datalist id={list}>
        {suggestions.map((v) => (
          <option key={v} value={v} />
        ))}
      </datalist>
    </>
  );
}
