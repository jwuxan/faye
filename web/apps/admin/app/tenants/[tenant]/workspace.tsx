"use client";

import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Input } from "@faye/ui/components/input";
import { Kbd } from "@faye/ui/components/kbd";
import { Popover, PopoverContent, PopoverTrigger } from "@faye/ui/components/popover";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@faye/ui/components/resizable";
import { Tabs, TabsList, TabsTrigger } from "@faye/ui/components/tabs";
import { cn } from "@faye/ui/lib/utils";
import { CircleX, FileCode, ListTree, Redo2, TriangleAlert, Undo2, Workflow, X } from "lucide-react";
import { useMemo, useState } from "react";
import type { PathView } from "../../../lib/api";
import { ENTRIES, SECTIONS } from "../../../lib/entries";
import { addressKey, block, type FlowDoc, fromBlock, insert, listOf, parseAddress, parseListKey, stepIds } from "../../../lib/flow";
import { PageHeader } from "../../shell";
import { dense, denseMono, IconButton, Overline, Prop } from "../../ui";
import { AddBlock } from "./add-block";
import { CodeEditor } from "./code-editor";
import { DraftActions, type Finding, useDraft } from "./draft";
import { Graph } from "./graph";
import { Inspector } from "./inspector";
import { Paths } from "./paths";
import { Preview } from "./preview";
import { addTarget, LIST, Tree, useDragging } from "./tree";

type View = "blocks" | "yaml";
type Side = "block" | "paths" | "preview";

const LAYOUT_KEY = "faye.flow.layout";

