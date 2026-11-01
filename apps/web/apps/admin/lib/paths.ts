/** The virtual list math of the Paths tab: windows, pages and the cache eviction. */

/** Paths per validate request. */
export const PAGE = 100;
/** Pixels per row, the 4px gap between them included; rows are fixed height. */
export const ROW = 76;
/** Rows rendered beyond the viewport on each side. */
export const OVERSCAN = 8;
/** Pages kept beyond the visible ones on each side. */
export const KEEP = 3;

export type Filter = "all" | "succeed" | "fail";

/** The rows [start, end) to render for a scroll position. */
export function rowWindow(scrollTop: number, height: number, total: number): { start: number; end: number } {
  const top = Math.max(0, scrollTop);
  const start = Math.max(0, Math.floor(top / ROW) - OVERSCAN);
  const end = Math.min(total, Math.ceil((top + Math.max(0, height)) / ROW) + OVERSCAN);
  return { start: Math.min(start, end), end };
}

/** The pages those rows fall in. */
export function pagesOf(w: { start: number; end: number }): number[] {
  const pages: number[] = [];
  for (let p = Math.floor(w.start / PAGE); p <= Math.floor((w.end - 1) / PAGE); p++) pages.push(p);
  return pages;
}

/** Drops cached pages more than KEEP pages from the visible ones, so memory stays bounded however far one scrolls. */
export function evict<T>(cache: Map<number, T>, visible: number[]): Map<number, T> {
  const lo = visible.length === 0 ? Infinity : visible[0] - KEEP;
  const hi = visible.length === 0 ? -Infinity : visible[visible.length - 1] + KEEP;
  let dropped = false;
  const next = new Map<number, T>();
  for (const [page, paths] of cache) {
    if (page >= lo && page <= hi) next.set(page, paths);
    else dropped = true;
  }
  return dropped ? next : cache;
}

/** The validate query for one page of a filter. */
export function pageQuery(page: number, filter: Filter): string {
  const query = `offset=${page * PAGE}&limit=${PAGE}`;
  return filter === "all" ? query : `${query}&succeeds=${filter === "succeed"}`;
}

/** How many paths the filter shows, from the validation's counts. */
export function shownCount(v: { pathCount: number; succeeding: number }, filter: Filter): number {
  return filter === "all" ? v.pathCount : filter === "succeed" ? v.succeeding : v.pathCount - v.succeeding;
}

export type BudgetTone = "success" | "warning" | "danger";

/** Under half the budget is green, past three quarters (or over) red, between yellow. */
export function budgetTone(paths: number, budget: number, over: boolean): BudgetTone {
  const ratio = paths / budget;
  if (over || ratio > 0.75) return "danger";
  if (ratio >= 0.5) return "warning";
  return "success";
}
