"use client";

import { cn } from "@faye/ui/lib/utils";
import { ArrowDown, ArrowUp, CircleX, Plus, Trash, TriangleAlert, X } from "lucide-react";
import { type ReactNode, useState } from "react";
import type { Catalog } from "../../../lib/api";
import {
  addressKey,
  type Body,
  type Entry,
  entryAt,
  type FlowDoc,
  ID,
  KEY,
  type Literal,
  listOf,
  move,
  parentOf,
  parseAddress,
  remove,
  rename,
  stepIds,
  updateBody,
} from "../../../lib/flow";
import { kindOf } from "../../../lib/kinds";
import { Input } from "@faye/ui/components/input";
import { Choice, denseMono, IconButton, Overline, Prop, Segmented, Switch, Tile } from "../../ui";
import { ConditionBuilder, FlowVars } from "./condition";

const OPTION_LABELS: Record<string, string> = { passkey: "Passkey autofill", register: "Create passkey" };

/** Edits the selected step or option: every key a body can have, as a property panel. */
export function Inspector({
  doc,
  selected,
  onEdit,
  onSelect,
  onDeleted,
  catalog,
  findings,
}: {
  doc: FlowDoc;
  selected: string;
  onEdit: (doc: FlowDoc) => void;
  onSelect: (key: string | null) => void;
  onDeleted: (id: string) => void;
  catalog: Catalog | null;
  findings: { errors: string[]; warnings: string[] } | undefined;
}) {
  const address = parseAddress(selected);
  const entry = entryAt(doc, address);
  if (!entry) return null;
  const b = entry.body;
  const { list, index } = parentOf(address);
  const siblings = listOf(doc, list) ?? [];
  const patch = (p: Partial<Body>) => onEdit(updateBody(doc, address, p));
  const isStep = list.kind === "steps";
  const screenOptions = catalog?.screens.find((s) => s.type === b.screen?.type)?.options ?? [];
  const look = kindOf(b);

  const moveBy = (delta: number) => {
    const to = index + delta;
    if (to < 0 || to >= siblings.length) return;
    onEdit(move(doc, address, list, delta > 0 ? to + 1 : to));
    onSelect(addressKey({ section: address.section, path: [...list.owner, [list.kind, to]] }));
  };

  return (
    <div className="grid" aria-label="Inspector">
      <header className="sticky top-0 z-10 flex h-12 items-center gap-2 border-b bg-background px-3">
        <Tile icon={look.icon} category={look.category} />
        <IdField
          key={selected + entry.id}
          id={entry.id}
          label={isStep ? "Step id" : "Option id"}
          taken={(id) => (isStep ? stepIds(doc).has(id) : siblings.some((e) => e.id === id))}
          onRename={(id) => onEdit(rename(doc, address, id))}
        />
        <span className="ml-auto flex">
          <IconButton icon={ArrowUp} label="Move up" disabled={index === 0} onClick={() => moveBy(-1)} />
          <IconButton icon={ArrowDown} label="Move down" disabled={index === siblings.length - 1} onClick={() => moveBy(1)} />
          <IconButton
            icon={Trash}
            label="Delete"
            className="hover:text-danger"
            onClick={() => {
              onEdit(remove(doc, address));
              onSelect(null);
              onDeleted(entry.id);
            }}
          />
        </span>
      </header>

      <Group title="Condition">
        <Prop label="Only if">
          <Switch label="Has a condition" checked={b.if !== undefined} onChange={(on) => patch({ if: on ? "user.exists" : undefined })} />
          <span className="text-xs text-muted-foreground">{b.if === undefined ? "always" : isStep ? "else skipped" : "else not offered"}</span>
        </Prop>
        {b.if !== undefined && (
          <FlowVars.Provider value={flowVars(doc)}>
            <ConditionBuilder value={b.if} onChange={(p) => patch({ if: p })} />
          </FlowVars.Provider>
        )}
      </Group>

      <Group title="Interaction">
        <Prop label="Screen">
          <Choice
            label="Screen"
            className="flex-1"
            none="none"
            value={b.screen?.type ?? ""}
            options={catalog?.screens.map((s) => ({ value: s.type })) ?? []}
            onChange={(v) => patch({ screen: v ? { type: v, opts: {} } : undefined })}
          />
        </Prop>
        {b.screen &&
          screenOptions.map((opt) => (
            <Prop key={opt} label={OPTION_LABELS[opt] ?? opt}>
              {opt === "code" ? (
                <Input
                  aria-label={opt}
                  className={cn(denseMono, "flex-1")}
                  value={String(b.screen!.opts[opt] ?? "")}
                  onChange={(e) => patch({ screen: { ...b.screen!, opts: withOpt(b.screen!.opts, opt, e.target.value || undefined) } })}
                />
              ) : (
                <Switch
                  label={OPTION_LABELS[opt] ?? opt}
                  checked={b.screen!.opts[opt] === true}
                  onChange={(on) => patch({ screen: { ...b.screen!, opts: withOpt(b.screen!.opts, opt, on ? true : undefined) } })}
                />
              )}
            </Prop>
          ))}
        <Prop label="Handler">
          <Choice
            label="Handler"
            className="flex-1"
            none="none"
            value={b.use ?? ""}
            options={catalog?.handlers.map((h) => ({ value: h.name })) ?? []}
            onChange={(v) => patch({ use: v || undefined })}
          />
        </Prop>
        {b.use && (
          <Prop label="Proves">
            <span className="font-mono text-xs text-muted-foreground">
              {catalog?.handlers.find((h) => h.name === b.use)?.amr.filter((a) => a !== "user").join(" · ") || "no credential"}
            </span>
          </Prop>
        )}
        <Prop label="Attempts">
          <Switch label="Retry" checked={b.retry !== undefined} onChange={(on) => patch({ retry: on ? 3 : undefined })} />
          {b.retry !== undefined && (
            <Input
              aria-label="Attempts"
              type="number"
              min={1}
              max={10}
              className={cn(denseMono, "w-16")}
              value={b.retry}
              onChange={(e) => patch({ retry: Math.min(10, Math.max(1, Number(e.target.value) || 1)) })}
            />
          )}
        </Prop>
      </Group>

      <Group title="Logic">
        <Prop label="Hook">
          <Choice
            label="Hook"
            className="flex-1"
            none="none"
            value={b.hook ?? ""}
            options={[...(b.hook && !doc.hooks.some((h) => h.id === b.hook) ? [{ value: b.hook }] : []), ...doc.hooks.map((h) => ({ value: h.id }))]}
            onChange={(v) => patch({ hook: v || undefined })}
          />
        </Prop>
        <Prop label="Options">
          <Switch label="Has options" checked={b.alternative !== undefined} onChange={(on) => patch({ alternative: on ? [] : undefined, pick: on ? b.pick : undefined })} />
          {b.alternative !== undefined && (
            <Segmented
              label="Picked by"
              value={b.pick ? "hook" : "person"}
              onChange={(v) => patch({ pick: v === "hook" ? (doc.hooks[0]?.id ?? "") : undefined })}
              options={[
                { value: "person", label: "person" },
                { value: "hook", label: "hook" },
              ]}
            />
          )}
        </Prop>
        {b.pick !== undefined && (
          <Prop label="Picker">
            <Choice
              label="Picking hook"
              className="flex-1"
              none={b.pick === "" ? "(choose)" : undefined}
              value={b.pick}
              options={[...(b.pick && !doc.hooks.some((h) => h.id === b.pick) ? [{ value: b.pick }] : []), ...doc.hooks.map((h) => ({ value: h.id }))]}
              onChange={(v) => patch({ pick: v })}
            />
          </Prop>
        )}
        <Prop label="Nested steps">
          <Switch label="Has nested steps" checked={b.steps !== undefined} onChange={(on) => patch({ steps: on ? [] : undefined })} />
        </Prop>
        <Prop label="Set vars" align="start">
          <SetVars value={b.set} onChange={(set) => patch({ set })} />
        </Prop>
        <Prop label="Fail">
          <Switch label="Fails" checked={b.fail !== undefined} onChange={(on) => patch({ fail: on ? "denied" : undefined })} />
          {b.fail !== undefined && (
            <Input aria-label="Failure reason" aria-invalid={!ID.test(b.fail)} className={cn(denseMono, "flex-1")} value={b.fail} onChange={(e) => patch({ fail: e.target.value })} />
          )}
        </Prop>
      </Group>
      {findings && (findings.errors.length > 0 || findings.warnings.length > 0) && (
        <ul className="grid gap-px border-b" aria-label="Problems in this block">
          {findings.errors.map((m, i) => (
            <li key={`e${i}`} className="flex gap-2 bg-danger/8 px-3 py-2 text-xs">
              <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
              {m}
            </li>
          ))}
          {findings.warnings.map((m, i) => (
            <li key={`w${i}`} className="flex gap-2 bg-warning/10 px-3 py-2 text-xs">
              <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warning-text" />
              {m}
            </li>
          ))}
        </ul>
      )}

    </div>
  );
}

