/**
 * A preview run as its live trace events arrive (the server's TraceEvent, fed over SSE), reduced to
 * what the block tree highlights: the visit keys of the steps and options it went through (the
 * scheme of PathView.visits and visitKeys in flow.ts) and the one waiting on a screen.
 */
export type TraceEvent = {
  event: string;
  id?: string;
  outcome?: string;
  amr?: string[];
  acr?: string;
  /** The node the event is about: its entry and child index path (ADR 0027); absent on start. */
  at?: { entry: string; path: number[] };
};
/**
 * Where an event's node came from in the YAML the run was compiled from, resolved by the server
 * from the version's source map; absent when the version has no line for it. kind is the YAML key
 * (use, hook, screen, fail, succeed, step, retry, if, alternative, set, steps).
 */
export type Source = { origin: string; line: number; column: number; kind: string };
export type Recorded = { seq: number; tenantId: string; flowId: string; at: number; event: TraceEvent; source?: Source | null };

export type LiveRun = {
  flowId: string;
  events: Recorded[];
  visits: string[];
  /** Visit key of the step or option waiting on a screen; null once the run moved on or ended. */
  waiting: string | null;
  end: TraceEvent | null;
  /** The sign in this run's consent steps follow: its flow and how it ended (ADR 0026). */
  signIn?: { flowId: string; end: TraceEvent };
};

/**
 * Folds one more event into the run it belongs to; an event of another flow starts a new run,
 * except the consent entry of the version a sign in just succeeded on: the authorization server
 * runs it as a flow of its own, but it is the rest of the same run.
 */
export function follow(run: LiveRun | null, r: Recorded): LiveRun {
  const e = r.event;
  const consent =
    run !== null &&
    run.flowId !== r.flowId &&
    !run.signIn &&
    run.end?.outcome === "succeed" &&
    e.event === "start" &&
    e.outcome === "consent" &&
    e.id === run.events[0]?.event.id;
  const current: LiveRun =
    run && (run.flowId === r.flowId || consent)
      ? { ...run, ...(consent ? { waiting: null, end: null, signIn: { flowId: run.flowId, end: run.end! } } : {}) }
      : { flowId: r.flowId, events: [], visits: [], waiting: null, end: null };
  const visits = [...current.visits];
  if (e.event === "step" && e.id) visits.push(e.id);
  if (e.event === "option" && e.id) visits.push(`${visits.at(-1) ?? ""}/${e.id}`);
  return {
    ...current,
    flowId: r.flowId,
    events: [...current.events, r],
    visits,
    waiting: e.event === "screen" ? (visits.at(-1) ?? null) : e.event === "end" ? null : current.waiting,
    end: e.event === "end" ? e : current.end,
  };
}

/** `acme.yaml:14:7`, as compile errors show positions. */
export function sourceLabel(s: Source): string {
  return `${s.origin}:${s.line}:${s.column}`;
}

/** The event in a line: its id, outcome, amr and acr, whichever it has. */
export function describe(e: TraceEvent): string {
  return [e.id && e.event !== "start" ? e.id : null, e.outcome, e.amr?.join("+"), e.acr].filter(Boolean).join(" ");
}

const pad = (n: number, w = 2) => String(n).padStart(w, "0");

/** HH:MM:SS.mmm, local, for a trace row's timestamp. */
export function timeOf(at: number): string {
  const d = new Date(at);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

/** "Nov 1, 21:33:05", local — a run's start, shown in its header and as a row's full title. */
export function stamp(at: number): string {
  const d = new Date(at);
  const month = d.toLocaleString("en-US", { month: "short" });
  return `${month} ${d.getDate()}, ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** A gap between two events: "+12 ms" under a second, "+1.3 s" under a minute, then "+2 min". */
export function delta(ms: number): string {
  if (ms < 1000) return `+${ms} ms`;
  if (ms < 60_000) return `+${(ms / 1000).toFixed(1)} s`;
  return `+${Math.round(ms / 60_000)} min`;
}

/** A run's total: "240 ms" under a second, "1.24 s" under a minute, then "2 min". */
export function duration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(2).replace(/0+$/, "").replace(/\.$/, "")} s`;
  return `${Math.round(ms / 60_000)} min`;
}
