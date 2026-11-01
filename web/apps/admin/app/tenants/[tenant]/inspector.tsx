"use client";

import { cn } from "@faye/ui/lib/utils";
import { ArrowDown, ArrowRight, ArrowUp, AtSign, CircleX, KeyRound, Plus, Trash, TriangleAlert, X } from "lucide-react";
import { type ReactNode, useState } from "react";
import {
  type Address,
  addressKey,
  blockOf,
  type Body,
  type Defaults,
  type Entry,
  entryAt,
  type FlowDoc,
  ID,
  KEY,
  type ListKind,
  type Literal,
  listOf,
  misplaced,
  move,
  parentOf,
  parseAddress,
  PASSWORD_VIEW,
  removeEntry,
  rename,
  setting,
  stepIds,
  updateBody,
  withSetting,
} from "../../../lib/flow";
import { AUTHENTICATE } from "../../../lib/entries";
import { kindOf } from "../../../lib/kinds";
import { Input } from "@faye/ui/components/input";
import { ToggleGroup, ToggleGroupItem } from "@faye/ui/components/toggle-group";
import { Choice, denseMono, Help, IconButton, Overline, Prop, Segmented, Switch, Tile } from "../../ui";
import { ConditionBuilder, FlowVars } from "./condition";

/** Edits the selected step or option: only the keys its kind of block takes (ADR 0030). */
export function Inspector({
  doc,
  selected,
  onEdit,
  onSelect,
  onDeleted,
  findings,
  defaults,
}: {
  doc: FlowDoc;
  selected: string;
  onEdit: (doc: FlowDoc) => void;
  onSelect: (key: string | null) => void;
  onDeleted: (id: string) => void;
  findings: { errors: string[]; warnings: string[] } | undefined;
  /** The tenant's defaults for its blocks' settings (General settings). */
  defaults: Defaults;
}) {
  const address = parseAddress(selected);
  const entry = entryAt(doc, address);
  if (!entry) return null;
  const b = entry.body;
  const { list, index } = parentOf(address);
  const siblings = listOf(doc, list) ?? [];
  const patch = (p: Partial<Body>) => onEdit(updateBody(doc, address, p));
  const isStep = list.kind === "steps";
  const look = kindOf(b);
  const kind = blockOf(b);
  const role = kind?.role;
  const withPassword = role === "identifier" && setting(b, PASSWORD_VIEW, defaults) === true;
  // Keys hand written YAML may give a block that does not take them, with how to clear each
  const logic = role === "choose" || role === "group" || role === "set" || role === "fail";
  const stray = (
    [
      ["if", role === "identifier" && b.if !== undefined, "the Identifier always runs", { if: undefined }],
      ["attempts", role === "identifier" && !withPassword && b.with?.attempts !== undefined, "only with the password", { with: withSetting(b, "attempts", undefined, defaults) }],
      ["after", role === "fail" && b.after !== undefined, "nothing runs after a fail", { after: undefined }],
      ["pick", role !== "choose" && b.pick !== undefined, "only a choice picks", { pick: undefined }],
      ["path", logic && b.path !== undefined, "it shows no screen", { path: undefined }],
      ["set", kind?.id !== undefined && b.set !== undefined, "a Set step does that", { set: undefined }],
      ["steps", kind?.id !== undefined && b.steps !== undefined, "a Group holds steps", { steps: undefined }],
      ["alternative", kind?.id !== undefined && b.alternative !== undefined, "a Choose offers options", { alternative: undefined, pick: undefined }],
      ["fail", kind?.id !== undefined && b.fail !== undefined, "a Fail step does that", { fail: undefined }],
      ...Object.keys(b.with ?? {})
        .filter((k) => kind?.settings?.[k] === undefined)
        .map((k) => [`with.${k}`, true, `the ${kind?.name ?? "block"} block has no such setting`, { with: withSetting(b, k, undefined, defaults) }]),
    ] as [string, boolean, string, Partial<Body>][]
  )
    .filter(([, on]) => on)
    .map(([key, , why, clear]) => [key, why, clear] as const);

  /** The document after moving by `delta`, or null where the block cannot go (ADR 0030). */
  const moved = (delta: number): FlowDoc | null => {
    const to = index + delta;
    if (to < 0 || to >= siblings.length) return null;
    const next = move(doc, address, list, delta > 0 ? to + 1 : to);
    return misplaced(next) > misplaced(doc) ? null : next;
  };
  // Deleting must not leave the flow without its Identifier first (ADR 0030)
  const deletable = misplaced(removeEntry(doc, address)) <= misplaced(doc);
  const moveBy = (delta: number) => {
    const next = moved(delta);
    if (!next) return;
    onEdit(next);
    onSelect(addressKey({ section: address.section, path: [...list.owner, [list.kind, index + delta]] }));
  };

  return (
    <div className="grid" aria-label="Inspector">
      <header className="sticky top-0 z-10 flex h-12 items-center gap-2 border-b bg-background px-3">
        <Tile icon={look.icon} tone={look.tone} />
        <IdField
          key={selected + entry.id}
          id={entry.id}
          label={isStep ? "Step id" : "Option id"}
          taken={(id) => (isStep ? stepIds(doc).has(id) : siblings.some((e) => e.id === id))}
          onRename={(id) => onEdit(rename(doc, address, id))}
        />
        <span className="ml-auto flex">
          <IconButton icon={ArrowUp} label={moved(-1) || index === 0 ? "Move up" : "Move up: the block cannot go there"} disabled={!moved(-1)} onClick={() => moveBy(-1)} />
          <IconButton icon={ArrowDown} label={moved(1) || index === siblings.length - 1 ? "Move down" : "Move down: the block cannot go there"} disabled={!moved(1)} onClick={() => moveBy(1)} />
          <IconButton
            icon={Trash}
            label={deletable ? "Delete" : "The flow always starts with the Identifier"}
            disabled={!deletable}
            className="transition-colors hover:text-danger"
            onClick={() => {
              onEdit(removeEntry(doc, address));
              onSelect(null);
              onDeleted(entry.id);
            }}
          />
        </span>
      </header>

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

      {role === "identifier" ? null : (
        <Group title="Condition" help="Run this block only when the predicate holds. A step whose condition fails is skipped; an option whose condition fails is not offered.">
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
      )}

      {role === "identifier" && (
        <Group title="Identifier" help="Always the first step, and always runs. Ask for the identifier first and the password on its own screen, or both on one screen; with the password on it, the screen offers no passkey autofill.">
          <Prop label="View" align="start">
            <ViewPicker
              password={withPassword}
              onChange={(password) => {
                // The password view takes no other option, so passkey autofill goes with it; and the
                // identifier alone never fails, so its attempts go when the password does
                const next = { ...b, with: withSetting(b, password ? "passkey" : "attempts", undefined, defaults) };
                patch({ with: withSetting(next, PASSWORD_VIEW, password, defaults) });
              }}
            />
          </Prop>
          <Prop label="Passkey autofill">
            <Switch label="Passkey autofill" disabled={withPassword} checked={!withPassword && setting(b, "passkey", defaults) === true} onChange={(on) => patch({ with: withSetting(b, "passkey", on, defaults) })} />
            {withPassword && <span className="text-xs text-muted-foreground">not with the password</span>}
          </Prop>
          {withPassword && <Attempts body={b} defaults={defaults} onChange={(w) => patch({ with: w })} />}
          <PathField value={b.path} onChange={(path) => patch({ path })} />
          {withPassword &&
            passwordSteps(doc).map((a) => (
              <p key={addressKey(a)} className="flex items-center gap-2 rounded-md bg-warning/10 px-2 py-1.5 text-xs">
                <TriangleAlert className="size-3.5 shrink-0 text-warning-text" />
                <span className="flex-1">
                  <span className="font-mono">{entryAt(doc, a)?.id}</span> asks for the password again
                </span>
                <button type="button" className="font-medium underline-offset-2 hover:underline" onClick={() => onEdit(removeEntry(doc, a))}>
                  Remove
                </button>
              </p>
            ))}
        </Group>
      )}

      {kind?.id !== undefined && role !== "identifier" && (
        <Group title={kind.name} help={kind.description}>
          {setting(b, "attempts", defaults) !== undefined && <Attempts body={b} defaults={defaults} onChange={(w) => patch({ with: w })} />}
          <PathField value={b.path} onChange={(path) => patch({ path })} />
        </Group>
      )}

      {role === "choose" && (
        <Group title="Choice" help="Offers its options: credential blocks, or Fail. The person picks among the ones whose condition holds, or a hook picks for them; with one enabled, it runs without asking.">
          <Prop label="Picked by">
            <Segmented
              label="Picked by"
              value={b.pick !== undefined ? "hook" : "person"}
              onChange={(v) => {
                if (v === "hook" && doc.hooks.length === 0) return;
                patch({ pick: v === "hook" ? doc.hooks[0].id : undefined });
              }}
              options={[
                { value: "person", label: "person" },
                { value: "hook", label: "hook" },
              ]}
            />
          </Prop>
          {b.pick !== undefined && (
            <Prop label="Picker">
              <Choice label="Picking hook" className="flex-1" none={b.pick === "" ? "(choose)" : undefined} value={b.pick} options={hookChoices(doc, b.pick)} onChange={(v) => patch({ pick: v })} />
            </Prop>
          )}
          {doc.hooks.length === 0 && b.pick === undefined && <p className="text-xs text-muted-foreground">To let a hook pick, declare one under Hooks first.</p>}
          {(b.alternative?.length ?? 0) < 2 && (
            <p className="text-xs text-muted-foreground">Add two options or more under it; with one, use that block as the step.</p>
          )}
        </Group>
      )}

      {role === "set" && (
        <Group title="Set" help="Writes literals into vars, which later conditions read.">
          <Prop label="Vars" align="start">
            <SetVars value={b.set} onChange={(set) => patch({ set })} />
          </Prop>
        </Group>
      )}

      {role === "fail" && (
        <Group title="Fail" help="Ends the sign in, refused with this reason.">
          <Prop label="Reason">
            <Input aria-label="Failure reason" aria-invalid={!ID.test(b.fail ?? "")} className={cn(denseMono, "flex-1")} value={b.fail ?? ""} onChange={(e) => patch({ fail: e.target.value })} />
          </Prop>
        </Group>
      )}

      {role === "group" && (
        <Group title="Group" help="Runs its steps in order; its condition covers them all.">
          <p className="text-xs text-muted-foreground">Add steps under it in the tree.</p>
        </Group>
      )}

      {stray.length > 0 && (
        <Group title="Not used by this block" help="Keys this kind of block does not take, from the YAML; the server refuses the flow until they go.">
          {stray.map(([key, why, clear]) => (
            <Prop key={key} label={key}>
              <span className="flex-1 text-xs text-muted-foreground">{why}</span>
              <button type="button" className="text-xs font-medium underline-offset-2 hover:underline" onClick={() => patch(clear)}>
                Remove
              </button>
            </Prop>
          ))}
        </Group>
      )}

      <Group title="Hooks" help="A hook may set vars or fail; it can never sign anyone in. Before runs once before the block, around its attempts; after runs once the block has run.">
        <Prop label="Before">
          <Choice label="Hook before" className="flex-1" none="none" value={b.before ?? ""} options={hookChoices(doc, b.before)} onChange={(v) => patch({ before: v || undefined })} />
        </Prop>
        {role !== "fail" && (
          <Prop label="After">
            <Choice label="Hook after" className="flex-1" none="none" value={b.after ?? ""} options={hookChoices(doc, b.after)} onChange={(v) => patch({ after: v || undefined })} />
          </Prop>
        )}
      </Group>
    </div>
  );
}