function savedLayout(): Record<string, number> | undefined {
  try {
    return JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? "null") ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * The login flow: the blocks or the YAML on the left, the graph of the same document in the middle,
 * and the selected block, the enumerated paths or a preview run on the right. Every edit, from
 * either view, redraws the graph and is checked by the server.
 */
export function FlowWorkspace() {
  const d = useDraft();
  const { doc, yaml, validation, previewing, setPreviewing, run } = d;
  const [view, setView] = useState<View>("blocks");
  const [side, setSide] = useState<Exclude<Side, "preview">>("block");
  const [selected, setSelected] = useState<string | null>(null);
  const [path, setPath] = useState<PathView | null>(null);
  const [dragging, setDragging] = useDragging();
  const [layout] = useState(savedLayout);

  // While the YAML does not parse, the graph keeps showing the last document that did
  const [lastGood, setLastGood] = useState<FlowDoc | null>(null);
  if (doc && doc !== lastGood) setLastGood(doc);
  const shown = doc ?? lastGood;

  // A path picked in Paths wins over the preview run
  const visited = useMemo(() => (path ? new Set(path.visits) : previewing && run ? new Set(run.visits) : null), [path, previewing, run]);

  const select = (key: string | null) => {
    setSelected(key);
    if (key && !key.startsWith(LIST) && !previewing) setSide("block");
  };

  const addBlock = (name: string) => {
    if (!doc) return;
    const { list, index } = addTarget(doc, selected);
    d.edit(insert(doc, list, index, fromBlock(block(name), list.kind, doc)));
    setSelected(addressKey({ section: list.section, path: [...list.owner, [list.kind, index]] }));
    setView("blocks");
  };

  const jump = (f: Finding) => {
    if (f.key && !f.key.startsWith("hook:") && doc) {
      setView("blocks");
      select(f.key);
    } else setView("yaml");
  };

  const sideValue: Side = previewing ? "preview" : side;
  const entrySelected = !!selected && !selected.startsWith(LIST) && !!doc && parseAddress(selected).path.length > 0;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={d.tenant} title="Login flow">
        <DraftActions preview />
      </PageHeader>

      {yaml === null ? (
        <div className="p-6 text-muted-foreground">Loading…</div>
      ) : (
        <ResizablePanelGroup
          orientation="horizontal"
          className="min-h-0 flex-1"
          defaultLayout={layout}
          onLayoutChanged={(l) => {
            try {
              localStorage.setItem(LAYOUT_KEY, JSON.stringify(l));
            } catch {
              // Private windows may refuse storage; the default layout is fine
            }
          }}
        >
          <ResizablePanel id="editor" defaultSize="40" minSize="24" className="flex min-w-0 flex-col">
            <div className="flex h-10 shrink-0 items-center gap-2 border-b px-2">
              <Tabs value={view} onValueChange={(v) => setView(v as View)}>
                <TabsList className="h-7 p-0.5">
                  <TabsTrigger value="blocks" className="h-6 px-2 text-xs">
                    <ListTree className="size-3.5" />
                    Blocks
                  </TabsTrigger>
                  <TabsTrigger value="yaml" className="h-6 px-2 text-xs">
                    <FileCode className="size-3.5" />
                    YAML
                  </TabsTrigger>
                </TabsList>
              </Tabs>
              {d.parseError && (
                <Badge variant="outline" className="border-danger/40 font-normal text-danger">
                  YAML does not parse
                </Badge>
              )}
              <span className="ml-auto flex items-center gap-1">
                {view === "blocks" && (
                  <>
                    <IconButton icon={Undo2} label="Undo (⌘Z)" onClick={d.undo} />
                    <IconButton icon={Redo2} label="Redo (⇧⌘Z)" onClick={d.redo} />
                  </>
                )}
                {doc && <AddBlock onAdd={addBlock} where={whereAdded(doc, selected)} />}
              </span>
            </div>
            <div className="relative min-h-0 flex-1">
              {view === "yaml" ? (
                <CodeEditor value={yaml} onChange={d.type} language="yaml" marks={d.marks} label="Flow YAML" className="h-full" />
              ) : doc ? (
                <Tree
                  doc={doc}
                  onEdit={d.edit}
                  selected={selected}
                  onSelect={select}
                  findings={d.findings}
                  visited={visited}
                  visitKeys={d.keys}
                  waiting={previewing && !path ? (run?.waiting ?? null) : null}
                  dragging={dragging}
                  setDragging={setDragging}
                />
              ) : (
                <div className="grid gap-3 p-6">
                  <p className="flex items-start gap-2">
                    <CircleX className="mt-0.5 size-4 shrink-0 text-danger" />
                    <span className="font-mono text-xs">{d.parseError}</span>
                  </p>
                  <Button variant="outline" size="sm" className="justify-self-start" onClick={() => setView("yaml")}>
                    Fix in YAML
                  </Button>
                </div>
              )}
            </div>
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="graph" defaultSize="32" minSize="16" collapsible className="flex min-w-0 flex-col">
            <div className="flex h-10 shrink-0 items-center gap-2 border-b px-3">
              <Workflow className="size-3.5 text-muted-foreground" />
              <span className="text-xs font-medium">Graph</span>
              <span className="text-xs text-muted-foreground">{doc ? "live" : "last valid"}</span>
              {(path || (previewing && run)) && (
                <Badge variant="outline" className="ml-auto min-w-0 gap-1 border-accent-ui/40 font-normal text-accent-ui">
                  <span className="truncate">{path ? "path" : "preview run"}</span>
                  {path && (
                    <button type="button" aria-label="Clear path" onClick={() => setPath(null)} className="-mr-0.5 hover:text-foreground">
                      <X className="size-3" />
                    </button>
                  )}
                </Badge>
              )}
            </div>
            <div className="min-h-0 flex-1">
              {shown ? (
                <Graph doc={shown} visited={visited} visitKeys={d.keys} selected={selected} onSelect={select} findings={d.findings} />
              ) : (
                <div className="p-6 text-muted-foreground">Nothing to draw yet</div>
              )}
            </div>
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="side" defaultSize="28" minSize={280} maxSize="45" className="flex min-w-0 flex-col">
            <Tabs
              value={sideValue}
              onValueChange={(v) => {
                setPreviewing(v === "preview");
                if (v !== "preview") setSide(v as Exclude<Side, "preview">);
              }}
              className="flex min-h-0 flex-1 flex-col gap-0"
            >
              <div className="flex h-10 shrink-0 items-center border-b px-2">
                <TabsList variant="line" className="h-10 gap-0 p-0">
                  <TabsTrigger value="block" className="h-10 px-2.5 text-xs">
                    {entrySelected ? "Block" : "Flow"}
                  </TabsTrigger>
                  <TabsTrigger value="paths" className="h-10 px-2.5 text-xs">
                    Paths
                    {validation && <span className="font-mono text-[11px] font-normal text-muted-foreground">{validation.paths.length}</span>}
                  </TabsTrigger>
                  <TabsTrigger value="preview" className="h-10 px-2.5 text-xs">
                    Preview
                  </TabsTrigger>
                </TabsList>
              </div>
              <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
                {sideValue === "preview" ? (
                  <Preview
                    tenant={d.tenant}
                    demoClient={d.demoClient}
                    blocked={d.blocked ?? (d.saved === "saving" ? "Saving the draft…" : d.saved === "failed" ? "The draft is not saved" : null)}
                    run={run}
                    onEvent={d.onTrace}
                    fail={d.fail}
                  />
                ) : sideValue === "paths" ? (
                  <Paths validation={validation} selected={path} onSelect={setPath} />
                ) : entrySelected && doc ? (
                  <Inspector
                    doc={doc}
                    selected={selected!}
                    onEdit={d.edit}
                    onSelect={select}
                    onDeleted={(id) => d.notify("ok", `Deleted ${id}`, true)}
                    catalog={d.catalog}
                    findings={d.findings.get(selected!)}
                  />
                ) : doc ? (
                  <FlowProperties doc={doc} onEdit={d.edit} selectedList={selected?.startsWith(LIST) ? selected : null} />
                ) : null}
              </div>
            </Tabs>
          </ResizablePanel>
        </ResizablePanelGroup>
      )}

      <StatusBar onJump={jump} path={path} clearPath={() => setPath(null)} />
    </div>
  );
}

