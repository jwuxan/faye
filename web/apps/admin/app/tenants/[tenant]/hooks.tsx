"use client";

import { Button } from "@faye/ui/components/button";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, CircleX, FileCode, Play, Plus, SquareCode, Trash } from "lucide-react";
import { useState } from "react";
import { api, type HookRun } from "../../../lib/api";
import { type Entry, type FlowDoc, ID, removeHook, setHook } from "../../../lib/flow";
import { HOOK_TEMPLATE, SAMPLE_CTX } from "../../../lib/hook-contract";
import { Empty, field, IconButton, Prop, Switch } from "../../ui";
import { CodeEditor } from "./code-editor";

/** Hooks declared in the flow: source, capabilities, timeout, and server side test runs. */
export function Hooks({
  tenant,
  doc,
  onEdit,
  files,
  granted,
  available,
}: {
  tenant: string;
  doc: FlowDoc;
  onEdit: (doc: FlowDoc) => void;
  /** Hook files next to the configured flow, by name. */
  files: Record<string, string>;
  /** Egress targets the tenant grants its hooks. */
  granted: string[];
  /** Every egress target the server knows. */
  available: string[];
}) {
  const [selected, setSelected] = useState<string | null>(doc.hooks[0]?.id ?? null);
  const [newId, setNewId] = useState("");
  const [adding, setAdding] = useState(false);
  const hook = doc.hooks.find((h) => h.id === selected) ?? null;

  const add = () => {
    if (!ID.test(newId) || doc.hooks.some((h) => h.id === newId)) return;
    onEdit(setHook(doc, { id: newId, source: HOOK_TEMPLATE, http: [] }));
    setSelected(newId);
    setNewId("");
    setAdding(false);
  };

  return (
    <div className="grid h-full grid-cols-[220px_1fr_360px]">
      <div className="flex min-h-0 flex-col border-r bg-surface-2">
        <div className="flex h-11 items-center border-b px-3">
          <span className="text-[13px] font-medium">Hooks</span>
          <IconButton icon={Plus} label="New hook" className="ml-auto" onClick={() => setAdding(true)} />
        </div>
        <ul className="grid gap-px p-2" aria-label="Hooks">
          {adding && (
            <li>
              <input
                autoFocus
                aria-label="New hook id"
                aria-invalid={newId !== "" && (!ID.test(newId) || doc.hooks.some((h) => h.id === newId))}
                placeholder="hook id, then Enter"
                className={cn(field, "w-full font-mono text-xs")}
                value={newId}
                onChange={(e) => setNewId(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") add();
                  if (e.key === "Escape") setAdding(false);
                }}
                onBlur={() => !newId && setAdding(false)}
              />
            </li>
          )}
          {doc.hooks.map((h) => (
            <li key={h.id}>
              <button
                type="button"
                onClick={() => setSelected(h.id)}
                className={cn(
                  "flex h-8 w-full items-center gap-2 rounded-md px-2 text-left",
                  selected === h.id ? "bg-background shadow-xs ring-1 ring-border" : "hover:bg-accent",
                )}
              >
                <SquareCode className="size-4 text-warm" strokeWidth={1.75} />
                <span className="font-mono text-[13px]">{h.id}</span>
                <span className="ml-auto font-mono text-[11px] text-muted-foreground">{uses(doc, h.id).length || ""}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>

      {hook ? (
        <>
          <div className="flex min-h-0 flex-col">
            <div className="flex h-11 shrink-0 items-center gap-2 border-b px-3">
              <span className="font-mono text-[13px] font-medium">{hook.id}</span>
              <span className="text-xs text-muted-foreground">
                {uses(doc, hook.id).length > 0 ? `used by ${uses(doc, hook.id).join(", ")}` : "unused"}
              </span>
              <span className="ml-auto flex items-center gap-1">
                {hook.file !== undefined && (
                  <>
                    <span className="flex items-center gap-1 font-mono text-xs text-muted-foreground">
                      <FileCode className="size-3.5" />
                      {hook.file}
                    </span>
                    <Button
                      variant="outline"
                      className="ml-2 h-7 px-2.5 text-xs"
                      disabled={files[hook.file] === undefined}
                      onClick={() => onEdit(setHook(doc, { ...hook, file: undefined, source: files[hook.file!] ?? "" }))}
                    >
                      Edit inline
                    </Button>
                  </>
                )}
                <IconButton
                  icon={Trash}
                  label="Delete hook"
                  className="hover:text-danger"
                  onClick={() => {
                    onEdit(removeHook(doc, hook.id));
                    setSelected(null);
                  }}
                />
              </span>
            </div>
            {hook.file !== undefined ? (
              <CodeEditor key={`${hook.id}-file`} language="javascript" value={files[hook.file] ?? ""} readOnly label={`${hook.id} source`} className="min-h-0 flex-1" />
            ) : (
              <CodeEditor
                key={hook.id}
                language="javascript"
                value={hook.source ?? ""}
                onChange={(source) => onEdit(setHook(doc, { ...hook, source }))}
                label={`${hook.id} source`}
                className="min-h-0 flex-1"
              />
            )}
          </div>
          <aside className="min-h-0 min-w-0 overflow-x-hidden overflow-y-auto border-l">
            <section className="grid gap-2.5 border-b px-3 pt-3 pb-4">
              <h4 className="text-[11px] font-medium tracking-[0.04em] text-muted-foreground uppercase">Capabilities</h4>
              {[...new Set([...available, ...hook.http])].map((t) => {
                const ok = granted.includes(t);
                return (
                  <Prop key={t} label="HTTP">
                    <Switch
                      label={`May call ${t}`}
                      checked={hook.http.includes(t)}
                      disabled={!ok && !hook.http.includes(t)}
                      onChange={(on) => onEdit(setHook(doc, { ...hook, http: on ? [...hook.http, t] : hook.http.filter((x) => x !== t) }))}
                    />
                    <span className={cn("font-mono text-xs", !ok && "text-muted-foreground")}>{t}</span>
                    {!ok && (
                      <span className="text-xs text-muted-foreground" title={`The operator grants targets per tenant: faye.tenants.<id>.egress in the server config`}>
                        not granted to this tenant
                      </span>
                    )}
                  </Prop>
                );
              })}
              {available.length === 0 && hook.http.length === 0 && (
                <Prop label="HTTP">
                  <span className="text-xs text-muted-foreground">the server has no egress targets</span>
                </Prop>
              )}
              <Prop label="Timeout">
                <input
                  type="number"
                  min={1}
                  max={2000}
                  aria-label="Timeout in ms"
                  className={cn(field, "w-20")}
                  value={hook.timeoutMs ?? 200}
                  onChange={(e) => {
                    const ms = Math.min(2000, Math.max(1, Number(e.target.value) || 200));
                    onEdit(setHook(doc, { ...hook, timeoutMs: ms === 200 ? undefined : ms }));
                  }}
                />
                <span className="text-xs text-muted-foreground">ms</span>
              </Prop>
            </section>
            <TestRun
              key={hook.id}
              tenant={tenant}
              source={hook.file !== undefined ? (files[hook.file] ?? "") : (hook.source ?? "")}
              http={hook.http}
              timeoutMs={hook.timeoutMs}
              picks={pickedBy(doc, hook.id)}
            />
          </aside>
        </>
      ) : (
        <div className="col-span-2">
          <Empty icon={SquareCode} title="No hook selected">
            <Button variant="outline" className="mt-2 h-7 px-2.5 text-xs" onClick={() => setAdding(true)}>
              <Plus className="size-3.5" />
              New hook
            </Button>
          </Empty>
        </div>
      )}
    </div>
  );
}

/** Ids of the steps and options that run or pick with a hook. */
function uses(doc: FlowDoc, id: string): string[] {
  const out: string[] = [];
  const walk = (list: Entry[] | undefined) =>
    (list ?? []).forEach((e) => {
      if (e.body.hook === id || e.body.pick === id) out.push(e.id);
      walk(e.body.alternative);
      walk(e.body.steps);
    });
  walk(doc.login);
  walk(doc.consent);
  return out;
}

/** The option ids of a choose this hook picks for, or null if it picks for none. */
function pickedBy(doc: FlowDoc, id: string): string[] | null {
  let found: string[] | null = null;
  const walk = (list: Entry[] | undefined) =>
    (list ?? []).forEach((e) => {
      if (e.body.pick === id && e.body.alternative) found = e.body.alternative.map((o) => o.id);
      walk(e.body.alternative);
      walk(e.body.steps);
    });
  walk(doc.login);
  walk(doc.consent);
  return found;
}

function TestRun({ tenant, source, http, timeoutMs, picks }: { tenant: string; source: string; http: string[]; timeoutMs?: number; picks: string[] | null }) {
  const [ctx, setCtx] = useState(JSON.stringify(SAMPLE_CTX, null, 2));
  const [asPicker, setAsPicker] = useState(picks !== null);
  const [options, setOptions] = useState((picks ?? []).join(", "));
  const [run, setRun] = useState<HookRun | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const test = async () => {
    setProblem(null);
    let parsed: unknown;
    try {
      parsed = JSON.parse(ctx);
    } catch (e) {
      setProblem(`ctx: ${(e as Error).message}`);
      return;
    }
    setBusy(true);
    try {
      const { status, body } = await api<HookRun & { error?: string }>(`tenants/${tenant}/hooks/test`, {
        method: "POST",
        json: { source, http, timeoutMs, ctx: parsed, options: asPicker ? options.split(",").map((s) => s.trim()).filter(Boolean) : null },
      });
      if (status === 400) setProblem(body.error ?? "refused");
      else setRun(body);
    } catch (e) {
      setProblem((e as Error).message);
    }
    setBusy(false);
  };

  return (
    <section className="grid min-w-0 gap-2.5 px-3 pt-3 pb-4" aria-label="Test run">
      <div className="flex items-center">
        <h4 className="text-[11px] font-medium tracking-[0.04em] text-muted-foreground uppercase">Test run</h4>
        <Button onClick={test} disabled={busy} className="ml-auto h-7 px-2.5 text-xs" title="Run (⌘↵)">
          <Play className="size-3.5" />
          {busy ? "Running" : "Run"}
        </Button>
      </div>
      <textarea
        aria-label="Test ctx"
        spellCheck={false}
        className="h-56 w-full resize-y rounded-md border bg-surface-2 p-2 font-mono text-[11px] leading-4 outline-none focus:border-ring"
        value={ctx}
        onChange={(e) => setCtx(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && (e.metaKey || e.ctrlKey) && test()}
      />
      <Prop label="Picks from">
        <Switch label="As the hook of a choose" checked={asPicker} onChange={setAsPicker} />
        <input aria-label="Enabled options" className={cn(field, "min-w-0 flex-1 font-mono text-xs")} disabled={!asPicker} value={options} onChange={(e) => setOptions(e.target.value)} placeholder="totp, enroll" />
      </Prop>
      {problem && (
        <p className="flex gap-2 rounded-md bg-danger/8 px-2 py-1.5 text-xs" role="alert">
          <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
          {problem}
        </p>
      )}
      {run && (
        <div className="grid min-w-0 gap-2 overflow-hidden rounded-md border" aria-live="polite">
          <div className="flex items-center gap-2 border-b px-2 py-1.5">
            {run.accepted ? <CircleCheck className="size-3.5 text-success" /> : <CircleX className="size-3.5 text-danger" />}
            <span className="font-mono text-xs">{run.effect}</span>
            <span className="ml-auto font-mono text-[11px] text-muted-foreground">{run.millis} ms</span>
          </div>
          {run.error && <p className="px-2 font-mono text-xs text-danger">{run.error}</p>}
          {run.result !== null && <pre className="px-2 font-mono text-[11px] leading-4 break-all whitespace-pre-wrap">{JSON.stringify(run.result, null, 2)}</pre>}
          {run.logs.length > 0 && (
            <pre className="border-t bg-surface-2 px-2 py-1.5 font-mono text-[11px] leading-4 break-all whitespace-pre-wrap text-muted-foreground" aria-label="Logs">
              {run.logs.join("\n")}
            </pre>
          )}
          {!run.error && run.result === null && run.logs.length === 0 && <span className="px-2 pb-2 text-xs text-muted-foreground">no output</span>}
        </div>
      )}
    </section>
  );
}
