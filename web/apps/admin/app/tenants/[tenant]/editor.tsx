"use client";

import { Button } from "@faye/ui/components/button";
import { cn } from "@faye/ui/lib/utils";
import {
  ChevronsUpDown,
  CircleCheck,
  CircleX,
  FileCode,
  GitCommitHorizontal,
  ListTree,
  LogOut,
  type LucideIcon,
  Play,
  Route,
  Settings2,
  SquareCode,
  TriangleAlert,
  Workflow,
  X,
} from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, type Catalog, type Draft, type PathView, type Published, SignedOut, type SettingsView, type Validation } from "../../../lib/api";
import { addressKey, block, type FlowDoc, fromBlock, fromYaml, insert, parseAddress, toYaml, visitKeys } from "../../../lib/flow";
import { locate } from "../../../lib/locate";
import { follow, type LiveRun, type Recorded } from "../../../lib/trace";
import { Wordmark } from "../../wordmark";
import { CodeEditor, type Mark } from "./code-editor";
import { Graph } from "./graph";
import { Hooks } from "./hooks";
import { Inspector } from "./inspector";
import { Paths } from "./paths";
import { Preview } from "./preview";
import { Settings } from "./settings";
import { addTarget, type Findings, LIST, Palette, Tree, useDragging } from "./tree";
import { Versions } from "./versions";

type Tab = "Blocks" | "YAML" | "Graph" | "Paths" | "Hooks" | "Versions" | "Settings";
const FLOW_TABS: { tab: Tab; icon: LucideIcon }[] = [
  { tab: "Blocks", icon: ListTree },
  { tab: "YAML", icon: FileCode },
  { tab: "Graph", icon: Workflow },
  { tab: "Paths", icon: Route },
  { tab: "Hooks", icon: SquareCode },
];
const TENANT_TABS: { tab: Tab; icon: LucideIcon }[] = [
  { tab: "Versions", icon: GitCommitHorizontal },
  { tab: "Settings", icon: Settings2 },
];

/** Debounce for checking and saving the draft after an edit. */
const QUIET_MS = 400;

type Toast = { id: number; kind: "ok" | "error"; text: string; undo?: boolean };
type Finding = { severity: "error" | "warning"; message: string; line: number; key: string | null };

/**
 * One tenant's flow. The YAML text is the state: block edits rewrite it, YAML edits replace it, and
 * every change is checked by the server and saved as the draft.
 */
