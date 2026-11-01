"use client";

import { cn } from "@faye/ui/lib/utils";
import { CircleX, GripVertical, Plus, TriangleAlert } from "lucide-react";
import { type DragEvent, type KeyboardEvent, type ReactNode, useEffect, useRef, useState } from "react";
import {
  type Address,
  addressKey,
  block,
  consentStart,
  type Entry,
  entryAt,
  type FlowDoc,
  fromBlock,
  inside,
  insert,
  type ListKind,
  type ListRef,
  listKey,
  listOf,
  misplaced,
  move,
  parentOf,
  parseAddress,
  parseListKey,
  removeEntry,
  type Section,
} from "../../../lib/flow";
import { AUTHENTICATE, CONSENT, ENTRIES } from "../../../lib/entries";
import { kindOf } from "../../../lib/kinds";
import { describe } from "../../../lib/predicate";
import { Chip, Tile } from "../../ui";

/** What is being dragged: a palette block, or an entry already in the tree. */
type Dragging = { palette: string } | { entry: string } | null;

export type Findings = Map<string, { errors: string[]; warnings: string[] }>;

/** Selection key of a list (a section's steps, a choose's options, a group's steps). */
export const LIST = "list:";

/**
 * Where the palette's Add puts a block: at the end of a selected list (click "one of" under a
 * choose, or a section title), after a selected block, else at the end of the sign in steps.
 * The end of a section's steps is before its consent steps, unless the block asks for consent:
 * appended after them, it would run after sign in, on the consent challenge.
 */
export function addTarget(doc: FlowDoc, selected: string | null, consent = false): { list: ListRef; index: number } {
  const end = (list: ListRef) => {
    const entries = listOf(doc, list) ?? [];
    return list.owner.length === 0 && !consent ? consentStart(entries) : entries.length;
  };
  if (selected?.startsWith(LIST)) {
    const list = parseListKey(selected.slice(LIST.length));
    return { list, index: end(list) };
  }
  const a = selected ? parseAddress(selected) : null;
  if (!a || a.path.length === 0) {
    const list: ListRef = { section: a?.section ?? AUTHENTICATE, owner: [], kind: "steps" };
    return { list, index: end(list) };
  }
  const [kind, index] = a.path[a.path.length - 1];
  return { list: { section: a.section, owner: a.path.slice(0, -1), kind }, index: index + 1 };
}

export function useDragging() {
  return useState<Dragging>(null);
}

