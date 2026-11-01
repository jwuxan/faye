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
import { cn } from "@faye/ui/lib/utils";
import { ArrowRight, Download, GitCompareArrows, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type Validation, type VersionView, type Versions as VersionList } from "../../../lib/api";
import { diffLines } from "../../../lib/diff";
import { highlightLines } from "../../../lib/highlight";
import { PageHeader } from "../../shell";
import { PaneBar } from "../../ui";
import { DraftActions, useDraft } from "./draft";

export function VersionsPage() {
  const d = useDraft();
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={d.tenant} title="Versions">
        <DraftActions />
      </PageHeader>
      <div className="min-h-0 flex-1">
        {d.yaml === null ? (
          <div className="p-6 text-muted-foreground">Loading…</div>
        ) : (
          <Versions
            tenant={d.tenant}
            draft={d.yaml}
            onLoad={(y) => {
              d.commit(y);
              d.notify("ok", "Loaded into draft", true);
            }}
            refresh={d.published}
            notify={d.notify}
            onRollback={d.refreshLive}
          />
        )}
      </div>
    </div>
  );
}

/** Published versions: what changed against the draft, roll back, or start a draft from one. */
export function Versions({
  tenant,
  draft,
  onLoad,
  refresh,
  notify,
  onRollback,
}: {
  tenant: string;
  draft: string;
  onLoad: (yaml: string) => void;
  /** Bumped after a publish, to reload the list. */
  refresh: number;
  notify: (kind: "ok" | "error", text: string) => void;
  onRollback: () => void;
}) {
  const [list, setList] = useState<VersionList | null>(null);
  const [selected, setSelected] = useState<VersionView | null>(null);

  const open = useCallback(
    async (hash: string) => {
      try {
        const { body } = await api<VersionView>(`tenants/${tenant}/versions/${hash}`);
        setSelected(body);
      } catch (e) {
        notify("error", (e as Error).message);
      }
    },
    [tenant, notify],
  );

  useEffect(() => {
    let live = true;
    api<VersionList>(`tenants/${tenant}/versions`)
      .then(async ({ body }) => {
        if (!live) return;
        setList(body);
        if (body.current) {
          const version = await api<VersionView>(`tenants/${tenant}/versions/${body.current}`);
          if (live) setSelected(version.body);
        }
      })
      .catch((e: Error) => notify("error", e.message));
    return () => {
      live = false;
    };
  }, [tenant, refresh, notify]);

  const rollback = async (hash: string) => {
    try {
      const { status, body } = await api<Validation>(`tenants/${tenant}/current`, { method: "POST", json: { versionHash: hash } });
      if (status === 200) notify("ok", `Version ${short(hash)} is live`);
      else notify("error", `Version ${short(hash)} not made live: ${body.errors?.map((e) => e.message).join("; ")}`);
      const { body: next } = await api<VersionList>(`tenants/${tenant}/versions`);
      setList(next);
      await open(hash);
      onRollback();
    } catch (e) {
      notify("error", (e as Error).message);
    }
  };

  // The selected version against the draft, each side highlighted as a whole so multi line values color right
  const diff = useMemo(() => {
    if (!selected) return { rows: [], added: 0, removed: 0 };
    const shown = selected.yaml || JSON.stringify(JSON.parse(selected.ir), null, 1);
    const before = highlightLines(shown);
    const after = highlightLines(draft);
    let i = 0;
    let j = 0;
    const rows = diffLines(shown, draft).map((l) => {
      const tokens = l.kind === "add" ? after[j++] : before[i++];
      if (l.kind === "same") j++;
      return { kind: l.kind, line: l.kind === "del" ? null : j, tokens: tokens ?? [{ text: l.text, cls: "" }] };
    });
    return { rows, added: rows.filter((r) => r.kind === "add").length, removed: rows.filter((r) => r.kind === "del").length };
  }, [selected, draft]);

  if (!list) return <div className="p-6 text-muted-foreground">Loading…</div>;
  const versions = [...list.versions].reverse();
  const number = (hash: string) => list.versions.indexOf(hash) + 1;

  return (
    <div className="grid h-full grid-rows-[auto_minmax(0,1fr)] md:grid-cols-[--spacing(72)_minmax(0,1fr)] md:grid-rows-1">
      <nav className="flex max-h-[38svh] min-h-0 flex-col overflow-hidden border-b bg-surface-2 md:max-h-none md:border-r md:border-b-0" aria-label="Version history">
        <PaneBar>
          <span className="text-xs font-medium">Versions</span>
          <span className="ml-1.5 font-mono text-xs text-muted-foreground">{versions.length}</span>
        </PaneBar>
        <ol className="min-h-0 overflow-auto px-2 py-2">
          {versions.map((hash, i) => {
            const live = hash === list.current;
            const active = selected?.hash === hash;
            const at = list.published?.[hash];
            return (
              <li key={hash} className="relative">
                {/* The rail joining each version to the one published before it, through the dots */}
                {versions.length > 1 && (
                  <span
                    aria-hidden
                    className={cn("absolute left-[--spacing(4.125)] z-[1] w-px bg-border", i === 0 ? "top-4.5 bottom-0" : i === versions.length - 1 ? "top-0 h-4.5" : "inset-y-0")}
                  />
                )}
                <button
                  type="button"
                  onClick={() => open(hash)}
                  aria-current={active ? "true" : undefined}
                  className={cn(
                    "relative flex w-full items-start gap-3 rounded-md px-3 py-2.5 text-left transition-colors",
                    active ? "bg-sidebar-accent text-sidebar-accent-foreground" : "hover:bg-accent",
                  )}
                >
                  <span
                    aria-hidden
                    className={cn(
                      "relative z-[2] mt-0.75 size-2.5 shrink-0 rounded-full border-2",
                      live ? "border-success bg-success" : active ? "border-accent-ui bg-background" : "border-muted-foreground/40 bg-surface-2",
                    )}
                  />
                  <span className="grid min-w-0 flex-1 gap-0.5">
                    <span className="flex items-center gap-2">
                      <span className="font-medium">Version {versions.length - i}</span>
                      {live && (
                        <Badge variant="outline" className="h-5 gap-1.5 px-1.5 font-normal text-muted-foreground">
                          <span className="size-1.5 rounded-full bg-success" />
                          Live
                        </Badge>
                      )}
                    </span>
                    <span className="truncate text-xs text-muted-foreground" title={at ? new Date(at).toLocaleString() : undefined}>
                      {at ? when(at) : "Date not recorded"}
                    </span>
                  </span>
                  <Badge variant="outline" className="mt-px shrink-0 font-mono text-xs font-normal text-muted-foreground">
                    {short(hash)}
                  </Badge>
                </button>
              </li>
            );
          })}
        </ol>
      </nav>
      {selected && (
        <div className="flex min-h-0 min-w-0 flex-col">
          <PaneBar>
            <div className="flex min-w-0 flex-1 items-center gap-2 whitespace-nowrap">
              <GitCompareArrows className="size-4 shrink-0 text-muted-foreground" />
              <span className="truncate font-medium">Version {number(selected.hash)}</span>
              <Badge variant="outline" className="shrink-0 font-mono text-xs font-normal text-muted-foreground">
                {short(selected.hash)}
              </Badge>
              <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                <ArrowRight className="size-3" aria-hidden />
                draft
              </span>
              <span className="shrink-0 font-mono text-xs text-success">+{diff.added}</span>
              <span className="shrink-0 font-mono text-xs text-danger">−{diff.removed}</span>
            </div>
            <div className="flex shrink-0 items-center gap-1">
              <Button variant="outline" size="sm" className="h-7 px-2.5 text-xs" disabled={!selected.yaml} onClick={() => onLoad(selected.yaml)}>
                <Download />
                Load into draft
              </Button>
              {selected.hash !== list.current && (
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button variant="outline" size="sm" className="h-7 px-2.5 text-xs">
                      <RotateCcw />
                      Make live
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Make version {number(selected.hash)} live?</AlertDialogTitle>
                      <AlertDialogDescription>
                        New sign ins use this version immediately. Sign ins in progress finish on their current version. The draft is unchanged.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction onClick={() => rollback(selected.hash)}>Make live</AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              )}
            </div>
          </PaneBar>
          <div className="min-h-0 flex-1 overflow-auto">
            {!selected.yaml && <p className="border-b bg-surface-2 px-4 py-1.5 text-xs text-muted-foreground">No YAML stored for this version. Showing IR.</p>}
            <table className="w-full border-collapse font-mono text-xs leading-6" aria-label="Changes from this version to the draft">
              <tbody>
                <tr aria-hidden className="h-3">
                  <td className="border-r bg-surface-2" />
                  <td colSpan={2} />
                </tr>
                {diff.rows.map((r, i) => (
                  <tr key={i} className={cn(r.kind === "add" && "bg-success/10", r.kind === "del" && "bg-danger/10")}>
                    <td className="w-px border-r bg-surface-2 pr-3 pl-4 text-right whitespace-nowrap text-muted-foreground select-none">{r.line}</td>
                    <td className={cn("w-px px-2.5 text-center select-none", r.kind === "add" ? "text-success" : r.kind === "del" ? "text-danger" : "text-transparent")}>
                      {r.kind === "add" ? "+" : r.kind === "del" ? "−" : " "}
                    </td>
                    <td className="pr-6 whitespace-pre">
                      {r.tokens.map((t, k) => (
                        <span key={k} className={t.cls || undefined}>
                          {t.text}
                        </span>
                      ))}
                    </td>
                  </tr>
                ))}
                <tr aria-hidden className="h-4">
                  <td className="border-r bg-surface-2" />
                  <td colSpan={2} />
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

/** "Today 15:53", "Yesterday 09:12", or "Oct 30, 2026 09:12" in the browser's locale. */
function when(iso: string): string {
  const at = new Date(iso);
  const time = at.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((day(new Date()) - day(at)) / 86_400_000);
  if (days === 0) return `Today ${time}`;
  if (days === 1) return `Yesterday ${time}`;
  return `${at.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })} ${time}`;
}

const short = (hash: string) => hash.replace("sha256:", "").slice(0, 8);
