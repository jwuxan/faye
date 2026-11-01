"use client";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@faye/ui/components/alert-dialog";
import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@faye/ui/components/dialog";
import { Field, FieldDescription, FieldError, FieldLabel } from "@faye/ui/components/field";
import { Input } from "@faye/ui/components/input";
import { Kbd } from "@faye/ui/components/kbd";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@faye/ui/components/resizable";
import { Tabs, TabsList, TabsTrigger } from "@faye/ui/components/tabs";
import { Textarea } from "@faye/ui/components/textarea";
import { useIsMobile } from "@faye/ui/hooks/use-mobile";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, CircleX, FileCode, Play, Plus, SquareCode, Trash } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { api, type HookRun } from "../../../lib/api";
import { type Entry, type FlowDoc, ID, removeHook, setHook } from "../../../lib/flow";
import { HOOK_TEMPLATE, SAMPLE_CTX } from "../../../lib/hook-contract";
import { PageHeader } from "../../shell";
import { denseMono, Empty, Help, IconButton, Overline, Prop, Switch, Tile } from "../../ui";
import { CodeEditor } from "./code-editor";
import { DraftActions, useDraft } from "./draft";
import { StatusBar } from "./workspace";

/**
 * Hooks: tenant JavaScript the flow calls at declared points. Each hook is written here and stored
 * in the flow's draft (hooks.<id>.source), so it is versioned and published with the flow.
 */