export function Editor({ tenant, tenants, subject, demoClient }: { tenant: string; tenants: string[]; subject: string; demoClient: string }) {
  const [yaml, setYaml] = useState<string | null>(null);
  const [files, setFiles] = useState<Record<string, string>>({});
  const [origin, setOrigin] = useState<Draft["origin"] | null>(null);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [settings, setSettings] = useState<SettingsView | null>(null);
  const [live, setLive] = useState<string | null>(null);
  const [validation, setValidation] = useState<Validation | null>(null);
  const [saved, setSaved] = useState<"saved" | "saving" | "failed">("saved");
  const [tab, setTab] = useState<Tab>("Blocks");
  const [selected, setSelected] = useState<string | null>(null);
  const [path, setPath] = useState<PathView | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [drawer, setDrawer] = useState(false);
  const [signedOut, setSignedOut] = useState(false);
  const [published, setPublished] = useState(0);
  const [previewing, setPreviewing] = useState(false);
  const [run, setRun] = useState<LiveRun | null>(null);
  const onTrace = useCallback((r: Recorded) => setRun((prev) => follow(prev, r)), []);
  const [dragging, setDragging] = useDragging();
  const loaded = useRef<string | null>(null);

  const notify = useCallback((kind: "ok" | "error", text: string, undo?: boolean) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, kind, text, undo }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "ok" ? 4500 : 8000);
  }, []);
  const fail = useCallback(
    (e: unknown) => {
      if (e instanceof SignedOut) setSignedOut(true);
      else notify("error", (e as Error).message);
    },
    [notify],
  );
  const refreshLive = useCallback(() => {
    api<{ id: string; current: string | null }[]>("tenants")
      .then(({ body }) => setLive(body.find((t) => t.id === tenant)?.current ?? null))
      .catch(fail);
  }, [tenant, fail]);

  useEffect(() => {
    Promise.all([api<Draft>(`tenants/${tenant}/draft`), api<Catalog>("catalog"), api<SettingsView>(`tenants/${tenant}/settings`)])
      .then(([d, c, s]) => {
        loaded.current = d.body.yaml;
        setYaml(d.body.yaml);
        setFiles(d.body.files);
        setOrigin(d.body.origin);
        setCatalog(c.body);
        setSettings(s.body);
      })
      .catch(fail);
    refreshLive();
  }, [tenant, fail, refreshLive]);

  // Check and save the draft once edits pause
  useEffect(() => {
    if (yaml === null) return;
    const timer = setTimeout(async () => {
      try {
        const { body } = await api<Validation>(`tenants/${tenant}/validate`, { method: "POST", yaml });
        setValidation(body);
        if (yaml !== loaded.current) {
          setSaved("saving");
          const d = await api<Draft>(`tenants/${tenant}/draft`, { method: "PUT", json: { yaml } });
          loaded.current = yaml;
          setFiles(d.body.files);
          setOrigin("draft");
          setSaved("saved");
        }
      } catch (e) {
        setSaved("failed");
        fail(e);
      }
    }, QUIET_MS);
    return () => clearTimeout(timer);
  }, [yaml, tenant, fail]);

  const parsed = useMemo(() => {
    if (yaml === null) return null;
    try {
      return { doc: fromYaml(yaml) };
    } catch (e) {
      return { error: (e as Error).message };
    }
  }, [yaml]);
  const doc = parsed && "doc" in parsed ? parsed.doc : null;

  // Undo history of whole drafts, for block edits and loads (the YAML tab keeps its own)
  const current = useRef<string | null>(null);
  const past = useRef<string[]>([]);
  const future = useRef<string[]>([]);
  useEffect(() => {
    current.current = yaml;
  }, [yaml]);
  const commit = useCallback((next: string) => {
    const prev = current.current;
    if (prev !== null && prev !== next) {
      past.current = [...past.current.slice(-99), prev];
      future.current = [];
    }
    setYaml(next);
  }, []);
  const edit = useCallback((next: FlowDoc) => commit(toYaml(next)), [commit]);
  const undo = useCallback(() => {
    const prev = past.current.pop();
    if (prev === undefined || current.current === null) return;
    future.current.push(current.current);
    setYaml(prev);
  }, []);
  const redo = useCallback(() => {
    const next = future.current.pop();
    if (next === undefined || current.current === null) return;
    past.current.push(current.current);
    setYaml(next);
  }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== "z") return;
      if ((e.target as HTMLElement).closest("input, textarea, select, .cm-editor")) return;
      e.preventDefault();
      if (e.shiftKey) redo();
      else undo();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [undo, redo]);

  // Server findings, placed on the block their YAML position falls in
  const { findings, all, marks } = useMemo(() => {
    const findings: Findings = new Map();
    const all: Finding[] = [];
    const marks: Mark[] = [];
    if (!validation || yaml === null) return { findings, all, marks };
    for (const [severity, list] of [["error", validation.errors], ["warning", validation.warnings]] as const) {
      for (const p of list) {
        marks.push({ line: p.line, column: p.column, message: p.message, severity });
        const key = p.line > 0 ? locate(yaml, p.line, p.column) : null;
        all.push({ severity, message: p.message, line: p.line, key });
        if (key && !key.startsWith("hook:")) {
          const f = findings.get(key) ?? { errors: [], warnings: [] };
          (severity === "error" ? f.errors : f.warnings).push(p.message);
          findings.set(key, f);
        }
      }
    }
    return { findings, all, marks };
  }, [validation, yaml]);

  const keys = useMemo(() => (doc ? visitKeys(doc) : new Map<string, string>()), [doc]);
  // A path picked in the Paths tab wins over the preview run
  const visited = useMemo(
    () => (path ? new Set(path.visits) : previewing && run ? new Set(run.visits) : null),
    [path, previewing, run],
  );

  const publish = async () => {
    if (yaml === null) return;
    try {
      const { status, body } = await api<Published & Validation>(`tenants/${tenant}/publish`, { method: "POST", yaml });
      if (status === 201) {
        notify("ok", `Published ${body.versionHash.slice(7, 15)}`);
        setPublished((n) => n + 1);
        refreshLive();
      } else {
        setValidation(body);
        notify("error", "Not published: the flow has errors");
      }
    } catch (e) {
      fail(e);
    }
  };

  const addBlock = (name: string) => {
    if (!doc) return;
    const { list, index } = addTarget(doc, selected);
    edit(insert(doc, list, index, fromBlock(block(name), list.kind, doc)));
    setSelected(addressKey({ section: list.section, path: [...list.owner, [list.kind, index]] }));
  };

  const jump = (f: Finding) => {
    setDrawer(false);
    if (f.key && !f.key.startsWith("hook:") && doc) {
      setSelected(f.key);
      setTab("Blocks");
    } else if (f.key?.startsWith("hook:")) setTab("Hooks");
    else setTab("YAML");
  };

  if (signedOut) {
    return (
      <div className="grid min-h-svh place-items-center">
        <p className="text-muted-foreground">
          Session ended.{" "}
          <a className="text-foreground underline underline-offset-4" href="/signin">
            Sign in
          </a>
        </p>
      </div>
    );
  }

  const errors = validation?.errors.length ?? 0;
  const warnings = validation?.warnings.length ?? 0;
  const successes = validation?.paths.filter((p) => p.succeeds).length ?? 0;
  const blocked = !validation ? "Checking…" : errors > 0 ? `${errors} error${errors > 1 ? "s" : ""} to fix` : null;

  return (
    <div className="grid h-svh grid-rows-[48px_40px_1fr_28px] bg-background">
      <header className="flex items-center gap-3 border-b px-4">
        <Link href="/" aria-label="All tenants">
          <Wordmark />
        </Link>
        <span className="text-border">/</span>
        <TenantSwitcher tenant={tenant} tenants={tenants} />
        {doc && (
          <>
            <span className="text-border">/</span>
            <input
              aria-label="Flow id"
              spellCheck={false}
              className="field-sizing-content h-7 min-w-16 rounded-md border border-transparent bg-transparent px-1.5 font-mono text-[13px] hover:border-input focus:border-ring focus:outline-none"
              value={doc.flow}
              onChange={(e) => edit({ ...doc, flow: e.target.value })}
            />
            <label className="flex h-7 items-center gap-1 rounded-md border border-input text-xs text-muted-foreground focus-within:border-ring">
              <span className="pl-2">version</span>
              <input
                aria-label="Flow version"
                type="number"
                min={1}
                className="w-10 bg-transparent pr-1 font-mono text-foreground outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none"
                value={doc.version}
                onChange={(e) => edit({ ...doc, version: Math.max(1, Number(e.target.value) || 1) })}
              />
            </label>
          </>
        )}
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
          <span className={cn("size-1.5 rounded-full", saved === "failed" ? "bg-danger" : saved === "saving" ? "bg-warning" : "bg-success")} />
          {saved === "saving" ? "Saving…" : saved === "failed" ? "Not saved" : origin === "draft" ? "Draft saved" : "No changes"}
        </span>
        <div className="ml-auto flex items-center gap-3">
          {live && (
            <span className="hidden items-center gap-1.5 font-mono text-xs text-muted-foreground md:flex" title={live}>
              <span className="size-1.5 rounded-full bg-success" />
              live {live.slice(7, 15)}
            </span>
          )}
          <Button
            variant="outline"
            aria-pressed={previewing}
            onClick={() => {
              setPreviewing((p) => !p);
              setTab("Blocks");
            }}
            className={cn("h-8 gap-1.5 px-3", previewing && "border-accent-ui text-accent-ui")}
          >
            <Play className="size-3.5" />
            Preview
          </Button>
          <span title={blocked ?? "Publish this draft as a new version"}>
            <Button onClick={publish} disabled={blocked !== null} className="h-8 px-3">
              Publish
            </Button>
          </span>
          <UserMenu subject={subject} />
        </div>
      </header>

      <nav role="tablist" aria-label="Views" className="flex items-stretch gap-1 border-b px-3">
        {[...FLOW_TABS, null, ...TENANT_TABS].map((t) =>
          t === null ? (
            <span key="gap" className="flex-1" />
          ) : (
            <button
              key={t.tab}
              type="button"
              role="tab"
              aria-selected={tab === t.tab}
              onClick={() => setTab(t.tab)}
              className={cn(
                "relative flex items-center gap-1.5 px-3 text-[13px] transition-colors",
                tab === t.tab ? "font-medium text-foreground after:absolute after:inset-x-2 after:-bottom-px after:h-0.5 after:bg-foreground" : "text-muted-foreground hover:text-foreground",
              )}
            >
              <t.icon className="size-4 opacity-60" strokeWidth={1.75} />
              {t.tab}
              {t.tab === "Paths" && validation && <span className="font-mono text-xs text-muted-foreground">{validation.paths.length}</span>}
              {t.tab === "Hooks" && doc && doc.hooks.length > 0 && <span className="font-mono text-xs text-muted-foreground">{doc.hooks.length}</span>}
            </button>
          ),
        )}
      </nav>

      <main className="relative min-h-0 overflow-hidden">
        {yaml === null || !parsed ? (
          <div className="p-6 text-muted-foreground">Loading…</div>
        ) : !doc && (tab === "Blocks" || tab === "Graph" || tab === "Hooks") ? (
          <div className="flex items-center gap-2 p-6">
            <CircleX className="size-4 text-danger" />
            <span className="font-mono text-xs">{"error" in parsed ? parsed.error : ""}</span>
            <Button variant="outline" className="ml-2 h-7 px-2 text-xs" onClick={() => setTab("YAML")}>
              Open YAML
            </Button>
          </div>
        ) : (
          <>
            {tab === "Blocks" && doc && (
              <div className="grid h-full grid-cols-[220px_1fr_340px]">
                <Palette onAdd={addBlock} setDragging={setDragging} />
                <Tree
                  doc={doc}
                  onEdit={edit}
                  selected={selected}
                  onSelect={setSelected}
                  findings={findings}
                  visited={visited}
                  visitKeys={keys}
                  waiting={previewing && !path ? (run?.waiting ?? null) : null}
                  dragging={dragging}
                  setDragging={setDragging}
                />
                <aside className="min-h-0 overflow-auto border-l">
                  {previewing ? (
                    <Preview
                      tenant={tenant}
                      demoClient={demoClient}
                      blocked={blocked ?? (saved === "saving" ? "Saving the draft…" : saved === "failed" ? "The draft is not saved" : null)}
                      run={run}
                      onEvent={onTrace}
                      fail={fail}
                    />
                  ) : selected && !selected.startsWith(LIST) && doc && parseAddress(selected).path.length > 0 ? (
                    <Inspector
                      doc={doc}
                      selected={selected}
                      onEdit={edit}
                      onSelect={setSelected}
                      onDeleted={(id) => notify("ok", `Deleted ${id}`, true)}
                      catalog={catalog}
                      findings={findings.get(selected)}
                    />
                  ) : (
                    <div className="grid gap-3 p-4 text-muted-foreground">
                      <span>{selected?.startsWith(LIST) ? "Palette blocks go to the end of this list" : "Select a block"}</span>
                      <span className="flex flex-wrap gap-x-3 gap-y-1 text-xs">
                        <span>
                          <kbd>↑</kbd> <kbd>↓</kbd> select
                        </span>
                        <span>
                          <kbd>⌘↑</kbd> <kbd>⌘↓</kbd> move
                        </span>
                        <span>
                          <kbd>⌫</kbd> delete
                        </span>
                        <span>
                          <kbd>⌘Z</kbd> undo
                        </span>
                      </span>
                    </div>
                  )}
                </aside>
              </div>
            )}
            {tab === "YAML" && <CodeEditor value={yaml} onChange={setYaml} language="yaml" marks={marks} label="Flow YAML" className="h-full" />}
            {tab === "Graph" && doc && (
              <Graph
                doc={doc}
                visited={visited}
                visitKeys={keys}
                onSelect={(key) => {
                  setSelected(key);
                  setTab("Blocks");
                }}
              />
            )}
            {tab === "Paths" && <Paths validation={validation} selected={path} onSelect={setPath} onShow={() => setTab("Blocks")} />}
            {tab === "Hooks" && doc && (
              <Hooks tenant={tenant} doc={doc} onEdit={edit} files={files} granted={settings?.egress ?? []} available={catalog?.egress ?? []} />
            )}
            {tab === "Versions" && (
              <Versions
                tenant={tenant}
                draft={yaml}
                onLoad={(y) => {
                  commit(y);
                  notify("ok", "Loaded into the draft", true);
                }}
                refresh={published}
                notify={notify}
                onRollback={refreshLive}
              />
            )}
            {tab === "Settings" && settings && <Settings key={JSON.stringify(settings)} tenant={tenant} view={settings} catalog={catalog} onSaved={setSettings} notify={notify} />}
          </>
        )}

        {drawer && all.length > 0 && (
          <div className="absolute inset-x-0 bottom-0 z-20 max-h-60 overflow-auto border-t bg-background shadow-[0_-4px_12px_rgb(0_0_0/0.06)]">
            <div className="sticky top-0 flex h-8 items-center border-b bg-surface-2 px-3 text-xs font-medium">
              Problems
              <button type="button" aria-label="Close problems" className="ml-auto text-muted-foreground hover:text-foreground" onClick={() => setDrawer(false)}>
                <X className="size-3.5" />
              </button>
            </div>
            <ul>
              {all.map((f, i) => (
                <li key={i}>
                  <button type="button" onClick={() => jump(f)} className="flex w-full items-start gap-2 px-3 py-1.5 text-left text-xs hover:bg-accent">
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
          </div>
        )}

        <div className="pointer-events-none absolute top-3 right-4 z-30 grid gap-2" aria-live="polite">
          {toasts.map((t) => (
            <div
              key={t.id}
              role={t.kind === "error" ? "alert" : "status"}
              className="pointer-events-auto flex max-w-sm items-center gap-2 rounded-lg border bg-background px-3 py-2 text-[13px] shadow-[0_1px_2px_rgb(0_0_0/0.06),0_4px_12px_rgb(0_0_0/0.08)]"
            >
              {t.kind === "ok" ? <CircleCheck className="size-4 shrink-0 text-success" /> : <CircleX className="size-4 shrink-0 text-danger" />}
              {t.text}
              {t.undo && (
                <button
                  type="button"
                  className="ml-2 font-medium text-accent-ui hover:underline"
                  onClick={() => {
                    undo();
                    setToasts((all) => all.filter((x) => x.id !== t.id));
                  }}
                >
                  Undo
                </button>
              )}
            </div>
          ))}
        </div>
      </main>

      <footer className="flex min-w-0 items-center gap-4 border-t bg-surface-2 px-3 font-mono text-xs whitespace-nowrap text-muted-foreground" aria-live="polite">
        {!validation ? (
          <span>checking…</span>
        ) : (
          <>
            <button type="button" onClick={() => setDrawer((d) => !d)} disabled={all.length === 0} className="flex shrink-0 items-center gap-3 enabled:hover:text-foreground">
              <span className="flex items-center gap-1.5">
                <span className={cn("size-1.5 rounded-full", errors > 0 ? "bg-danger" : "bg-success")} />
                <span className={errors > 0 ? "text-danger" : ""}>{plural(errors, "error")}</span>
              </span>
              <span className="flex items-center gap-1.5">
                <span className={cn("size-1.5 rounded-full", warnings > 0 ? "bg-warning" : "bg-border")} />
                {plural(warnings, "warning")}
              </span>
            </button>
            <span className="shrink-0">
              {plural(validation.paths.length, "path")} · {successes} succeed · {validation.paths.length - successes} fail
            </span>
            <span className="hidden min-w-0 truncate xl:inline">{validation.summary}</span>
            {path && (
              <button
                type="button"
                onClick={() => setPath(null)}
                className="ml-auto flex h-5 max-w-[40%] min-w-0 shrink items-center gap-1 rounded-[4px] bg-accent-ui/10 px-1.5 text-accent-ui hover:bg-accent-ui/15"
              >
                <span className="truncate">{path.path}</span>
                <X className="size-3" />
              </button>
            )}
          </>
        )}
      </footer>
    </div>
  );
}

function TenantSwitcher({ tenant, tenants }: { tenant: string; tenants: string[] }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        className="flex h-7 items-center gap-1 rounded-md px-1.5 font-mono text-[13px] font-medium hover:bg-accent"
      >
        {tenant}
        <ChevronsUpDown className="size-3.5 text-muted-foreground" />
      </button>
      {open && (
        <ul role="listbox" className="absolute top-8 left-0 z-40 min-w-44 rounded-lg border bg-background p-1 shadow-[0_1px_2px_rgb(0_0_0/0.06),0_4px_12px_rgb(0_0_0/0.08)]">
          {tenants.map((t) => (
            <li key={t} role="option" aria-selected={t === tenant}>
              <a href={`/tenants/${encodeURIComponent(t)}`} className={cn("flex h-7 items-center rounded-md px-2 font-mono text-[13px] hover:bg-accent", t === tenant && "font-medium")}>
                {t}
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function UserMenu({ subject }: { subject: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button
        type="button"
        aria-label={`Signed in as ${subject}`}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="grid size-6 place-items-center rounded-full bg-muted font-mono text-[11px] font-medium uppercase hover:ring-2 hover:ring-border"
      >
        {subject.replace(/^user-/, "").slice(0, 1)}
      </button>
      {open && (
        <div className="absolute top-8 right-0 z-40 w-52 rounded-lg border bg-background p-1 shadow-[0_1px_2px_rgb(0_0_0/0.06),0_4px_12px_rgb(0_0_0/0.08)]">
          <div className="px-2 py-1.5 font-mono text-xs text-muted-foreground">{subject}</div>
          <form action="/signout" method="post">
            <button type="submit" className="flex h-7 w-full items-center gap-2 rounded-md px-2 text-[13px] hover:bg-accent">
              <LogOut className="size-3.5" />
              Sign out
            </button>
          </form>
        </div>
      )}
    </div>
  );
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
