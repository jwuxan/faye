"use client";

import { Badge } from "@faye/ui/components/badge";
import { cn } from "@faye/ui/lib/utils";
import { Route } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, type PathView, type Validation } from "../../../../lib/api";
import { budgetTone, evict, type Filter, PAGE, pageQuery, pagesOf, ROW, rowWindow, shownCount } from "../../../../lib/paths";
import { Empty, Help, Segmented } from "../../ui";
import { useDraft } from "./draft";

export type PathFilterValue = Filter;

/** Which paths the Paths tab lists; it sits in the tab's toolbar. */
export function PathFilter({ validation, value, onChange }: { validation: Validation; value: PathFilterValue; onChange: (v: PathFilterValue) => void }) {
  return (
    <Segmented
      label="Show"
      value={value}
      onChange={onChange}
      options={[
        { value: "all", label: <>All <span className="font-mono text-muted-foreground">{validation.pathCount}</span></> },
        { value: "succeed", label: <>Succeed <span className="font-mono text-muted-foreground">{validation.succeeding}</span></> },
        { value: "fail", label: <>Fail <span className="font-mono text-muted-foreground">{validation.pathCount - validation.succeeding}</span></> },
      ]}
    />
  );
}

const NO_PAGES = new Map<number, PathView[]>();

/**
 * Every path the checker enumerated, with the assurance each yields; one can be highlighted in the
 * blocks and graph. There can be tens of thousands: the validation carries only counts and the list
 * fetches the paths a page at a time, rendering the rows near the scroll position.
 */