export function HooksPage() {
  const d = useDraft();
  const { doc } = d;
  const [chosen, setChosen] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [side, setSide] = useState<"test" | "settings">("test");
  const isMobile = useIsMobile();
  const [pane, setPane] = useState<"list" | "source" | "side">("source");
  const selected = chosen ?? doc?.hooks[0]?.id ?? null;
  const hook = doc?.hooks.find((h) => h.id === selected) ?? null;

  const listPane = doc && (
    <>
      <div className="flex h-10 shrink-0 items-center border-b px-3">
        <span className="text-xs font-medium">Hooks</span>
        <span className="ml-1.5 font-mono text-xs text-muted-foreground">{doc.hooks.length}</span>
        <IconButton icon={Plus} label="New hook" className="ml-auto" onClick={() => setAdding(true)} />
      </div>
      <ul className="grid min-h-0 content-start gap-0.5 overflow-auto p-2" aria-label="Hooks">
        {doc.hooks.map((h) => {
          const used = uses(doc, h.id);
          return (
            <li key={h.id}>
              <button
                type="button"
                aria-current={selected === h.id}
                onClick={() => {
                  setChosen(h.id);
                  setPane("source");
                }}
                className={cn(
                  "grid w-full gap-0.5 rounded-lg px-2 py-1.5 text-left transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
                  selected === h.id ? "bg-sidebar-accent text-sidebar-accent-foreground" : "hover:bg-accent",
                )}
              >
                <span className="flex items-center gap-2">
                  <Tile icon={SquareCode} tone="orange" size="sm" />
                  <span className="truncate font-mono text-sm">{h.id}</span>
                </span>
                <span className="truncate pl-7 text-xs text-muted-foreground">
                  {used.length > 0 ? `used by ${used.join(", ")}` : "not used yet"}
                  {h.file !== undefined && ` · ${h.file}`}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
  const sourcePane = hook && doc && (
    <>
      <div className="flex h-10 shrink-0 items-center gap-2 border-b px-3">
        <span className="font-mono text-sm font-medium">{hook.id}</span>
        <span className="text-xs text-muted-foreground">JavaScript</span>
        {hook.file !== undefined && (
          <Badge variant="outline" className="gap-1 font-mono font-normal text-muted-foreground">
            <FileCode className="size-3" />
            {hook.file}
          </Badge>
        )}
        <span className="ml-auto flex items-center gap-1">
          {hook.file !== undefined && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 px-2.5 text-xs"
              disabled={d.files[hook.file] === undefined}
              onClick={() => d.edit(setHook(doc, { ...hook, file: undefined, source: d.files[hook.file!] ?? "" }))}
            >
              Edit inline
            </Button>
          )}
          <DeleteHook id={hook.id} used={uses(doc, hook.id)} onDelete={() => {
            d.edit(removeHook(doc, hook.id));
            setChosen(null);
            d.notify("ok", `Deleted hook ${hook.id}`, true);
          }} />
        </span>
      </div>
      {hook.file !== undefined ? (
        <>
          <p className="border-b bg-surface-2 px-3 py-1.5 text-xs text-muted-foreground">Read only: this hook comes from a file next to the configured flow. Edit inline to change it here.</p>
          <CodeEditor key={`${hook.id}-file`} language="javascript" value={d.files[hook.file] ?? ""} readOnly label={`${hook.id} source`} className="min-h-0 flex-1" />
        </>
      ) : (
        <CodeEditor
          key={hook.id}
          language="javascript"
          value={hook.source ?? ""}
          onChange={(source) => d.edit(setHook(doc, { ...hook, source }))}
          label={`${hook.id} source`}
          className="min-h-0 flex-1"
        />
      )}
    </>
  );
  const sidePane = hook && doc && (
    <Tabs value={side} onValueChange={(v) => setSide(v as "test" | "settings")} className="flex min-h-0 flex-1 flex-col gap-0">
      <div className="flex h-10 shrink-0 items-center border-b px-2">
        <TabsList className="p-0.5 group-data-[orientation=horizontal]/tabs:h-7">
          <TabsTrigger value="test" className="h-6 px-2.5 text-xs">
            Test run
          </TabsTrigger>
          <TabsTrigger value="settings" className="h-6 px-2.5 text-xs">
            Capabilities
          </TabsTrigger>
        </TabsList>
      </div>
      <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
        {side === "settings" ? (
          <Capabilities doc={doc} hookId={hook.id} granted={d.settings?.egress ?? []} available={d.catalog?.egress ?? []} />
        ) : (
          <TestRun
            key={hook.id}
            tenant={d.tenant}
            source={hook.file !== undefined ? (d.files[hook.file] ?? "") : (hook.source ?? "")}
            http={hook.http}
            timeoutMs={hook.timeoutMs}
            picks={pickedBy(doc, hook.id)}
          />
        )}
      </div>
    </Tabs>
  );
  const emptyPane = (
    <Empty icon={SquareCode} title="No hooks yet">
      <p className="max-w-xs">Hooks score risk, route between options, or enrich a sign in. They can set values, fail a step, or pick an option, never sign someone in.</p>
      <Button size="sm" className="mt-2" onClick={() => setAdding(true)}>
        <Plus />
        New hook
      </Button>
    </Empty>
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={d.tenant} title="Hooks">
        <DraftActions />
      </PageHeader>
      {d.yaml === null ? (
        <div className="p-6 text-muted-foreground">Loading…</div>
      ) : !doc ? (
        <div className="grid gap-3 p-6">
          <p className="flex items-start gap-2">
            <CircleX className="mt-0.5 size-4 shrink-0 text-danger" />
            <span className="font-mono text-xs">The flow YAML does not parse: {d.parseError}</span>
          </p>
          <Button asChild variant="outline" size="sm" className="justify-self-start">
            <Link href={`/tenants/${encodeURIComponent(d.tenant)}/flow`}>Open the flow</Link>
          </Button>
        </div>
      ) : isMobile ? (
        // A phone has room for one pane: pick it here
        <Tabs value={hook ? pane : "list"} onValueChange={(v) => setPane(v as typeof pane)} className="flex min-h-0 flex-1 flex-col gap-0">
          <div className="flex h-10 shrink-0 items-center border-b bg-surface-2 px-2">
            <TabsList className="w-full p-0.5 group-data-[orientation=horizontal]/tabs:h-8">
              <TabsTrigger value="list" className="h-7 text-xs">
                Hooks
              </TabsTrigger>
              <TabsTrigger value="source" disabled={!hook} className="h-7 text-xs">
                Source
              </TabsTrigger>
              <TabsTrigger value="side" disabled={!hook} className="h-7 text-xs">
                Test
              </TabsTrigger>
            </TabsList>
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            {!hook ? (
              doc.hooks.length > 0 ? (
                <div className="flex min-h-0 flex-1 flex-col bg-surface-2">{listPane}</div>
              ) : (
                emptyPane
              )
            ) : pane === "list" ? (
              <div className="flex min-h-0 flex-1 flex-col bg-surface-2">{listPane}</div>
            ) : pane === "source" ? (
              sourcePane
            ) : (
              sidePane
            )}
          </div>
        </Tabs>
      ) : (
        <ResizablePanelGroup orientation="horizontal" className="min-h-0 flex-1">
          <ResizablePanel id="list" defaultSize={232} minSize={180} maxSize={360} className="flex min-w-0 flex-col bg-surface-2">
            {listPane}
          </ResizablePanel>
          <ResizableHandle />
          {hook ? (
            <>
              <ResizablePanel id="source" defaultSize="55" minSize="30" className="flex min-w-0 flex-col">
                {sourcePane}
              </ResizablePanel>
              <ResizableHandle />
              <ResizablePanel id="side" defaultSize="30" minSize={300} className="flex min-w-0 flex-col">
                {sidePane}
              </ResizablePanel>
            </>
          ) : (
            <ResizablePanel id="empty" className="min-w-0">
              {emptyPane}
            </ResizablePanel>
          )}
        </ResizablePanelGroup>
      )}
      <StatusBar onJump={() => undefined} />
      {doc && (
        <NewHook
          open={adding}
          onOpenChange={setAdding}
          taken={(id) => doc.hooks.some((h) => h.id === id)}
          onCreate={(id) => {
            d.edit(setHook(doc, { id, source: HOOK_TEMPLATE, http: [] }));
            setChosen(id);
            setAdding(false);
          }}
        />
      )}
    </div>
  );
}

function NewHook({ open, onOpenChange, taken, onCreate }: { open: boolean; onOpenChange: (o: boolean) => void; taken: (id: string) => boolean; onCreate: (id: string) => void }) {
  const [id, setId] = useState("");
  const problem = id === "" ? null : !ID.test(id) ? "Start with a letter; use a to z, 0 to 9, - and _" : taken(id) ? "A hook with this id exists" : null;
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) setId("");
      }}
    >
      <DialogContent className="sm:max-w-md">
        <form
          className="grid gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (id && !problem) {
              onCreate(id);
              setId("");
            }
          }}
        >
          <DialogHeader>
            <DialogTitle>New hook</DialogTitle>
            <DialogDescription>Starts from a template that sets a value. Use it from a block’s Hook field, or as the picker of a choose.</DialogDescription>
          </DialogHeader>
          <Field data-invalid={problem !== null}>
            <FieldLabel htmlFor="hook-id">Id</FieldLabel>
            <Input id="hook-id" autoFocus spellCheck={false} className="font-mono" placeholder="risk" value={id} aria-invalid={problem !== null} onChange={(e) => setId(e.target.value)} />
            {problem ? <FieldError>{problem}</FieldError> : <FieldDescription>How blocks refer to this hook in the flow.</FieldDescription>}
          </Field>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!id || problem !== null}>
              Create hook
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function DeleteHook({ id, used, onDelete }: { id: string; used: string[]; onDelete: () => void }) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="Delete hook" className="size-7 text-muted-foreground hover:text-danger">
          <Trash />
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete hook {id}?</AlertDialogTitle>
          <AlertDialogDescription>
            {used.length > 0
              ? `${used.join(", ")} still ${used.length === 1 ? "uses" : "use"} it; the checker will report ${used.length === 1 ? "that block" : "those blocks"} until you change them. `
              : ""}
            The draft keeps your undo history, so ⌘Z brings it back.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onDelete}>
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Which named HTTP targets the hook may call, and its deadline. */
function Capabilities({ doc, hookId, granted, available }: { doc: FlowDoc; hookId: string; granted: string[]; available: string[] }) {
  const { edit } = useDraft();
  const hook = doc.hooks.find((h) => h.id === hookId)!;
  const targets = [...new Set([...available, ...hook.http])];
  return (
    <div className="grid content-start gap-2.5 px-3 pt-3 pb-4">
      <div className="flex items-center gap-1">
        <Overline>HTTP egress</Overline>
        <Help>Hooks run sandboxed with no network. Each switch lets this hook call one named target the operator granted to the tenant; nothing else is reachable.</Help>
      </div>
      {targets.map((t) => {
        const ok = granted.includes(t);
        return (
          <Prop key={t} label={t}>
            <Switch
              label={`May call ${t}`}
              checked={hook.http.includes(t)}
              disabled={!ok && !hook.http.includes(t)}
              onChange={(on) => edit(setHook(doc, { ...hook, http: on ? [...hook.http, t] : hook.http.filter((x) => x !== t) }))}
            />
            {!ok && <span className="text-xs text-muted-foreground">not granted to this tenant</span>}
          </Prop>
        );
      })}
      {targets.length === 0 && <p className="text-xs text-muted-foreground">The server has no egress targets.</p>}
      <p className="text-xs text-muted-foreground">
        The operator grants targets per tenant in the server config (<code className="font-mono">faye.tenants.&lt;id&gt;.egress</code>). A hook calls them with{" "}
        <code className="font-mono">cap.http</code>.
      </p>
      <div className="flex items-center gap-1 pt-2">
        <Overline>Deadline</Overline>
        <Help>The hook is stopped when this runs out, egress calls included. 200 ms unless set; at most 2000 ms.</Help>
      </div>
      <Prop label="Timeout">
        <Input
          type="number"
          min={1}
          max={2000}
          aria-label="Timeout in ms"
          className={cn(denseMono, "w-20")}
          value={hook.timeoutMs ?? 200}
          onChange={(e) => {
            const ms = Math.min(2000, Math.max(1, Number(e.target.value) || 200));
            edit(setHook(doc, { ...hook, timeoutMs: ms === 200 ? undefined : ms }));
          }}
        />
        <span className="text-xs text-muted-foreground">ms, egress included</span>
      </Prop>
    </div>
  );
}

