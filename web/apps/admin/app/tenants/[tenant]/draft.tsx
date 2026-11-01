"use client";

import { Button } from "@faye/ui/components/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import { cn } from "@faye/ui/lib/utils";
import { Play } from "lucide-react";
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "@faye/ui/components/sonner";
import { api, type Catalog, type Draft, type Published, type SettingsView, type Validation } from "../../../lib/api";
import { isLegacy, type FlowDoc, fromYaml, toYaml, visitKeys } from "../../../lib/flow";
import { locate } from "../../../lib/locate";
import { follow, type LiveRun, type Recorded } from "../../../lib/trace";
import type { Mark } from "./code-editor";
import type { Findings } from "./tree";

/** Debounce for checking and saving the draft after an edit. */
const QUIET_MS = 400;

export type Finding = { severity: "error" | "warning"; message: string; line: number; key: string | null };

type DraftState = {
  tenant: string;
  demoClient: string;
  yaml: string | null;
  /** Replaces the text as typed in the YAML view, which keeps its own undo. */
  type: (yaml: string) => void;
  /** Replaces the text as one undoable step. */
  commit: (yaml: string) => void;
  edit: (doc: FlowDoc) => void;
  undo: () => void;
  redo: () => void;
  doc: FlowDoc | null;
  parseError: string | null;
  files: Record<string, string>;
  origin: Draft["origin"] | null;
  saved: "saved" | "saving" | "failed";
  validation: Validation | null;
  findings: Findings;
  problems: Finding[];
  marks: Mark[];
  keys: Map<string, string>;
  catalog: Catalog | null;
  settings: SettingsView | null;
  setSettings: (s: SettingsView) => void;
  live: string | null;
  refreshLive: () => void;
  publish: () => Promise<void>;
  published: number;
  /** Why the draft cannot be published or previewed, or null. */
  blocked: string | null;
  previewing: boolean;
  setPreviewing: (on: boolean) => void;
  run: LiveRun | null;
  onTrace: (r: Recorded) => void;
  fail: (e: unknown) => void;
  notify: (kind: "ok" | "error", text: string, undo?: boolean) => void;
};

const Ctx = createContext<DraftState | null>(null);

export function useDraft(): DraftState {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useDraft outside DraftProvider");
  return ctx;
}

/**
 * One tenant's draft, shared by every section of the console. The YAML text is the state: block
 * edits rewrite it, YAML edits replace it, and every change is checked by the server and saved.
 */
