/**
 * A preview run as the Faye server reports it (GET /api/preview/flows/{flowId}), for tokens a
 * preview client got: they carry the login's flow id as faye_flow_id. Server only.
 */
/** source: the YAML line the event's node came from (ADR 0027), absent when the version has none. */
export type TraceEvent = {
  event: string;
  id?: string;
  outcome?: string;
  amr?: string[];
  acr?: string;
  source?: { origin: string; line: number; column: number; kind: string } | null;
};
export type PredictedPath = { path: string; succeeds: boolean; amr: string[]; acr: string | null };
export type PreviewRun = {
  flowId: string;
  tenant: string;
  versionHash: string;
  draft: boolean;
  events: TraceEvent[];
  visits: string[];
  result: { succeeded: boolean; failure: string | null; amr: string[]; acr: string | null } | null;
  expected: PredictedPath[];
};

export async function previewRun(flowId: string): Promise<PreviewRun | null> {
  const server = process.env.FAYE_SERVER_URL ?? "http://localhost:8080";
  try {
    const res = await fetch(`${server}/api/preview/flows/${encodeURIComponent(flowId)}`, { cache: "no-store" });
    return res.ok ? ((await res.json()) as PreviewRun) : null;
  } catch {
    return null;
  }
}

/** The predicted path whose amr (as a set) and acr the token's equal, if any. */
export function matching(run: PreviewRun, amr: unknown, acr: unknown): PredictedPath | null {
  const issued = new Set(Array.isArray(amr) ? amr.map(String) : []);
  return (
    run.expected.find(
      (p) => p.succeeds && p.acr === (acr ?? null) && p.amr.length === issued.size && p.amr.every((a) => issued.has(a)),
    ) ?? null
  );
}

/** One line per event: what it was, its id and how it went. */
export function describe(e: TraceEvent): string {
  return [e.id && e.event !== "start" ? e.id : null, e.outcome, e.amr?.join("+"), e.acr].filter(Boolean).join(" ");
}