/** Ids of the steps and options that run or pick with a hook. */
function uses(doc: FlowDoc, id: string): string[] {
  const out: string[] = [];
  const walk = (list: Entry[] | undefined) =>
    (list ?? []).forEach((e) => {
      if (e.body.before === id || e.body.after === id || e.body.pick === id) out.push(e.id);
      walk(e.body.alternative);
      walk(e.body.steps);
    });
  walk(doc.login);
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
    <section className="grid min-w-0 content-start gap-3 px-3 pt-3 pb-4" aria-label="Test run">
      <div className="flex items-center gap-2">
        <p className="text-xs text-muted-foreground">Runs on the server, in the same sandbox as a published hook.</p>
        <Button onClick={test} disabled={busy} size="sm" className="ml-auto h-7 px-2.5 text-xs">
          <Play />
          {busy ? "Running" : "Run"}
          <Kbd className="h-4 bg-primary-foreground/15 text-xs text-primary-foreground">⌘↵</Kbd>
        </Button>
      </div>
      {run && (
        <div className="grid min-w-0 animate-in fade-in-0 slide-in-from-top-2 overflow-hidden rounded-md border" aria-live="polite">
          <div className={cn("flex items-center gap-2 border-b px-2 py-1.5", run.accepted ? "bg-success/8" : "bg-danger/8")}>
            {run.accepted ? <CircleCheck className="size-3.5 text-success" /> : <CircleX className="size-3.5 text-danger" />}
            <span className="font-mono text-xs">{run.effect}</span>
            <span className="ml-auto font-mono text-xs text-muted-foreground">{run.millis} ms</span>
          </div>
          {run.error && <p className="px-2 py-1.5 font-mono text-xs text-danger">{run.error}</p>}
          {run.result !== null && <pre className="px-2 py-1.5 font-mono text-xs leading-4 break-all whitespace-pre-wrap">{JSON.stringify(run.result, null, 2)}</pre>}
          {run.logs.length > 0 && (
            <pre className="border-t bg-surface-2 px-2 py-1.5 font-mono text-xs leading-4 break-all whitespace-pre-wrap text-muted-foreground" aria-label="Logs">
              {run.logs.join("\n")}
            </pre>
          )}
          {!run.error && run.result === null && run.logs.length === 0 && <span className="px-2 py-1.5 text-xs text-muted-foreground">no output</span>}
        </div>
      )}
      {problem && (
        <p className="flex gap-2 rounded-md bg-danger/8 px-2 py-1.5 text-xs" role="alert">
          <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
          {problem}
        </p>
      )}
      <div className="flex items-center gap-1 pt-1">
        <Overline>ctx</Overline>
        <Help>The input the hook receives in a test run, as JSON. Edit it to try other users or requests; ⌘↵ runs the test.</Help>
      </div>
      <Textarea
        aria-label="Test ctx"
        spellCheck={false}
        className="h-64 resize-y bg-surface-2 font-mono text-xs leading-4 md:text-xs"
        value={ctx}
        onChange={(e) => setCtx(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && (e.metaKey || e.ctrlKey) && test()}
      />
      <Prop label="Picks from">
        <Switch label="As the hook of a choose" checked={asPicker} onChange={setAsPicker} />
        <Input
          aria-label="Enabled options"
          className={cn(denseMono, "min-w-0 flex-1")}
          disabled={!asPicker}
          value={options}
          onChange={(e) => setOptions(e.target.value)}
          placeholder="totp, enroll"
        />
      </Prop>
    </section>
  );
}