/** Where Add block puts a block, in words. */
function whereAdded(doc: FlowDoc, selected: string | null): string {
  if (selected?.startsWith(LIST)) {
    const list = parseListKey(selected.slice(LIST.length));
    const owner = list.owner.length > 0 ? listOf(doc, { ...list, owner: list.owner.slice(0, -1), kind: list.owner[list.owner.length - 1][0] })?.[list.owner[list.owner.length - 1][1]]?.id : null;
    return owner ? `Adds to the end of ${owner}'s ${list.kind === "alternative" ? "options" : "steps"}` : `Adds to the end of ${ENTRIES[list.section].title}`;
  }
  if (selected) {
    const a = parseAddress(selected);
    if (a.path.length > 0) {
      const { list, index } = addTarget(doc, selected);
      const before = listOf(doc, list)?.[index - 1]?.id;
      if (before) return `Adds after ${before}`;
    }
  }
  return "Adds to the end of Sign in. Select a block to add after it.";
}

/** With no block selected: the flow's own properties, and how to drive the editor. */
function FlowProperties({ doc, onEdit, selectedList }: { doc: FlowDoc; onEdit: (doc: FlowDoc) => void; selectedList: string | null }) {
  const steps = stepIds(doc).size;
  return (
    <div className="grid content-start">
      <section className="grid gap-2.5 border-b px-3 pt-3 pb-4">
        <Overline>Flow</Overline>
        <Prop label="Id">
          <Input aria-label="Flow id" spellCheck={false} className={cn(denseMono, "flex-1")} value={doc.flow} onChange={(e) => onEdit({ ...doc, flow: e.target.value })} />
        </Prop>
        <Prop label="Version">
          <Input
            aria-label="Flow version"
            type="number"
            min={1}
            className={cn(denseMono, "w-20")}
            value={doc.version}
            onChange={(e) => onEdit({ ...doc, version: Math.max(1, Number(e.target.value) || 1) })}
          />
        </Prop>
        <Prop label="Sections">
          <span className="font-mono text-xs">{SECTIONS.filter((s) => doc[s]).join(" · ")}</span>
        </Prop>
        <Prop label="Size">
          <span className="text-xs text-muted-foreground">
            {steps} {steps === 1 ? "step" : "steps"} · {doc.hooks.length} {doc.hooks.length === 1 ? "hook" : "hooks"}
          </span>
        </Prop>
      </section>
      <section className="grid gap-2.5 px-3 pt-3 pb-4 text-xs text-muted-foreground">
        <Overline>Editing</Overline>
        <p className={cn(dense, "h-auto px-0")}>
          {selectedList ? "Add block puts new blocks at the end of the selected list." : "Select a block in the list or the graph to edit it here."}
        </p>
        <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5">
          <Shortcut keys={["↑", "↓"]}>select</Shortcut>
          <Shortcut keys={["⌘↑", "⌘↓"]}>move</Shortcut>
          <Shortcut keys={["⌫"]}>delete</Shortcut>
          <Shortcut keys={["/"]}>add a block</Shortcut>
          <Shortcut keys={["⌘Z"]}>undo</Shortcut>
          <Shortcut keys={["⌘B"]}>toggle sidebar</Shortcut>
        </dl>
      </section>
    </div>
  );
}