/** The flow as a nested list with rails: sections, steps, and the options of each choose. */
export function Tree({
  doc,
  onEdit,
  selected,
  onSelect,
  findings,
  visited,
  visitKeys,
  waiting = null,
  dragging,
  setDragging,
}: {
  doc: FlowDoc;
  onEdit: (doc: FlowDoc) => void;
  selected: string | null;
  onSelect: (key: string | null) => void;
  findings: Findings;
  /** Visit keys of the path selected in the Paths tab, or of the preview run. */
  visited: Set<string> | null;
  visitKeys: Map<string, string>;
  /** Visit key of the block a preview run is waiting on. */
  waiting?: string | null;
  dragging: Dragging;
  setDragging: (d: Dragging) => void;
}) {
  /** The document after dropping here, or null where the block cannot go (ADR 0030). */
  const dropped = (list: ListRef, index: number): FlowDoc | null => {
    if (!dragging || ("entry" in dragging && inside(list, parseAddress(dragging.entry)))) return null;
    const next =
      "palette" in dragging
        ? insert(doc, list, index, fromBlock(block(dragging.palette), list.kind))
        : move(doc, parseAddress(dragging.entry), list, index);
    return misplaced(next) > misplaced(doc) ? null : next;
  };
  const drop = (list: ListRef, index: number) => {
    if (!dragging) return;
    const next = dropped(list, index);
    setDragging(null);
    if (!next) return;
    onEdit(next);
    onSelect("palette" in dragging ? addressKey({ section: list.section, path: [...list.owner, [list.kind, index]] }) : null);
  };
  // Only the positions a drop would keep compiling are drop targets, so a drop is never refused
  const canDrop = (list: ListRef, index: number) => dropped(list, index) !== null;

  // Keyboard: arrows select in tree order, cmd+arrows reorder, backspace deletes
  const order = [...visitKeys.keys()];
  const onKeyDown = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement).closest("input, select, textarea")) return;
    const at = selected ? order.indexOf(selected) : -1;
    if (selected?.startsWith(LIST) && e.key !== "Escape") return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const delta = e.key === "ArrowDown" ? 1 : -1;
      if ((e.metaKey || e.ctrlKey) && selected) {
        const a = parseAddress(selected);
        const { list, index } = parentOf(a);
        const to = index + delta;
        const size = listOf(doc, list)?.length ?? 0;
        if (to < 0 || to >= size) return;
        const next = move(doc, a, list, delta > 0 ? to + 1 : to);
        if (misplaced(next) > misplaced(doc)) return;
        onEdit(next);
        onSelect(addressKey({ section: a.section, path: [...list.owner, [list.kind, to]] }));
      } else {
        const next = order[Math.min(order.length - 1, Math.max(0, at + delta))];
        if (next) onSelect(next);
      }
    } else if ((e.key === "Backspace" || e.key === "Delete") && selected && !selected.startsWith(LIST)) {
      e.preventDefault();
      const a = parseAddress(selected);
      const next = removeEntry(doc, a);
      // The Identifier stays first: the Delete button says why
      if (misplaced(next) > misplaced(doc)) return;
      onEdit(next);
      onSelect(neighbour(next, a));
    } else if (e.key === "Escape") onSelect(null);
  };

  const ctx = { doc, selected, onSelect, findings, visited, visitKeys, waiting, dragging, setDragging, drop, canDrop };
  return (
    <div
      className="h-full min-h-0 overflow-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
      role="tree"
      aria-label={`Flow ${doc.flow}`}
      tabIndex={0}
      onKeyDown={onKeyDown}
      onClick={(e) => e.target === e.currentTarget && onSelect(null)}
    >
      <div className="mx-auto grid max-w-3xl gap-6 px-5 pt-3 pb-16" onClick={(e) => e.target === e.currentTarget && onSelect(null)}>
        <textarea
          aria-label="Flow description"
          placeholder="Describe this flow"
          spellCheck={false}
          rows={1}
          value={(doc.comment ?? "").split("\n").map((l) => l.replace(/^ /, "")).join("\n")}
          onChange={(e) => onEdit({ ...doc, comment: e.target.value ? e.target.value.split("\n").map((l) => (l ? ` ${l}` : "")).join("\n") : undefined })}
          className="field-sizing-content -mx-2 resize-none rounded-md border border-transparent bg-transparent px-2 py-1 text-sm leading-5 text-muted-foreground outline-none placeholder:text-muted-foreground hover:border-input focus:border-ring focus:text-foreground"
        />
        <SectionView section={AUTHENTICATE} title={ENTRIES[AUTHENTICATE].title} {...ctx} />
      </div>
    </div>
  );
}

type Ctx = {
  doc: FlowDoc;
  selected: string | null;
  onSelect: (key: string | null) => void;
  findings: Findings;
  visited: Set<string> | null;
  visitKeys: Map<string, string>;
  waiting: string | null;
  dragging: Dragging;
  setDragging: (d: Dragging) => void;
  drop: (list: ListRef, index: number) => void;
  canDrop: (list: ListRef, index: number) => boolean;
};

function SectionView({ section, title, ...ctx }: { section: Section; title: string } & Ctx) {
  const entries = ctx.doc[section] ?? [];
  return (
    <section>
      <header className="sticky top-0 z-10 flex h-9 items-center gap-2 bg-background">
        <ListLabel list={{ section, owner: [], kind: "steps" }} {...ctx}>
          <h2 className="text-base font-medium text-foreground">{title}</h2>
        </ListLabel>
        <span className="font-mono text-xs text-muted-foreground">{section}</span>
        <span className="text-xs text-muted-foreground">· {entries.length} {entries.length === 1 ? "step" : "steps"}</span>
      </header>
      <ListView list={{ section, owner: [], kind: "steps" }} entries={entries} depth={0} {...ctx} />
    </section>
  );
}

function ListView({ list, entries, depth, ...ctx }: { list: ListRef; entries: Entry[]; depth: number } & Ctx) {
  return (
    <ol role="group" aria-label={list.kind === "steps" ? "Steps" : "Options"}>
      {entries.map((e, i) => (
        <li key={`${listKey(list)}-${i}-${e.id}`} className="relative">
          {depth === 0 && list.kind === "steps" && i === consentStart(entries) && <ConsentDivider />}
          {depth > 0 && <span aria-hidden className="absolute top-4.5 -left-4 h-px w-3 bg-border" />}
          <DropZone list={list} index={i} {...ctx} />
          <EntryView address={{ section: list.section, path: [...list.owner, [list.kind, i]] }} entry={e} kind={list.kind} depth={depth} {...ctx} />
        </li>
      ))}
      <li className="relative">
        {entries.length === 0 ? (
          <EmptyDrop list={list} {...ctx} />
        ) : (
          <DropZone list={list} index={entries.length} {...ctx} />
        )}
      </li>
    </ol>
  );
}

