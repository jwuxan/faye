"use client";

import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Input } from "@faye/ui/components/input";
import { Kbd } from "@faye/ui/components/kbd";
import { Popover, PopoverContent, PopoverTrigger } from "@faye/ui/components/popover";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@faye/ui/components/resizable";
import { Spinner } from "@faye/ui/components/spinner";
import { Tabs, TabsList, TabsTrigger } from "@faye/ui/components/tabs";
import { Textarea } from "@faye/ui/components/textarea";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import { useIsMobile } from "@faye/ui/hooks/use-mobile";
import { cn } from "@faye/ui/lib/utils";
import {
  CircleCheck,
  CircleX,
  FileCode,
  ListTree,
  type LucideIcon,
  PanelRightClose,
  PanelRightOpen,
  Play,
  Redo2,
  RotateCcw,
  Route,
  Settings2,
  TriangleAlert,
  Undo2,
  Workflow,
  X,
} from "lucide-react";
import { useCallback, useMemo, useRef, useState } from "react";
import type { PathView } from "../../../../lib/api";
import { APPROVE, AUTHENTICATE, ENTRIES } from "../../../../lib/entries";
import {
  addressKey,
  block,
  consentStart,
  type Defaults,
  entryAt,
  type FlowDoc,
  fromBlock,
  insert,
  isLegacy,
  type ListRef,
  listOf,
  misfit,
  parseAddress,
  parseListKey,
  recordsConsent,
  stepIds,
} from "../../../../lib/flow";
import { PageHeader } from "../../shell";
import { CopyButton, dense, denseMono, Help, IconButton, PaneBar, PanelSection, Prop, TabToolbar } from "../../ui";
import { AddBlock } from "./add-block";
import { CodeEditor } from "./code-editor";
import { DraftActions, type Finding, useDraft } from "./draft";
import { Graph } from "./graph";
import { Inspector } from "./inspector";
import { PathFilter, type PathFilterValue, Paths } from "./paths";
import { Preview } from "./preview";
import { FlowTimeLimits } from "./settings";
import { addTarget, LIST, Tree, useDragging } from "./tree";

type Tab = "blocks" | "yaml" | "paths" | "settings" | "preview";
/** The one pane a phone shows. */
type Pane = "editor" | "graph";

const LAYOUT_KEY = "faye.flow.layout.v2";
/** Width of the block panel: `w-88` floating, `w-80` docked, and the gap around the floating one. */
const PANEL = 320;
const PANEL_PX = 352 + 24;