export function DraftProvider({ tenant, demoClient, children }: { tenant: string; demoClient: string; children: ReactNode }) {
  const [yaml, setYaml] = useState<string | null>(null);
  const [files, setFiles] = useState<Record<string, string>>({});
  const [origin, setOrigin] = useState<Draft["origin"] | null>(null);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [settings, setSettings] = useState<SettingsView | null>(null);
  const [live, setLive] = useState<string | null>(null);
  const [validation, setValidation] = useState<Validation | null>(null);
  const [saved, setSaved] = useState<"saved" | "saving" | "failed">("saved");
  const [published, setPublished] = useState(0);
  const [previewing, setPreviewing] = useState(false);
  const [run, setRun] = useState<LiveRun | null>(null);
  const onTrace = useCallback((r: Recorded) => setRun((prev) => follow(prev, r)), []);
  const loaded = useRef<string | null>(null);

  const undoRef = useRef<() => void>(() => {});
  const notify = useCallback((kind: "ok" | "error", text: string, undo?: boolean) => {
    const action = undo ? { label: "Undo", onClick: () => undoRef.current() } : undefined;
    if (kind === "ok") toast.success(text, { action });
    else toast.error(text, { action, duration: 8000 });
  }, []);
  const fail = useCallback(
    (e: unknown) => {
      notify("error", (e as Error).message);
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
    // Saved settings (block defaults, policy) change what the same YAML compiles to
  }, [yaml, tenant, fail, settings?.settings]);

  const parsed = useMemo(() => {
    if (yaml === null) return null;
    try {
      return { doc: fromYaml(yaml), error: null };
    } catch (e) {
      return { doc: null, error: (e as Error).message };
    }
  }, [yaml]);
  const doc = parsed?.doc ?? null;

  // Undo history of whole drafts, for block edits and loads (the YAML view keeps its own)
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
    undoRef.current = undo;
  }, [undo]);
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
  const { findings, problems, marks } = useMemo(() => {
    const findings: Findings = new Map();
    const problems: Finding[] = [];
    const marks: Mark[] = [];
    if (!validation || yaml === null) return { findings, problems, marks };
    const legacy = isLegacy(yaml);
    for (const [severity, list] of [["error", validation.errors], ["warning", validation.warnings]] as const) {
      for (const p of list) {
        marks.push({ line: p.line, column: p.column, message: p.message, severity });
        // Old YAML shows as converted blocks, which its lines do not address: the banner speaks for it
        const key = p.line > 0 && !legacy ? locate(yaml, p.line, p.column) : null;
        problems.push({ severity, message: p.message, line: p.line, key });
        if (key && !key.startsWith("hook:")) {
          const f = findings.get(key) ?? { errors: [], warnings: [] };
          (severity === "error" ? f.errors : f.warnings).push(p.message);
          findings.set(key, f);
        }
      }
    }
    return { findings, problems, marks };
  }, [validation, yaml]);

  const keys = useMemo(() => (doc ? visitKeys(doc) : new Map<string, string>()), [doc]);

  const publish = useCallback(async () => {
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
  }, [yaml, tenant, notify, refreshLive, fail]);

  const errors = validation?.errors.length ?? 0;
  const blocked = !validation ? "Checking…" : errors > 0 ? `${errors} error${errors > 1 ? "s" : ""} to fix` : null;

  const value: DraftState = {
    tenant,
    demoClient,
    yaml,
    type: setYaml,
    commit,
    edit,
    undo,
    redo,
    doc,
    parseError: parsed?.error ?? null,
    files,
    origin,
    saved,
    validation,
    findings,
    problems,
    marks,
    keys,
    catalog,
    settings,
    setSettings,
    live,
    refreshLive,
    publish,
    published,
    blocked,
    previewing,
    setPreviewing,
    run,
    onTrace,
    fail,
    notify,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** Save state of the draft, the live version, and Publish: the actions of every page that edits the draft. */
export function DraftActions({ preview = false }: { preview?: boolean }) {
  const { saved, origin, live, blocked, publish, previewing, setPreviewing } = useDraft();
  return (
    <>
      <span className="flex items-center gap-1.5 text-xs whitespace-nowrap text-muted-foreground" role="status">
        <span className={cn("size-1.5 rounded-full transition-colors", saved === "failed" ? "bg-danger" : saved === "saving" ? "bg-warning" : "bg-success")} />
        {/* On a phone the dot alone says it; the words stay for screen readers */}
        <span className={cn(saved !== "failed" && "sr-only sm:not-sr-only")}>
          {saved === "saving" ? "Saving…" : saved === "failed" ? "Not saved" : origin === "draft" ? "Draft saved" : "No changes"}
        </span>
      </span>
      {live && (
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="hidden items-center gap-1.5 rounded-md border px-2 py-0.5 font-mono text-xs text-muted-foreground lg:flex">
              <span className="size-1.5 rounded-full bg-success" />
              live {live.slice(7, 15)}
            </span>
          </TooltipTrigger>
          <TooltipContent className="font-mono">{live}</TooltipContent>
        </Tooltip>
      )}
      {preview && (
        <Button
          variant="outline"
          size="sm"
          aria-pressed={previewing}
          onClick={() => setPreviewing(!previewing)}
          className={cn(previewing && "border-accent-ui text-accent-ui hover:text-accent-ui")}
        >
          <Play />
          <span className="sr-only sm:not-sr-only">Preview</span>
        </Button>
      )}
      <Tooltip>
        <TooltipTrigger asChild>
          <span tabIndex={blocked ? 0 : -1}>
            <Button size="sm" onClick={publish} disabled={blocked !== null}>
              Publish
            </Button>
          </span>
        </TooltipTrigger>
        <TooltipContent>{blocked ?? "Publish this draft as a new version"}</TooltipContent>
      </Tooltip>
    </>
  );
}