function Shortcut({ keys, children }: { keys: string[]; children: string }) {
  return (
    <>
      <dt className="flex gap-1">
        {keys.map((k) => (
          <Kbd key={k}>{k}</Kbd>
        ))}
      </dt>
      <dd>{children}</dd>
    </>
  );
}

/** Errors, warnings and path counts of the draft; the counts open the list of problems. */
export function StatusBar({ onJump, path, clearPath }: { onJump: (f: Finding) => void; path?: PathView | null; clearPath?: () => void }) {
  const { validation, problems } = useDraft();
  const [open, setOpen] = useState(false);
  const errors = validation?.errors.length ?? 0;
  const warnings = validation?.warnings.length ?? 0;
  const successes = validation?.paths.filter((p) => p.succeeds).length ?? 0;
  return (
    <footer className="flex h-7 min-w-0 shrink-0 items-center gap-4 border-t bg-surface-2 px-3 font-mono text-xs whitespace-nowrap text-muted-foreground" aria-live="polite">
      {!validation ? (
        <span>checking…</span>
      ) : (
        <>
          <Popover open={open} onOpenChange={setOpen}>
            <PopoverTrigger asChild>
              <button type="button" disabled={problems.length === 0} className="flex shrink-0 items-center gap-3 enabled:hover:text-foreground">
                <span className="flex items-center gap-1.5">
                  <span className={cn("size-1.5 rounded-full", errors > 0 ? "bg-danger" : "bg-success")} />
                  <span className={errors > 0 ? "text-danger" : ""}>{plural(errors, "error")}</span>
                </span>
                <span className="flex items-center gap-1.5">
                  <span className={cn("size-1.5 rounded-full", warnings > 0 ? "bg-warning" : "bg-border")} />
                  {plural(warnings, "warning")}
                </span>
              </button>
            </PopoverTrigger>
            <PopoverContent side="top" align="start" className="w-[min(560px,90vw)] p-0">
              <div className="border-b px-3 py-2 text-xs font-medium">Problems</div>
              <ul className="max-h-72 overflow-auto py-1">
                {problems.map((f, i) => (
                  <li key={i}>
                    <button
                      type="button"
                      onClick={() => {
                        setOpen(false);
                        onJump(f);
                      }}
                      className="flex w-full items-start gap-2 px-3 py-1.5 text-left text-xs hover:bg-accent"
                    >
                      {f.severity === "error" ? (
                        <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
                      ) : (
                        <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warning" />
                      )}
                      <span className="flex-1">{f.message}</span>
                      {f.line > 0 && <span className="font-mono text-muted-foreground">line {f.line}</span>}
                    </button>
                  </li>
                ))}
              </ul>
            </PopoverContent>
          </Popover>
          <span className="shrink-0">
            {plural(validation.paths.length, "path")} · {successes} succeed · {validation.paths.length - successes} fail
          </span>
          <span className="hidden min-w-0 truncate xl:inline">{validation.summary}</span>
          {path && clearPath && (
            <button
              type="button"
              onClick={clearPath}
              className="ml-auto flex h-5 max-w-[40%] min-w-0 shrink items-center gap-1 rounded-[4px] bg-accent-ui/10 px-1.5 text-accent-ui hover:bg-accent-ui/15"
            >
              <span className="truncate">{path.path}</span>
              <X className="size-3" />
            </button>
          )}
        </>
      )}
    </footer>
  );
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