function savedLayout(): Record<string, number> | undefined {
  try {
    return JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? "null") ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * The login flow: tabs on the left (the blocks or the YAML, the enumerated paths, the flow's settings
 * and a preview run), and the graph of the same document as a canvas on the right, with the selected
 * block's inspector floating over it. Every edit, from either view, redraws the graph and is checked
 * by the server.
 */
export function FlowWorkspace() {
  const d = useDraft();
  const { doc, yaml, validation, previewing, setPreviewing, run } = d;
  const [tab, setTabState] = useState<Exclude<Tab, "preview">>("blocks");
  const [selected, setSelected] = useState<string | null>(null);
  const [path, setPath] = useState<PathView | null>(null);
  const [panelOpen, setPanelOpen] = useState(true);
  const [dragging, setDragging] = useDragging();
  const [layout] = useState(savedLayout);
  const isMobile = useIsMobile();
  const [pane, setPane] = useState<Pane>("editor");
  const [pathFilter, setPathFilter] = useState<PathFilterValue>("all");
  // Bumped by the toolbar's Reset scenario
  const [previewReset, setPreviewReset] = useState(0);
  // The preview's trace feed, shown in the toolbar while the Preview tab is current
  const [following, setFollowing] = useState<"connecting" | "open" | "lost">("connecting");
  // The toolbar's place for the Preview tab's Preview button
  const [launchSlot, setLaunchSlot] = useState<HTMLDivElement | null>(null);
  // Below this width the block panel docks beside the graph instead of floating over it
  const [canvasW, setCanvasW] = useState(0);
  const canvasObserver = useRef<ResizeObserver | null>(null);
  // A callback ref, not an effect: the canvas mounts late, once the draft has loaded
  const canvasBox = useCallback((el: HTMLDivElement | null) => {
    canvasObserver.current?.disconnect();
    canvasObserver.current = null;
    if (el) {
      canvasObserver.current = new ResizeObserver(() => setCanvasW(el.clientWidth));
      canvasObserver.current.observe(el);
    }
  }, []);
  // How the block panel sits on the canvas: floats over it while it is wide, docks beside it
  // while that still leaves a usable graph, else covers the whole canvas (the graph stays
  // mounted underneath; closing shows it again).
  const panelMode: "float" | "dock" | "cover" = isMobile || canvasW >= 720 || canvasW === 0 ? "float" : canvasW - PANEL >= 240 ? "dock" : "cover";

  // Preview is a tab: being on it is what runs one
  const current: Tab = previewing ? "preview" : tab;
  const setTab = (t: Tab) => {
    setPreviewing(t === "preview");
    if (t !== "preview") setTabState(t);
  };

  // While the YAML does not parse, the graph keeps showing the last document that did
  const [lastGood, setLastGood] = useState<FlowDoc | null>(null);
  if (doc && doc !== lastGood) setLastGood(doc);
  const shown = doc ?? lastGood;

  // A path picked in Paths wins over the preview run
  const visited = useMemo(() => (path ? new Set(path.visits) : previewing && run ? new Set(run.visits) : null), [path, previewing, run]);

  const select = (key: string | null) => {
    setSelected(key);
    if (key && !key.startsWith(LIST)) setPanelOpen(true);
  };

  /** Where a block would go now, and why it cannot, if it cannot. */
  const placeFor = (name: string) => {
    const b = block(name);
    // The Identifier has one place, whatever is selected
    const target: { list: ListRef; index: number } =
      b.role === "identifier"
        ? { list: { section: AUTHENTICATE, owner: [], kind: "steps" }, index: 0 }
        : b.role === "approve"
          ? { list: { section: APPROVE, owner: [], kind: "steps" }, index: doc!.approve?.length ?? 0 }
          : addTarget(doc!, selected, recordsConsent(b.body()));
    const policy = d.settings?.settings;
    const banned =
      (b.use !== undefined && policy?.handlers != null && !policy.handlers.includes(b.use)) ||
      (b.screen !== undefined && policy?.screens != null && !policy.screens.includes(b.screen));
    // A code block whose channel has no delivery configured for this tenant (ADR 0032)
    const undelivered = b.use !== undefined && (d.settings?.undeliverable ?? []).includes(b.use);
    return {
      ...target,
      problem: undelivered
        ? undeliveredReason(b.use!)
        : banned
          ? "Disallowed in General settings"
          : misfit(doc!, target.list, target.index, b.role),
    };
  };

  const addBlock = (name: string) => {
    if (!doc) return;
    const { list, index, problem } = placeFor(name);
    if (problem) return;
    d.edit(insert(doc, list, index, fromBlock(block(name), list.kind)));
    select(addressKey({ section: list.section, path: [...list.owner, [list.kind, index]] }));
    setTab("blocks");
  };

  const jump = (f: Finding) => {
    if (f.key && !f.key.startsWith("hook:") && doc) {
      setTab("blocks");
      select(f.key);
    } else setTab("yaml");
  };

  const entrySelected = !!selected && !selected.startsWith(LIST) && !!doc && parseAddress(selected).path.length > 0;
  const editing = current === "blocks" || current === "yaml";
  const hint = current === "blocks" ? "Drag to reorder. Press / to add a block." : "Ctrl+Space autocompletes keys, blocks and paths.";

  const unparsed = (
    <div className="grid gap-3 p-6">
      <p className="flex items-start gap-2">
        <CircleX className="mt-0.5 size-4 shrink-0 text-danger" />
        <span className="font-mono text-xs">{d.parseError}</span>
      </p>
      <Button variant="outline" size="sm" className="justify-self-start" onClick={() => setTab("yaml")}>
        Fix in YAML
      </Button>
    </div>
  );

  const editorPane = (
    <>
      <div className="@container flex h-10 shrink-0 items-center gap-2 border-b px-4">
        <Tabs value={current} onValueChange={(v) => setTab(v as Tab)} className="min-w-0">
          <TabsList className="p-0.5 group-data-[orientation=horizontal]/tabs:h-7">
            <EditorTab value="blocks" icon={ListTree}>
              Blocks
            </EditorTab>
            <EditorTab value="yaml" icon={FileCode}>
              YAML
            </EditorTab>
            <EditorTab value="paths" icon={Route} count={validation?.pathCount}>
              Paths
            </EditorTab>
            <EditorTab value="settings" icon={Settings2}>
              Settings
            </EditorTab>
            <EditorTab value="preview" icon={Play}>
              Preview
            </EditorTab>
          </TabsList>
        </Tabs>
      </div>
      {/* Every tab has the same toolbar under the tabs: its state on the left, its actions on the right */}
      <TabToolbar
        actions={
          editing ? (
            <>
              {(current === "blocks" || current === "yaml") && <Help side="bottom">{hint}</Help>}
              {current === "blocks" && (
                <>
                  <IconButton icon={Undo2} label="Undo (⌘Z)" onClick={d.undo} />
                  <IconButton icon={Redo2} label="Redo (⇧⌘Z)" onClick={d.redo} />
                </>
              )}
              {doc && <AddBlock onAdd={addBlock} where={whereAdded(doc, selected)} problem={(name) => placeFor(name).problem} />}
            </>
          ) : current === "paths" ? (
            <Help side="bottom">
              Every way the draft can end. A succeeding path shows the amr and acr it issues, computed from the credentials it verifies.
            </Help>
          ) : current === "preview" ? (
            <IconButton icon={RotateCcw} label="Reset scenario" onClick={() => setPreviewReset((n) => n + 1)} />
          ) : undefined
        }
      >
        {d.parseError ? (
          <Badge variant="outline" className="border-danger/40 font-normal text-danger">
            YAML does not parse
          </Badge>
        ) : current === "paths" ? (
          validation && validation.pathCount > 0 && <PathFilter validation={validation} value={pathFilter} onChange={setPathFilter} />
        ) : current === "settings" ? (
          <span>Publishes with the draft, except time limits</span>
        ) : current === "preview" ? (
          <>
            <div ref={setLaunchSlot} className="flex shrink-0 items-center gap-1" />
            <Badge variant="outline" className="h-5 gap-1.5 font-normal" role="status">
              <span className={cn("size-1.5 rounded-full", following === "open" ? "bg-success" : following === "lost" ? "bg-danger" : "bg-warning")} />
              {following === "open" ? "Following trace" : following === "lost" ? "Trace disconnected" : "Connecting…"}
            </Badge>
          </>
        ) : null}
      </TabToolbar>
      <div className="relative min-h-0 flex-1">
        {current === "yaml" ? (
          <CodeEditor value={yaml ?? ""} onChange={d.type} language="yaml" marks={d.marks} label="Flow YAML" className="h-full" />
        ) : current === "paths" ? (
          <Paths tenant={d.tenant} validation={validation} checked={d.checked} filter={pathFilter} selected={path} onSelect={setPath} />
        ) : current === "settings" ? (
          <div className="h-full overflow-x-hidden overflow-y-auto">{doc ? <FlowSettings doc={doc} onEdit={d.edit} tenant={d.tenant} live={d.live} /> : unparsed}</div>
        ) : current === "preview" ? (
          <div className="h-full overflow-x-hidden overflow-y-auto">
            <Preview
              tenant={d.tenant}
              demoClient={d.demoClient}
              blocked={d.blocked ?? (d.saved === "saving" ? "Saving the draft…" : d.saved === "failed" ? "Draft not saved" : null)}
              run={run}
              onEvent={d.onTrace}
              fail={d.fail}
              reset={previewReset}
              onFollowing={setFollowing}
              launchSlot={launchSlot}
            />
          </div>
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
          unparsed
        )}
      </div>
    </>
  );

  const floating = "rounded-lg border bg-background shadow-sm";
  const panelBody = (
    <>
      <PaneBar>
        <span className="text-xs font-medium">{entrySelected ? "Block" : "Flow"}</span>
        {entrySelected && doc && <span className="min-w-0 truncate font-mono text-xs text-muted-foreground">{entryAt(doc, parseAddress(selected!))?.id}</span>}
        <IconButton icon={PanelRightClose} label="Hide panel" className="-mr-1.5 ml-auto" onClick={() => setPanelOpen(false)} />
      </PaneBar>
      {/* The panel is as tall as what it shows, up to the canvas; past that it scrolls */}
      <div className={cn("min-h-0 overflow-x-hidden overflow-y-auto", (isMobile || panelMode !== "float") && "flex-1")}>
        {entrySelected && doc ? (
          <Inspector
            defaults={(d.settings?.settings.blocks ?? {}) as Defaults}
            undeliverable={d.settings?.undeliverable}
            doc={doc}
            selected={selected!}
            onEdit={d.edit}
            onSelect={select}
            onDeleted={(id) => d.notify("ok", `Deleted ${id}`, true)}
            findings={d.findings.get(selected!)}
          />
        ) : (
          <EditingHelp selectedList={selected?.startsWith(LIST) ? selected : null} />
        )}
      </div>
    </>
  );
  const canvas = (
    <div ref={canvasBox} className="flex min-h-0 flex-1 overflow-hidden">
      <div className="relative min-h-0 flex-1 overflow-hidden">
        {shown ? (
          <Graph
            doc={shown}
            visited={visited}
            visitKeys={d.keys}
            selected={selected}
            onSelect={select}
            findings={d.findings}
            reserve={panelOpen && panelMode === "float" && !isMobile ? PANEL_PX : 0}
            tenant={d.tenant}
          />
        ) : (
          <div className="p-6 text-muted-foreground">No valid flow to draw</div>
        )}

        <div className={cn(floating, "absolute top-3 left-3 flex h-8 items-center gap-2 px-2.5")}>
          <Workflow className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="text-xs font-medium">Graph</span>
          <Badge variant="outline" className="h-5 font-normal">{doc ? "draft" : "last valid draft"}</Badge>
          {(path || (previewing && run)) && (
            <Badge variant="outline" className="min-w-0 animate-in fade-in-0 gap-1 border-accent-ui/40 font-normal text-accent-ui">
              <span className="truncate">{path ? "path" : "preview run"}</span>
              {path && (
                <Button type="button" variant="ghost" size="icon-xs" aria-label="Clear path" onClick={() => setPath(null)} className="-mr-0.5 size-4 hover:bg-transparent hover:text-foreground">
                  <X className="size-3" />
                </Button>
              )}
            </Badge>
          )}
        </div>

        {panelOpen && panelMode !== "dock" ? (
          <aside
            aria-label={entrySelected ? "Block" : "Flow"}
            className={cn(
              "absolute flex animate-in flex-col overflow-hidden bg-background fade-in-0 motion-reduce:animate-none",
              isMobile
                ? "inset-2 rounded-xl border shadow-lg slide-in-from-right-2"
                : panelMode === "cover"
                  ? "inset-0"
                  : "top-3 right-3 max-h-[calc(100%-(--spacing(6)))] w-88 rounded-xl border shadow-lg slide-in-from-right-2",
            )}
          >
            {panelBody}
          </aside>
        ) : (
          !panelOpen && (
            <Button variant="outline" size="sm" className={cn(floating, "absolute top-3 right-3 h-8 text-xs")} onClick={() => setPanelOpen(true)}>
              <PanelRightOpen />
              {entrySelected ? "Block" : "Flow"}
            </Button>
          )
        )}
      </div>
      {panelOpen && panelMode === "dock" && (
        <aside aria-label={entrySelected ? "Block" : "Flow"} className="flex w-80 shrink-0 flex-col border-l bg-background">
          {panelBody}
        </aside>
      )}
    </div>
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={d.tenant} title="Login flow">
        <DraftActions />
      </PageHeader>

      {/* Old YAML opens as blocks, but the server checks what is saved: rewrite it only when asked */}
      {yaml !== null && doc && isLegacy(yaml) && (
        <div className="flex animate-in fade-in-0 slide-in-from-top-2 items-center gap-3 border-b bg-warning/10 px-4 py-2 text-xs" role="status">
          <TriangleAlert className="size-3.5 shrink-0 text-warning-text" />
          <span className="flex-1">
            This flow uses screens and handlers instead of blocks. The server rejects it until it is upgraded. Upgrading keeps only the first comment.
          </span>
          <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => d.edit(doc)}>
            Upgrade to blocks
          </Button>
        </div>
      )}

      {yaml === null ? (
        <div className="p-6 text-muted-foreground">Loading…</div>
      ) : isMobile ? (
        // A phone has room for one pane: pick it here
        <Tabs value={pane} onValueChange={(v) => setPane(v as Pane)} className="flex min-h-0 flex-1 flex-col gap-0">
          <div className="flex h-10 shrink-0 items-center border-b bg-surface-2 px-4">
            <TabsList className="w-full p-0.5 group-data-[orientation=horizontal]/tabs:h-8">
              <TabsTrigger value="editor" className="h-7 text-xs">
                Editor
              </TabsTrigger>
              <TabsTrigger value="graph" className="h-7 text-xs">
                Graph
              </TabsTrigger>
            </TabsList>
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">{pane === "editor" ? editorPane : canvas}</div>
        </Tabs>
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
          <ResizablePanel id="editor" defaultSize="44" minSize="24" className="flex min-w-0 flex-col">
            {editorPane}
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="graph" defaultSize="56" minSize="30" className="flex min-w-0 flex-col">
            {canvas}
          </ResizablePanel>
        </ResizablePanelGroup>
      )}

      <StatusBar onJump={jump} path={path} clearPath={() => setPath(null)} />
    </div>
  );
}

