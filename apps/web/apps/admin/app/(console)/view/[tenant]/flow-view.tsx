"use client";

import { Button } from "@faye/ui/components/button";
import { Badge } from "@faye/ui/components/badge";
import { Workflow } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type Draft } from "../../../../lib/api";
import { type FlowDoc, fromYaml } from "../../../../lib/flow";
import { Graph } from "../../tenants/[tenant]/graph";

/**
 * The whole flow, read only, filling the window: what Full screen opens from the workspace. It
 * follows the console's draft live (same BroadcastChannel DraftProvider posts to), and refetches
 * when the tab returns to view.
 */
export function FlowView({ tenant }: { tenant: string }) {
  const [doc, setDoc] = useState<FlowDoc | null>(null);
  const [stale, setStale] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    // YAML that does not parse is skipped: the graph keeps the last document that did
    const show = (yaml: string) => {
      try {
        setDoc(fromYaml(yaml));
        setStale(false);
      } catch {
        setStale(true);
      }
    };
    const load = () =>
      api<Draft>(`tenants/${tenant}/draft`)
        .then(({ body }) => {
          if (!live) return;
          show(body.yaml);
          setError(null);
        })
        .catch((e) => live && setError((e as Error).message));
    load();
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    const channel = new BroadcastChannel(`faye.draft.${tenant}`);
    channel.onmessage = (e) => show(e.data as string);
    return () => {
      live = false;
      document.removeEventListener("visibilitychange", onVisible);
      channel.close();
    };
  }, [tenant]);

  return (
    <div className="relative h-svh">
      {doc ? (
        <Graph doc={doc} visited={null} visitKeys={new Map()} findings={new Map()} selected={null} readOnly fit="contain" />
      ) : (
        <div className="grid h-full place-items-center text-muted-foreground">{error ?? "Loading…"}</div>
      )}
      <div className="absolute top-3 left-3 flex h-8 items-center gap-2 rounded-lg border bg-background px-2.5 shadow-sm">
        <Workflow className="size-3.5 shrink-0 text-muted-foreground" />
        <span className="font-mono text-xs">{tenant}</span>
        <span className="text-xs font-medium">Login flow</span>
        <Badge variant="outline" className="h-5 font-normal">{stale ? "last valid draft" : "read only"}</Badge>
        <Button asChild variant="ghost" size="sm" className="h-6 px-1.5 text-xs">
          <Link href={`/tenants/${encodeURIComponent(tenant)}/flow`}>Edit</Link>
        </Button>
      </div>
    </div>
  );
}