/**
 * Marks where the sign in ends: the step that records consent and every step after it run on the
 * authorization server's consent challenge, once the sign in was accepted (ADR 0026).
 */
function ConsentDivider() {
  return (
    <div role="separator" aria-label={`${CONSENT.title}: runs after sign in`} className="flex items-center gap-2 pt-3 pb-1 text-xs text-muted-foreground">
      <span className="font-medium text-foreground">{CONSENT.title}</span>
      <span>runs after sign in, on the consent challenge</span>
      <span aria-hidden className="h-px flex-1 bg-border" />
    </div>
  );
}

function useDrop(list: ListRef, index: number, ctx: Ctx) {
  const [over, setOver] = useState(false);
  const allowed = ctx.canDrop(list, index);
  return {
    over,
    allowed,
    handlers: {
      onDragOver: (e: DragEvent) => {
        if (!allowed) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = ctx.dragging && "palette" in ctx.dragging ? "copy" : "move";
        setOver(true);
      },
      onDragLeave: () => setOver(false),
      onDrop: (e: DragEvent) => {
        e.preventDefault();
        setOver(false);
        ctx.drop(list, index);
      },
    },
  };
}

/** The gap between two rows: grows while dragging, shows an insertion line when hovered. */
function DropZone({ list, index, ...ctx }: { list: ListRef; index: number } & Ctx) {
  const { over, allowed, handlers } = useDrop(list, index, ctx);
  return (
    <div {...handlers} className={cn("relative transition-[height,margin] duration-150 motion-reduce:transition-none", ctx.dragging && allowed ? "-my-1 h-3" : "h-0.5")}>
      {over && (
        <span aria-hidden className="absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 bg-accent-ui">
          <span className="absolute -top-0.75 -left-1 size-2 rounded-full border-2 border-accent-ui bg-background" />
        </span>
      )}
    </div>
  );
}

function EmptyDrop({ list, ...ctx }: { list: ListRef } & Ctx) {
  const { over, handlers } = useDrop(list, 0, ctx);
  return (
    <div
      {...handlers}
      className={cn(
        "my-1 flex h-7 items-center rounded-md border border-dashed px-2 text-xs text-muted-foreground transition-colors",
        over && "border-accent-ui bg-accent-ui/8 text-accent-ui",
      )}
    >
      {list.kind === "steps" ? "No steps" : "No options"}
    </div>
  );
}

