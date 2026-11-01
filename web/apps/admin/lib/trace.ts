/**
 * A preview run as its live trace events arrive (the server's TraceEvent, fed over SSE), reduced to
 * what the block tree highlights: the visit keys of the steps and options it went through (the
 * scheme of PathView.visits and visitKeys in flow.ts) and the one waiting on a screen.
 */
export type TraceEvent = { event: string; id?: string; outcome?: string; amr?: string[]; acr?: string };
export type Recorded = { seq: number; tenantId: string; flowId: string; at: number; event: TraceEvent };

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

/** The event in a line: its id, outcome, amr and acr, whichever it has. */
export function describe(e: TraceEvent): string {
  return [e.id && e.event !== "start" ? e.id : null, e.outcome, e.amr?.join("+"), e.acr].filter(Boolean).join(" ");
}