/** A tab of the left panel; in a narrow panel only its icon shows, and its name stays for screen readers. */
function EditorTab({ value, icon: Icon, count, children }: { value: Tab; icon: LucideIcon; count?: number; children: string }) {
  return (
    <TabsTrigger value={value} className="h-6 px-2 text-xs" title={children}>
      <Icon className="size-3.5" />
      <span className="sr-only @sm:not-sr-only">{children}</span>
      {count !== undefined && <span className="hidden text-xs font-normal text-muted-foreground tabular-nums @md:inline">{count}</span>}
    </TabsTrigger>
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
  return `Adds to the end of ${ENTRIES[AUTHENTICATE].title}, before Consent. Select a block to add after it.`;
}

/** The Settings tab: what the flow says about itself, rather than about any one block. */
function FlowSettings({ doc, onEdit, tenant, live }: { doc: FlowDoc; onEdit: (doc: FlowDoc) => void; tenant: string; live: string | null }) {
  const steps = stepIds(doc).size;
  // The description is the comment above the document, one space after each `#`
  const description = (doc.comment ?? "").split("\n").map((l) => l.replace(/^ /, "")).join("\n");
  return (
    <div className="grid content-start">
      <PanelSection title="Flow">
        <Prop label="Name">
          <Input aria-label="Flow name" spellCheck={false} className={cn(denseMono, "flex-1")} value={doc.flow} onChange={(e) => onEdit({ ...doc, flow: e.target.value })} />
        </Prop>
        <Prop label="ID">
          <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground" title={live ?? undefined}>
            {live ?? "Not published"}
          </span>
          {live && <CopyButton value={live} label="Copy ID" />}
          <Help>Hash of the live version. Set on publish.</Help>
        </Prop>
        <Prop label="Tenant">
          <span className="font-mono text-xs text-muted-foreground">{tenant}</span>
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
        <Prop label="Description" align="start">
          <Textarea
            aria-label="Flow description"
            spellCheck={false}
            rows={3}
            value={description}
            onChange={(e) => onEdit({ ...doc, comment: e.target.value ? e.target.value.split("\n").map((l) => (l ? ` ${l}` : "")).join("\n") : undefined })}
            className="min-h-16 resize-y text-sm"
          />
        </Prop>
      </PanelSection>
      <PanelSection title="Time limits">
        <FlowTimeLimits />
      </PanelSection>
      <PanelSection title="Shape">
        <Prop label="Entries">
          <span className="font-mono text-xs">{consentStart(doc.login) < doc.login.length ? "login · consent" : "login"}</span>
        </Prop>
        <Prop label="Size">
          <span className="text-xs text-muted-foreground">
            {steps} {steps === 1 ? "step" : "steps"} · {doc.hooks.length} {doc.hooks.length === 1 ? "hook" : "hooks"}
          </span>
        </Prop>
      </PanelSection>
    </div>
  );
}

/** The floating panel with no block selected: how to drive the editor. */
function EditingHelp({ selectedList }: { selectedList: string | null }) {
  return (
    <section className="grid gap-3 px-4 pt-4 pb-5 text-xs text-muted-foreground">
      <p className={cn(dense, "h-auto px-0")}>
        {selectedList ? "Add block appends to the selected list." : "Select a block in the list or graph to edit it."}
      </p>
      <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5">
        <Shortcut keys={["↑", "↓"]}>Select block</Shortcut>
        <Shortcut keys={["⌘↑", "⌘↓"]}>Move block</Shortcut>
        <Shortcut keys={["⌫"]}>Delete block</Shortcut>
        <Shortcut keys={["/"]}>Add block</Shortcut>
        <Shortcut keys={["⌘Z"]}>Undo</Shortcut>
        <Shortcut keys={["⌘B"]}>Toggle sidebar</Shortcut>
        <Shortcut keys={["⌘K"]}>Command palette</Shortcut>
      </dl>
    </section>
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
  const successes = validation?.succeeding ?? 0;
  return (
    <footer className="flex h-8 min-w-0 shrink-0 items-center gap-2 border-t bg-surface-2 px-4 text-xs whitespace-nowrap text-muted-foreground tabular-nums" aria-live="polite">
      {!validation ? (
        <span className="flex items-center gap-1.5">
          <Spinner className="size-3" aria-hidden /> checking…
        </span>
      ) : (
        <>
          <Popover open={open} onOpenChange={setOpen}>
            <PopoverTrigger asChild>
              <button type="button" disabled={problems.length === 0} className="flex shrink-0 items-center gap-1.5 transition-colors enabled:hover:text-foreground">
                {errors === 0 && warnings === 0 ? (
                  <Badge variant="outline" className="h-5 gap-1 font-normal">
                    <CircleCheck className="size-3 text-success" />
                    No problems
                  </Badge>
                ) : (
                  <>
                    {errors > 0 && (
                      <Badge variant="outline" className="h-5 gap-1 border-danger/40 bg-danger/5 font-normal text-danger">
                        <CircleX className="size-3" />
                        <span key={errors} className="count-tick">{plural(errors, "error")}</span>
                      </Badge>
                    )}
                    {warnings > 0 && (
                      <Badge variant="outline" className="h-5 gap-1 border-warning/40 bg-warning/10 font-normal text-warning-text">
                        <TriangleAlert className="size-3" />
                        <span key={warnings} className="count-tick">{plural(warnings, "warning")}</span>
                      </Badge>
                    )}
                  </>
                )}
              </button>
            </PopoverTrigger>
            <PopoverContent side="top" align="start" className="w-[min(--spacing(140),90vw)] p-0">
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
          <Tooltip>
            <TooltipTrigger asChild>
              <Badge key={`${validation.pathCount}-${successes}`} variant="secondary" className="h-5 shrink-0 gap-1 font-normal">
                <Route className="size-3" />
                <span className="count-tick">{plural(validation.pathCount, "path")}</span>
              </Badge>
            </TooltipTrigger>
            <TooltipContent side="top">
              {successes} succeed · {validation.pathCount - successes} fail
              <br />
              {validation.summary}
            </TooltipContent>
          </Tooltip>
          {path && clearPath && (
            <Badge variant="outline" className="ml-auto h-5 max-w-2/5 min-w-0 gap-1 border-accent-ui/40 bg-accent-ui/5 font-normal text-accent-ui">
              <span className="truncate">{path.path}</span>
              <Button type="button" variant="ghost" size="icon-xs" aria-label="Clear path" onClick={clearPath} className="size-4 shrink-0 hover:bg-transparent hover:text-foreground">
                <X className="size-3" />
              </Button>
            </Badge>
          )}
        </>
      )}
    </footer>
  );
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

/** Why a code block cannot be added: the deployment sends no codes of its kind for this tenant (ADR 0032). */
function undeliveredReason(handler: string): string {
  const channel = handler === "email_code.verify" ? "email" : "sms";
  return `No ${channel === "email" ? "email" : "text message"} delivery for this tenant; set faye.tenants.<id>.delivery.${channel}`;
}