function EntryView({ address, entry, kind, depth, ...ctx }: { address: Address; entry: Entry; kind: ListKind; depth: number } & Ctx) {
  const key = addressKey(address);
  const found = ctx.findings.get(key);
  const isSelected = ctx.selected === key;
  const onPath = ctx.visited?.has(ctx.visitKeys.get(key) ?? "") ?? false;
  const isWaiting = ctx.waiting !== null && ctx.visitKeys.get(key) === ctx.waiting;
  const isDragged = ctx.dragging && "entry" in ctx.dragging && ctx.dragging.entry === key;
  const b = entry.body;
  const look = kindOf(b);
  const chipsTitle = [
    b.before !== undefined && `before ${b.before || "?"}`,
    b.set && `set ${b.set.map(([k]) => k).join(", ")}`,
    b.block,
    ...Object.entries(b.with ?? {}).map(([k, v]) => `${k} ${String(v)}`),
    b.fail !== undefined && `fail ${b.fail}`,
    b.after !== undefined && `after ${b.after || "?"}`,
  ]
    .filter(Boolean)
    .join(" · ");
  const row = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (isSelected) row.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [isSelected]);
  return (
    <div className={cn(ctx.visited && !onPath && "opacity-45", isDragged && "opacity-40")}>
      <div
        ref={row}
        role="treeitem"
        aria-selected={isSelected}
        aria-label={`${kind === "steps" ? "Step" : "Option"} ${entry.id}`}
        draggable
        onDragStart={(e) => {
          e.dataTransfer.setData("text/plain", key);
          e.dataTransfer.effectAllowed = "move";
          ctx.setDragging({ entry: key });
        }}
        onDragEnd={() => ctx.setDragging(null)}
        onClick={() => ctx.onSelect(key)}
        className={cn(
          "group relative grid cursor-default rounded-lg px-2 py-1.5 transition-[background-color,color,box-shadow]",
          isSelected ? "bg-accent-ui/10 ring-1 ring-accent-ui/25 ring-inset dark:bg-accent-ui/16" : "hover:bg-accent",
        )}
      >
        <GripVertical aria-hidden className="absolute top-2.5 -left-4 size-3.5 cursor-grab text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
        <div className="flex h-6 min-w-0 items-center gap-2">
          <span className={cn("rounded-md", onPath && "ring-2 ring-accent-ui/60 ring-offset-1 ring-offset-background", isWaiting && "ring-2 ring-offset-1 ring-offset-background animate-pulse")}>
            <Tile icon={look.icon} tone={look.tone} />
          </span>
          <span className="truncate font-mono text-sm font-medium" title={entry.id}>
            {entry.id}
          </span>
          {isWaiting && <Chip className="bg-accent-ui/10 text-accent-ui">waiting on screen</Chip>}
          {look.label.toLowerCase().replace(" ", "-") !== entry.id && <span className="shrink-[4] truncate text-xs text-muted-foreground">{look.label}</span>}
          <span title={chipsTitle || undefined} className="ml-auto flex min-w-0 items-center justify-end gap-1 overflow-hidden mask-[linear-gradient(90deg,transparent,black_--spacing(5))]">
            {b.before !== undefined && <Chip mono>before {b.before || "?"}</Chip>}
            {b.set && <Chip mono>set {b.set.map(([k]) => k).join(", ")}</Chip>}
            {Object.entries(b.with ?? {}).map(([k, v]) => (
              <Chip key={k} mono>
                {k === "attempts" ? `×${String(v)}` : v === true ? k : `${k} ${String(v)}`}
              </Chip>
            ))}
            {b.fail !== undefined && <Chip mono>fail {b.fail}</Chip>}
            {b.after !== undefined && <Chip mono>after {b.after || "?"}</Chip>}
            {found && found.errors.length > 0 && (
              <span className="flex items-center gap-1 pl-1 text-xs text-danger" title={found.errors.join("\n")}>
                <CircleX className="size-3.5" />
                {found.errors.length}
              </span>
            )}
            {found && found.warnings.length > 0 && (
              <span className="flex items-center gap-1 pl-1 text-xs text-warning-text" title={found.warnings.join("\n")}>
                <TriangleAlert className="size-3.5" />
                {found.warnings.length}
              </span>
            )}
          </span>
        </div>
        {b.if !== undefined && (
          <div className="truncate pl-8 font-mono text-xs leading-5 text-muted-foreground" title={describe(b.if)}>
            <span className="text-foreground">if</span> {describe(b.if)}
          </div>
        )}
      </div>
      {(b.alternative || b.steps) && (
        <div className="relative ml-4.75 pl-4">
          <span aria-hidden className={cn("absolute top-0 bottom-4 left-0 w-px", onPath ? "bg-accent-ui" : "bg-border")} />
          {b.alternative && (
            <>
              <ListLabel list={{ section: address.section, owner: address.path, kind: "alternative" }} {...ctx}>
                one of · {b.pick ? (
                  <>
                    hook <span className="mx-1 font-mono">{b.pick}</span> picks
                  </>
                ) : (
                  "person picks"
                )}
              </ListLabel>
              <ListView list={{ section: address.section, owner: address.path, kind: "alternative" }} entries={b.alternative} depth={depth + 1} {...ctx} />
            </>
          )}
          {b.steps && (
            <>
              <ListLabel list={{ section: address.section, owner: address.path, kind: "steps" }} {...ctx}>
                {b.alternative ? "then, in order" : "in order"}
              </ListLabel>
              <ListView list={{ section: address.section, owner: address.path, kind: "steps" }} entries={b.steps} depth={depth + 1} {...ctx} />
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * The label above a list. Selecting it makes the palette add to the end of that list, which is how
 * a block gets into a choose or group without dragging.
 */
function ListLabel({ list, children, ...ctx }: { list: ListRef; children: ReactNode } & Ctx) {
  const key = LIST + listKey(list);
  const isSelected = ctx.selected === key;
  return (
    <button
      type="button"
      onClick={() => ctx.onSelect(key)}
      title="Select to add blocks here"
      aria-pressed={isSelected}
      className={cn(
        "flex h-6 items-center gap-1 rounded-xs px-1 -mx-1 text-xs text-muted-foreground transition-colors hover:text-foreground",
        isSelected && "bg-accent-ui/10 text-accent-ui hover:text-accent-ui",
      )}
    >
      {children}
      {isSelected && <Plus className="size-3" />}
    </button>
  );
}

/** What to select after deleting the entry at `a`: whatever took its place, else the one before. */
function neighbour(doc: FlowDoc, a: Address): string | null {
  if (entryAt(doc, a)) return addressKey(a);
  const [kind, index] = a.path[a.path.length - 1];
  if (index > 0) return addressKey({ section: a.section, path: [...a.path.slice(0, -1), [kind, index - 1]] });
  return a.path.length > 1 ? addressKey({ section: a.section, path: a.path.slice(0, -1) }) : null;
}