/** The flow's hooks, and a named one it does not declare, so the select shows it. */
function hookChoices(doc: FlowDoc, current: string | undefined) {
  return [...(current && !doc.hooks.some((h) => h.id === current) ? [{ value: current }] : []), ...doc.hooks.map((h) => ({ value: h.id }))];
}

/** Where Password blocks are, which the identifier's password view replaces. */
function passwordSteps(doc: FlowDoc): Address[] {
  const out: Address[] = [];
  const walk = (list: Entry[] | undefined, path: [ListKind, number][], kind: ListKind) =>
    (list ?? []).forEach((e, i) => {
      const here: [ListKind, number][] = [...path, [kind, i]];
      if (blockOf(e.body)?.role === "password") out.push({ section: AUTHENTICATE, path: here });
      walk(e.body.steps, here, "steps");
      walk(e.body.alternative, here, "alternative");
    });
  walk(doc.login, [], "steps");
  return out;
}

/** How many times the step may be answered before it fails; the block's default unless changed. */
function Attempts({ body, defaults, onChange }: { body: Body; defaults: Defaults; onChange: (w: Record<string, Literal> | undefined) => void }) {
  const value = String(setting(body, "attempts", defaults) ?? 1);
  // Typed freely, committed on blur or Enter, so a cleared field is not read as 1
  const [text, setText] = useState(value);
  const [shown, setShown] = useState(value);
  if (shown !== value) {
    setShown(value);
    setText(value);
  }
  const commit = () => {
    if (text === value) return;
    const n = Number(text);
    if (Number.isInteger(n) && n >= 1) onChange(withSetting(body, "attempts", n, defaults));
    else setText(value);
  };
  return (
    <Prop label="Attempts">
      <Input
        aria-label="Attempts"
        type="number"
        min={1}
        className={cn(denseMono, "w-16")}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === "Enter" && commit()}
      />
      {body.with?.attempts === undefined && (
        <span className="text-xs text-muted-foreground">{blockOf(body)?.id !== undefined && defaults[blockOf(body)!.id!]?.attempts !== undefined ? "tenant default" : "default"}</span>
      )}
    </Prop>
  );
}

