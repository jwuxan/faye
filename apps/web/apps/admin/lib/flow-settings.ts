/**
 * Flow settings (ADR 0035): how long a person has to finish an entry of each kind. The server
 * names the kinds and the limits; this file only labels them, converts minutes the console shows
 * to the seconds the API takes, and says what is wrong before a save. The server checks again.
 */

/** One kind's limits, in seconds; absent means no limit but the deployment's. */
export type FlowLimits = { lifetimeSeconds?: number; idleTimeoutSeconds?: number };
/** Flow settings by entry kind, as the admin API takes them. */
export type FlowSettings = Record<string, FlowLimits>;
/** What may be set: the kinds the engine runs, and the bounds of either limit in seconds. */
export type FlowLimitsView = { kinds: string[]; maxSeconds: number; minSeconds: number };

const KINDS: Record<string, { title: string; description: string }> = {
  authenticate: { title: "Sign in", description: "From the first screen of a sign in to its last answer." },
  consent: { title: "Consent", description: "From the consent screen to the user's decision." },
};

/** How the console names an entry kind; a kind it does not know shows under its own name. */
export function kindLabel(kind: string): { title: string; description: string } {
  return KINDS[kind] ?? { title: kind, description: `Entries of kind ${kind}.` };
}

/** Seconds as whole minutes for a field; empty when unset. */
export function toMinutes(seconds: number | undefined): string {
  return seconds === undefined ? "" : String(Math.round(seconds / 60));
}

/** A field's minutes as seconds; undefined when empty or not a whole number of minutes. */
export function fromMinutes(text: string): number | undefined {
  if (text.trim() === "") return undefined;
  const n = Number(text);
  return Number.isInteger(n) ? n * 60 : undefined;
}

/** `settings` with one kind's limit set or cleared; a kind with no limit left is dropped, as the server does. */
export function withLimit(settings: FlowSettings, kind: string, name: keyof FlowLimits, seconds: number | undefined): FlowSettings {
  const limits: FlowLimits = { ...(settings[kind] ?? {}) };
  if (seconds === undefined) delete limits[name];
  else limits[name] = seconds;
  const next = { ...settings };
  if (Object.keys(limits).length === 0) delete next[kind];
  else next[kind] = limits;
  return next;
}

/** What is wrong with one kind's limits, by field; empty when they can be saved. */
export function limitProblems(limits: FlowLimits, view: FlowLimitsView): Partial<Record<keyof FlowLimits, string>> {
  const out: Partial<Record<keyof FlowLimits, string>> = {};
  const min = view.minSeconds / 60;
  const { lifetimeSeconds: life, idleTimeoutSeconds: idle } = limits;
  if (life !== undefined && (life < view.minSeconds || life > view.maxSeconds)) {
    out.lifetimeSeconds = `Between ${min} and ${view.maxSeconds / 60} minutes`;
  }
  const idleMax = life ?? view.maxSeconds;
  if (idle !== undefined && (idle < view.minSeconds || idle > idleMax)) {
    out.idleTimeoutSeconds = life !== undefined && idle > life ? "No longer than the time limit" : `Between ${min} and ${idleMax / 60} minutes`;
  }
  return out;
}
