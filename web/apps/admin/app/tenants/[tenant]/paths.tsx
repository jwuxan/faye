"use client";

import { cn } from "@faye/ui/lib/utils";
import { ArrowRight, Route } from "lucide-react";
import { useState } from "react";
import type { PathView, Validation } from "../../../lib/api";
import { Empty, Segmented } from "../../ui";

type Filter = "all" | "succeed" | "fail";

/** Every path the checker enumerated, with the assurance each yields; one can be highlighted. */
export function Paths({
  validation,
  selected,
  onSelect,
  onShow,
}: {
  validation: Validation | null;
  selected: PathView | null;
  onSelect: (p: PathView | null) => void;
  onShow: () => void;
}) {
  const [filter, setFilter] = useState<Filter>("all");
  if (!validation) return <div className="p-6 text-muted-foreground">Checking…</div>;
  if (validation.paths.length === 0) return <Empty icon={Route} title="No paths">Fix the errors first</Empty>;
  const shown = validation.paths.filter((p) => filter === "all" || (filter === "succeed") === p.succeeds);
  const succeed = validation.paths.filter((p) => p.succeeds).length;
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-11 shrink-0 items-center gap-3 border-b px-4">
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
        {selected && (
          <button type="button" onClick={onShow} className="ml-auto flex items-center gap-1 text-xs text-accent-ui hover:underline">
            View in Blocks <ArrowRight className="size-3.5" />
          </button>
        )}
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        <table className="w-full border-collapse text-[13px]">
          <thead className="sticky top-0 z-10 bg-surface-2 text-left text-xs text-muted-foreground">
            <tr className="h-8 border-b">
              <th className="w-24 pl-4 font-medium">Result</th>
              <th className="w-20 font-medium">Section</th>
              <th className="font-medium">Path</th>
              <th className="w-40 font-medium">amr</th>
              <th className="w-24 pr-4 text-right font-medium">acr</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((p, i) => {
              const isSelected = selected === p;
              return (
                <tr
                  key={i}
                  tabIndex={0}
                  aria-selected={isSelected}
                  onClick={() => onSelect(isSelected ? null : p)}
                  onKeyDown={(e) => e.key === "Enter" && onSelect(isSelected ? null : p)}
                  className={cn(
                    "h-8 cursor-pointer border-b outline-none hover:bg-accent focus-visible:bg-accent",
                    isSelected && "bg-accent-ui/8 shadow-[inset_2px_0_0_var(--accent-ui)] dark:bg-accent-ui/14",
                  )}
                >
                  <td className="pl-4">
                    <span className="flex items-center gap-1.5 text-xs">
                      <span className={cn("size-1.5 rounded-full", p.succeeds ? "bg-success" : "bg-muted-foreground/50")} />
                      {p.succeeds ? "succeed" : "fail"}
                    </span>
                  </td>
                  <td className="font-mono text-xs text-muted-foreground">{p.section}</td>
                  <td className="max-w-0 truncate pr-4 font-mono text-xs" title={p.path}>
                    {p.path.split(" > ").map((part, j, parts) => (
                      <span key={j} className={j === parts.length - 1 ? "text-muted-foreground" : ""}>
                        {part}
                        {j < parts.length - 1 && <span className="px-1 text-muted-foreground">›</span>}
                      </span>
                    ))}
                  </td>
                  <td className="font-mono text-xs">{p.amr.join(" ") || <span className="text-muted-foreground">—</span>}</td>
                  <td className="pr-4 text-right font-mono text-xs">{p.acr ?? <span className="text-muted-foreground">—</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