function PathField({ value, onChange }: { value: string | undefined; onChange: (v: string | undefined) => void }) {
  return (
    <Prop label="Path">
      <Input aria-label="Path" className={cn(denseMono, "flex-1")} placeholder="default" value={value ?? ""} onChange={(e) => onChange(e.target.value || undefined)} />
    </Prop>
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
  return out;
}

/** A login screen in miniature: its fields (identifier, password) above its button. */
function MiniScreen({ fields }: { fields: ("identifier" | "password")[] }) {
  return (
    <span className="grid w-10 gap-0.75 rounded-xs border bg-background p-1">
      {fields.map((f) => {
        const Icon = f === "identifier" ? AtSign : KeyRound;
        return (
          <span key={f} className="flex h-2.5 items-center rounded-xs border px-0.5">
            <Icon className="size-1.5 text-muted-foreground" />
          </span>
        );
      })}
      <span className="h-1.5 rounded-xs bg-foreground/60" />
    </span>
  );
}

/** The Identifier's two views, each drawn as the screens a person goes through. */
function ViewPicker({ password, onChange }: { password: boolean; onChange: (password: boolean) => void }) {
  const views = [
    {
      value: "first",
      label: "Identifier first",
      diagram: (
        <>
          <MiniScreen fields={["identifier"]} />
          <ArrowRight className="size-3 text-muted-foreground" />
          <MiniScreen fields={["password"]} />
        </>
      ),
    },
    { value: "password", label: "With password", diagram: <MiniScreen fields={["identifier", "password"]} /> },
  ];
  return (
    <ToggleGroup
      type="single"
      spacing={2}
      aria-label="View"
      value={password ? "password" : "first"}
      onValueChange={(v) => v && onChange(v === "password")}
      className="grid w-full grid-cols-2"
    >
      {views.map((v) => (
        <ToggleGroupItem
          key={v.value}
          value={v.value}
          className="h-auto min-w-0 flex-col gap-1.5 border px-1.5 py-2 text-xs font-normal data-[state=on]:border-accent-ui data-[state=on]:bg-accent-ui/5 data-[state=on]:font-medium data-[state=on]:text-foreground"
        >
          <span className="flex h-10 items-center gap-1" aria-hidden>
            {v.diagram}
          </span>
          <span className="truncate">{v.label}</span>
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  );
}

function Group({ title, help, children }: { title: string; help?: string; children: ReactNode }) {
  return (
    <section className="grid gap-2.5 border-b px-3 pt-3 pb-4">
      <div className="flex items-center gap-1">
        <Overline>{title}</Overline>
        {help && <Help>{help}</Help>}
      </div>
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
        className="h-7 w-full border-transparent bg-transparent px-1.5 font-mono text-sm font-medium shadow-none hover:border-input dark:bg-transparent"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === "Enter" && commit()}
      />
      {problem && <span className="absolute top-full left-1.5 text-xs text-danger">{problem}</span>}
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
      <button type="button" onClick={() => onChange([...rows, ["name", "value"]])} className="flex h-7 items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground">
        <Plus className="size-3.5" />
        Add var
      </button>
    </div>
  );
}