/** vars names the flow's set blocks write, with the values they write. */
function flowVars(doc: FlowDoc): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const walk = (list: Entry[] | undefined) =>
    (list ?? []).forEach((e) => {
      for (const [k, v] of e.body.set ?? []) out.set(k, [...new Set([...(out.get(k) ?? []), String(v)])]);
      walk(e.body.alternative);
      walk(e.body.steps);
    });
  walk(doc.login);
  walk(doc.consent);
  return out;
}

function withOpt(opts: Record<string, Literal>, key: string, value: Literal | undefined): Record<string, Literal> {
  const next = { ...opts };
  if (value === undefined) delete next[key];
  else next[key] = value;
  return next;
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid gap-2.5 border-b px-3 pt-3 pb-4">
      <Overline>{title}</Overline>
      {children}
    </section>
  );
}

function IdField({ id, label, taken, onRename }: { id: string; label: string; taken: (id: string) => boolean; onRename: (id: string) => void }) {
  const [value, setValue] = useState(id);
  const problem = !ID.test(value) ? "a-z, 0-9, - and _" : value !== id && taken(value) ? "in use" : null;
  const commit = () => {
    if (!problem && value !== id) onRename(value);
  };
  return (
    <span className="relative min-w-0 flex-1">
      <Input
        aria-label={label}
        aria-invalid={problem !== null}
        title={problem ?? label}
        spellCheck={false}
        className="h-7 w-full border-transparent bg-transparent px-1.5 font-mono text-[13px] font-medium shadow-none hover:border-input md:text-[13px] dark:bg-transparent"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === "Enter" && commit()}
      />
      {problem && <span className="absolute top-full left-1.5 text-[11px] text-danger">{problem}</span>}
    </span>
  );
}