export function Paths({
  tenant,
  validation,
  checked,
  filter,
  selected,
  onSelect,
}: {
  tenant: string;
  validation: Validation | null;
  checked: string | null;
  filter: PathFilterValue;
  selected: PathView | null;
  onSelect: (p: PathView | null) => void;
}) {
  const fail = useDraft().fail;
  const [height, setHeight] = useState(0);
  const scroller = useRef<HTMLDivElement | null>(null);
  const virtual = useRef<HTMLUListElement | null>(null);
  const observer = useRef<ResizeObserver | null>(null);

  // Pages fetched for the checked YAML under the current filter; a changed key starts over,
  // scroll position included
  const cacheKey = `${checked}\n${filter}`;
  const [cache, setCache] = useState<{ key: string; pages: Map<number, PathView[]> }>({ key: cacheKey, pages: new Map() });
  const [scroll, setScroll] = useState({ key: cacheKey, top: 0 });
  const scrollTop = scroll.key === cacheKey ? scroll.top : 0;
  const inflight = useRef(new Set<string>());
  const pages = cache.key === cacheKey ? cache.pages : NO_PAGES;
  // Paths can read alike, so the selection is the row it was picked at
  const [picked, setPicked] = useState<{ key: string; row: number } | null>(null);

  const scrollBox = useCallback((el: HTMLDivElement | null) => {
    observer.current?.disconnect();
    observer.current = null;
    scroller.current = el;
    if (el) {
      setHeight(el.clientHeight);
      observer.current = new ResizeObserver(() => setHeight(el.clientHeight));
      observer.current.observe(el);
    }
  }, []);

  // Back to the top when the paths change underneath: another check, another filter
  useEffect(() => {
    scroller.current?.scrollTo({ top: 0 });
  }, [cacheKey]);

  const shown = validation ? shownCount(validation, filter) : 0;
  const { start, end } = rowWindow(scrollTop, height, shown);
  const visible = useRef<number[]>([]);
  useEffect(() => {
    visible.current = pagesOf({ start, end });
  }, [start, end]);

  // Fetch the pages the window needs; responses for a stale check or filter are dropped
  useEffect(() => {
    if (!validation || checked === null) return;
    for (const page of pagesOf({ start, end })) {
      if (pages.has(page)) continue;
      const id = `${cacheKey}\n${page}`;
      if (inflight.current.has(id)) continue;
      inflight.current.add(id);
      api<Validation>(`tenants/${tenant}/validate?${pageQuery(page, filter)}`, { method: "POST", yaml: checked })
        .then(({ body }) => {
          inflight.current.delete(id);
          setCache((cur) => {
            const next = new Map(cur.key === cacheKey ? cur.pages : undefined);
            next.set(page, body.paths);
            return { key: cacheKey, pages: evict(next, visible.current) };
          });
        })
        .catch((e) => {
          inflight.current.delete(id);
          fail(e);
        });
    }
  }, [validation, checked, cacheKey, tenant, filter, start, end, pages, fail]);

  if (!validation) return <div className="p-4 text-muted-foreground">Checking…</div>;
  if (validation.pathCount === 0)
    return (
      <div className="flex h-full flex-col overflow-y-auto">
        <PathBudget validation={validation} />
        <div className="min-h-0 flex-1">
          <Empty icon={Route} title="No paths">
            Fix errors to enumerate paths
          </Empty>
        </div>
      </div>
    );

  const rows: number[] = [];
  for (let i = start; i < end; i++) rows.push(i);

  return (
    <div
      ref={scrollBox}
      onScroll={(e) => setScroll({ key: cacheKey, top: e.currentTarget.scrollTop - (virtual.current?.offsetTop ?? 0) })}
      className="relative h-full overflow-x-hidden overflow-y-auto"
    >
      <PathBudget validation={validation} />
      <p className="p-4 pb-3 text-xs text-muted-foreground">Select a path to highlight it in the blocks and graph.</p>
      <ul ref={virtual} aria-label="Paths" className="relative m-4 mt-0" style={{ height: shown * ROW }}>
        {rows.map((i) => {
          const p = pages.get(Math.floor(i / PAGE))?.[i % PAGE];
          const isSelected =
            p !== undefined && picked?.key === cacheKey && picked.row === i && selected?.entry === p.entry && selected?.path === p.path;
          return (
            <li key={i} aria-setsize={shown} aria-posinset={i + 1} className="absolute inset-x-0" style={{ top: i * ROW, height: ROW - 4 }}>
              {p ? (
                <PathRow
                  path={p}
                  selected={isSelected}
                  onSelect={(s) => {
                    setPicked(s ? { key: cacheKey, row: i } : null);
                    onSelect(s);
                  }}
                />
              ) : (
                <div aria-busy="true" className="grid h-full content-center gap-1.5 rounded-md border px-2.5 py-2">
                  <span className="h-4 w-2/5 rounded-xs bg-muted" />
                  <span className="h-4 w-4/5 rounded-xs bg-muted" />
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** How close each entry is to the checker's path cap; nothing when the flow has none to report. */
export function PathBudget({ validation }: { validation: Validation }) {
  if (validation.entries.length === 0) return null;
  const budget = validation.pathBudget;
  return (
    <div className="grid gap-2.5 p-4 pb-2">
      <p className="flex items-center gap-1 text-xs text-muted-foreground">
        Path budget
        <Help side="bottom">
          Each entry may have at most {budget.toLocaleString()} paths, counted by the checker. Past that the flow cannot be
          published: split the entry or remove branches.
        </Help>
      </p>
      {validation.entries.map(({ entry, paths, overBudget }) => {
        const tone = budgetTone(paths, budget, overBudget);
        return (
          <div key={entry} className="grid gap-1">
            <span className="flex items-baseline gap-2 text-xs">
              <span className="font-mono">{entry}</span>
              <span className="ml-auto font-mono text-muted-foreground">
                {overBudget ? `over ${budget.toLocaleString()}` : `${paths.toLocaleString()} / ${budget.toLocaleString()}`}
              </span>
            </span>
            <span
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={budget}
              aria-valuenow={overBudget ? budget : paths}
              aria-label={`${entry} path budget`}
              className="h-1.5 rounded-full bg-muted"
            >
              <span
                className={cn(
                  "block h-full rounded-full",
                  tone === "success" ? "bg-success" : tone === "warning" ? "bg-warning" : "bg-danger",
                )}
                style={{ width: `${overBudget ? 100 : Math.min(100, (paths / budget) * 100)}%` }}
              />
            </span>
            {tone === "danger" && (
              <span className="text-xs text-muted-foreground">
                {overBudget
                  ? "Over the limit: this flow cannot be published until the entry is split"
                  : "Close to the limit: split this entry before adding branches"}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** One loaded row of the virtual list. */
function PathRow({ path: p, selected, onSelect }: { path: PathView; selected: boolean; onSelect: (p: PathView | null) => void }) {
  const parts = p.path.split(" > ");
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={() => onSelect(selected ? null : p)}
      className={cn(
        "grid h-full w-full gap-1.5 overflow-hidden rounded-md border px-2.5 py-2 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring/50",
        selected && "border-accent-ui bg-accent-ui/8 hover:bg-accent-ui/10 dark:bg-accent-ui/14",
      )}
    >
      <span className="flex items-center gap-1.5 text-xs">
        <span className={cn("size-1.5 shrink-0 rounded-full", p.succeeds ? "bg-success" : "bg-muted-foreground/50")} />
        <span className={p.succeeds ? "font-medium" : "text-muted-foreground"}>{p.succeeds ? "succeeds" : "fails"}</span>
        <span className="font-mono text-muted-foreground">{p.entry}</span>
        <span className="ml-auto flex gap-1">
          {p.amr.map((a) => (
            <Badge key={a} variant="secondary" className="h-4 rounded-xs px-1 font-mono text-xs font-normal">
              {a}
            </Badge>
          ))}
          {p.acr && (
            <Badge variant="outline" className="h-4 rounded-xs px-1 font-mono text-xs font-normal">
              {p.acr}
            </Badge>
          )}
        </span>
      </span>
      <span title={p.path} className="font-mono text-xs leading-4 break-words line-clamp-2">
        {parts.map((part, j) => (
          <span key={j} className={j === parts.length - 1 ? "text-muted-foreground" : ""}>
            {part}
            {j < parts.length - 1 && <span className="px-0.5 text-muted-foreground">›</span>}
          </span>
        ))}
      </span>
    </button>
  );
}
