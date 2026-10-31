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
import { GitCompareArrows, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { api, type Validation, type VersionView, type Versions as VersionList } from "../../../lib/api";
import { diffLines } from "../../../lib/diff";
import { PageHeader } from "../../shell";
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
              d.notify("ok", "Loaded into the draft", true);
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
      const { body } = await api<VersionView>(`tenants/${tenant}/versions/${hash}`);
      setSelected(body);
    },
    [tenant],
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
    const { status, body } = await api<Validation>(`tenants/${tenant}/current`, { method: "POST", json: { versionHash: hash } });
    if (status === 200) notify("ok", `Rolled back to ${short(hash)}`);
    else notify("error", `Not rolled back: ${body.errors?.map((e) => e.message).join("; ")}`);
    const { body: next } = await api<VersionList>(`tenants/${tenant}/versions`);
    setList(next);
    await open(hash);
    onRollback();
  };

  if (!list) return <div className="p-6 text-muted-foreground">Loading…</div>;
  const shown = selected?.yaml || (selected ? JSON.stringify(JSON.parse(selected.ir), null, 1) : "");
  const lines = selected ? diffLines(shown, draft) : [];
  const added = lines.filter((l) => l.kind === "add").length;
  const removed = lines.filter((l) => l.kind === "del").length;
  const versions = [...list.versions].reverse();

  return (
    <div className="grid h-full grid-cols-[260px_1fr]">
      <ol className="min-h-0 overflow-auto border-r bg-surface-2 p-2" aria-label="Versions, newest first">
        {versions.map((hash, i) => (
          <li key={hash}>
            <button
              type="button"
              onClick={() => open(hash)}
              className={cn(
                "flex h-8 w-full items-center gap-2 rounded-md px-2 text-left",
                selected?.hash === hash ? "bg-background shadow-xs ring-1 ring-border" : "hover:bg-accent",
              )}
            >
              <span className="font-mono text-xs">{short(hash)}</span>
              <span className="text-xs text-muted-foreground">#{versions.length - i}</span>
              {hash === list.current && (
                <Badge variant="outline" className="ml-auto gap-1.5 font-normal text-muted-foreground">
                  <span className="size-1.5 rounded-full bg-success" />
                  live
                </Badge>
              )}
            </button>
          </li>
        ))}
      </ol>
      {selected && (
        <div className="flex min-h-0 flex-col">
          <div className="flex h-11 shrink-0 items-center gap-3 border-b px-4">
            <GitCompareArrows className="size-4 text-muted-foreground" />
            <span className="font-mono text-xs">{short(selected.hash)}</span>
            <span className="text-xs text-muted-foreground">→ draft</span>
            <span className="font-mono text-xs text-success">+{added}</span>
            <span className="font-mono text-xs text-danger">−{removed}</span>
            <div className="ml-auto flex items-center gap-2">
              <Button variant="outline" size="sm" className="h-7 px-2.5 text-xs" disabled={!selected.yaml} onClick={() => onLoad(selected.yaml)}>
                Load into draft
              </Button>
              {selected.hash !== list.current && (
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button variant="outline" size="sm" className="h-7 px-2.5 text-xs">
                      <RotateCcw />
                      Roll back to this
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Make {short(selected.hash)} live?</AlertDialogTitle>
                      <AlertDialogDescription>
                        New sign ins run this version as soon as you confirm. Sign ins already in progress finish on the version they started with. The draft is not changed.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction onClick={() => rollback(selected.hash)}>Roll back</AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              )}
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-auto">
            {!selected.yaml && <p className="border-b bg-surface-2 px-4 py-1.5 text-xs text-muted-foreground">No YAML kept for this version; showing its IR</p>}
            <table className="w-full border-collapse font-mono text-xs leading-5" aria-label="Diff">
              <tbody>
                {lines.map((l, i) => (
                  <tr key={i} className={cn(l.kind === "add" && "bg-success/10", l.kind === "del" && "bg-danger/10")}>
                    <td className="w-8 border-r pr-2 text-right text-muted-foreground/60 select-none">{i + 1}</td>
                    <td className={cn("w-6 text-center select-none", l.kind === "add" ? "text-success" : l.kind === "del" ? "text-danger" : "text-transparent")}>
                      {l.kind === "add" ? "+" : l.kind === "del" ? "−" : " "}
                    </td>
                    <td className="pr-4 whitespace-pre">{l.text}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

const short = (hash: string) => hash.replace("sha256:", "").slice(0, 8);