function SetVars({ value, onChange }: { value: [string, Literal][] | undefined; onChange: (v: [string, Literal][] | undefined) => void }) {
  const rows = value ?? [];
  const update = (i: number, row: [string, Literal]) => onChange(rows.map((r, j) => (j === i ? row : r)));
  return (
    <div className="grid w-full gap-1">
      {rows.map(([name, v], i) => (
        <div key={i} className="flex items-center gap-1">
          <Input aria-label="Name" aria-invalid={!KEY.test(name)} className={cn(denseMono, "w-24")} value={name} onChange={(e) => update(i, [e.target.value, v])} />
          <span className="text-muted-foreground">=</span>
          <Input
            aria-label="Value"
            className={cn(denseMono, "w-20 flex-1")}
            value={String(v)}
            onChange={(e) => {
              const t = e.target.value;
              update(i, [name, t === "true" ? true : t === "false" ? false : /^-?\d+$/.test(t) ? Number(t) : t]);
            }}
          />
          <IconButton icon={X} label="Remove var" onClick={() => onChange(rows.length > 1 ? rows.filter((_, j) => j !== i) : undefined)} />
        </div>
      ))}
      <button type="button" onClick={() => onChange([...rows, ["name", "value"]])} className="flex h-7 items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
        <Plus className="size-3.5" />
        Add var
      </button>
    </div>
  );
}
