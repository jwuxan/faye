"use client";

import { Badge } from "@faye/ui/components/badge";
import { cn } from "@faye/ui/lib/utils";
import { Route } from "lucide-react";
import { useState } from "react";
import type { PathView, Validation } from "../../../lib/api";
import { Empty, Segmented } from "../../ui";

type Filter = "all" | "succeed" | "fail";

/** Every path the checker enumerated, with the assurance each yields; one can be highlighted in the blocks and graph. */
export function Paths({ validation, selected, onSelect }: { validation: Validation | null; selected: PathView | null; onSelect: (p: PathView | null) => void }) {
  const [filter, setFilter] = useState<Filter>("all");
  if (!validation) return <div className="p-4 text-muted-foreground">Checking…</div>;
  if (validation.paths.length === 0) return <Empty icon={Route} title="No paths">Fix the errors first</Empty>;
  const shown = validation.paths.filter((p) => filter === "all" || (filter === "succeed") === p.succeeds);
  const succeed = validation.paths.filter((p) => p.succeeds).length;
  return (
    <div className="grid content-start gap-3 p-3">
      <div className="flex items-center gap-2">
        <Segmented
          label="Show"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: <>All <span className="font-mono text-muted-foreground">{validation.paths.length}</span></> },
            { value: "succeed", label: <>Succeed <span className="font-mono text-muted-foreground">{succeed}</span></> },
            { value: "fail", label: <>Fail <span className="font-mono text-muted-foreground">{validation.paths.length - succeed}</span></> },
          ]}
        />
      </div>
      <p className="text-xs text-muted-foreground">Select a path to light it up in the blocks and the graph.</p>
      <ul className="grid gap-1" aria-label="Paths">
        {shown.map((p, i) => {
          const isSelected = selected === p;
          const parts = p.path.split(" > ");
          return (
            <li key={i}>
              <button
                type="button"
                aria-pressed={isSelected}
                onClick={() => onSelect(isSelected ? null : p)}
                className={cn(
                  "grid w-full gap-1.5 rounded-md border px-2.5 py-2 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring/50",
                  isSelected && "border-accent-ui bg-accent-ui/8 hover:bg-accent-ui/10 dark:bg-accent-ui/14",
                )}
              >
                <span className="flex items-center gap-1.5 text-xs">
                  <span className={cn("size-1.5 shrink-0 rounded-full", p.succeeds ? "bg-success" : "bg-muted-foreground/50")} />
                  <span className={p.succeeds ? "font-medium" : "text-muted-foreground"}>{p.succeeds ? "succeeds" : "fails"}</span>
                  <span className="font-mono text-muted-foreground">{p.entry}</span>
                  <span className="ml-auto flex gap-1">
                    {p.amr.map((a) => (
                      <Badge key={a} variant="secondary" className="h-4 rounded-[3px] px-1 font-mono text-[10px] font-normal">
                        {a}
                      </Badge>
                    ))}
                    {p.acr && (
                      <Badge variant="outline" className="h-4 rounded-[3px] px-1 font-mono text-[10px] font-normal">
                        {p.acr}
                      </Badge>
                    )}
                  </span>
                </span>
                <span className="font-mono text-[11px] leading-4 break-words">
                  {parts.map((part, j) => (
                    <span key={j} className={j === parts.length - 1 ? "text-muted-foreground" : ""}>
                      {part}
                      {j < parts.length - 1 && <span className="px-0.5 text-muted-foreground">›</span>}
                    </span>
                  ))}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
