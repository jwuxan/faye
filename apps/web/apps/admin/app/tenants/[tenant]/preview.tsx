"use client";

import { Button } from "@faye/ui/components/button";
import { Badge } from "@faye/ui/components/badge";
import { Field, FieldLabel } from "@faye/ui/components/field";
import { RadioGroup, RadioGroupItem } from "@faye/ui/components/radio-group";
import { Separator } from "@faye/ui/components/separator";
import { Spinner } from "@faye/ui/components/spinner";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, CircleX, Play, UserPlus, Users } from "lucide-react";
import { Fragment, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { api, type PreviewRun, type PreviewView, type Seeded } from "../../../lib/api";
import { remaining, totp } from "../../../lib/totp";
import { delta, describe, duration, stamp, timeOf, type LiveRun, type Recorded, type TraceEvent } from "../../../lib/trace";
import { Input } from "@faye/ui/components/input";
import { Chip, Choice, CopyButton, denseMono, Help, PanelSection, Prop, Segmented, Switch } from "../../ui";

/** An IP in TEST-NET-3, which the local ip-reputation stub scores as high risk. */
const RISKY_IP = "203.0.113.7";

type Launch = { loginHint: string; acrValues: string; prompt: string; ip: string };
const EMPTY: Launch = { loginHint: "", acrValues: "", prompt: "login", ip: "" };

/** The scenario form, kept for this tab so a reload does not lose it. */
type Saved = { launch: Launch; account: Seeded | null; secret: string };
const savedKey = (tenant: string) => `faye.preview.${tenant}`;
function restore(tenant: string): Saved | null {
  try {
    const s = sessionStorage.getItem(savedKey(tenant));
    return s ? (JSON.parse(s) as Saved) : null;
  } catch {
    return null;
  }
}

/**
 * Runs the draft against a real OAuth client without publishing it: set up who signs in and the
 * request, then Preview opens the demo client with the tenant's preview client, and the run's trace
 * streams in as it happens (the block tree highlights it).
 */
export function Preview({
  tenant,
  demoClient,
  blocked,
  run,
  onEvent,
  fail,
  reset = 0,
  onFollowing,
  launchSlot,
}: {
  tenant: string;
  demoClient: string;
  /** Why the draft cannot run, or null. */
  blocked: string | null;
  run: LiveRun | null;
  onEvent: (r: Recorded) => void;
  fail: (e: unknown) => void;
  /** Bumped by Reset scenario. */
  reset?: number;
  /** Reports the trace feed's state, for the toolbar's status. */
  onFollowing?: (s: "connecting" | "open" | "lost") => void;
  /** Where the toolbar holds the Preview button. */
  launchSlot?: HTMLElement | null;
}) {
  const [view, setView] = useState<PreviewView | null>(null);
  const [saved] = useState(() => restore(tenant));
  const [launch, setLaunch] = useState<Launch>(saved?.launch ?? EMPTY);
  const [account, setAccount] = useState<Seeded | null>(saved?.account ?? null);
  const [secret, setSecret] = useState(saved?.secret ?? "");
  const [seeding, setSeeding] = useState(false);
  const [following, setFollowing] = useState<"connecting" | "open" | "lost">("connecting");
  const [launching, setLaunching] = useState(false);
  const [openIn, setOpenIn] = useState<"popup" | "tab">(() => {
    try {
      return localStorage.getItem("faye.preview.open") === "tab" ? "tab" : "popup";
    } catch {
      return "popup";
    }
  });
  // Reset scenario, from the toolbar
  const [resetSeen, setResetSeen] = useState(reset);
  if (reset !== resetSeen) {
    setResetSeen(reset);
    setLaunch(EMPTY);
    setAccount(null);
    setSecret("");
  }
  // What the checker predicted, by flow: a sign in and the consent run that follows it are two
  const [summaries, setSummaries] = useState<Record<string, PreviewRun>>({});

  useEffect(() => {
    api<PreviewView>(`tenants/${tenant}/preview`)
      .then(({ body }) => {
        setView(body);
        if (!saved) setLaunch((l) => ({ ...l, ip: body.scenario.request.ip ?? "" }));
      })
      .catch(fail);
  }, [tenant, fail, saved]);

  useEffect(() => {
    try {
      sessionStorage.setItem(savedKey(tenant), JSON.stringify({ launch, account, secret } satisfies Saved));
    } catch {
      // ignored: private windows can refuse storage
    }
  }, [tenant, launch, account, secret]);

  useEffect(() => onFollowing?.(following), [following, onFollowing]);

  // Follow the tenant's preview trace while the panel is open
  useEffect(() => {
    if (!view?.enabled) return;
    const source = new EventSource(`/api/admin/tenants/${encodeURIComponent(tenant)}/preview/events`);
    source.onopen = () => setFollowing("open");
    source.onerror = () => setFollowing(source.readyState === EventSource.CLOSED ? "lost" : "connecting");
    source.addEventListener("trace", (e) => onEvent(JSON.parse((e as MessageEvent<string>).data) as Recorded));
    return () => source.close();
  }, [tenant, view?.enabled, onEvent]);

  // Once a run ends, ask the server what the checker predicted for the way it went
  const ended = run?.end ? run.flowId : null;
  useEffect(() => {
    if (!ended) return;
    api<PreviewRun>(`tenants/${tenant}/preview/flows/${ended}`)
      .then(({ body }) => setSummaries((s) => ({ ...s, [ended]: body })))
      .catch(fail);
  }, [ended, tenant, fail]);

  const seed = async (withTotp: boolean) => {
    setSeeding(true);
    try {
      const { body } = await api<Seeded>(`tenants/${tenant}/preview/users`, { method: "POST", json: { totp: withTotp } });
      setAccount(body);
      setSecret(body.totpSecret ?? "");
      setLaunch((l) => ({ ...l, loginHint: body.identifier }));
    } catch (e) {
      fail(e);
    } finally {
      setSeeding(false);
    }
  };

  const start = async () => {
    // Open the window while the click still counts as a user action; point it at the client after
    const win = openIn === "popup" ? window.open("about:blank", "faye-preview", "popup,width=480,height=820") : window.open("about:blank", "_blank");
    setLaunching(true);
    try {
      await api(`tenants/${tenant}/preview/scenario`, { method: "PUT", json: { request: launch.ip ? { ip: launch.ip } : {} } });
      const query = new URLSearchParams({ client: `preview-${tenant}` });
      if (launch.loginHint) query.set("login_hint", launch.loginHint);
      if (launch.acrValues) query.set("acr_values", launch.acrValues);
      if (launch.prompt) query.set("prompt", launch.prompt);
      const url = `${demoClient}/login?${query}`;
      if (win) win.location.href = url;
      else window.open(url, "_blank");
    } catch (e) {
      win?.close();
      fail(e);
    } finally {
      setLaunching(false);
    }
  };

  if (!view) return <div className="p-4 text-muted-foreground">Loading…</div>;
  if (!view.enabled) {
    return (
      <div className="p-4 text-sm">
        <p className="text-muted-foreground">
          Previews are disabled on this server. Start Faye with <code className="font-mono text-xs">FAYE_PREVIEW_ENABLED=true</code> to run drafts with the{" "}
          <code className="font-mono text-xs">preview-{tenant}</code> client.
        </p>
      </div>
    );
  }

  const set = (patch: Partial<Launch>) => setLaunch((l) => ({ ...l, ...patch }));
  const accountValue = account ? "new" : "none";
  const running = launching || (run !== null && !run.end);
  return (
    <div className="grid content-start">
      <PanelSection title="Account" help="Creates a preview account and passes it as login_hint. Preview accounts sign in only through preview clients.">
        <Prop label="Who">
          <Segmented
            label="Account"
            value={accountValue}
            options={[
              { value: "none", label: <><Users className="size-3.5" strokeWidth={1.75} />Anyone</> },
              { value: "new", label: <><UserPlus className="size-3.5" strokeWidth={1.75} />New user</> },
            ]}
            onChange={(v) => {
              if (v === "none") {
                setAccount(null);
                setSecret("");
                set({ loginHint: "" });
              } else if (!seeding) seed(false);
            }}
          />
          {seeding && <Spinner aria-label="Creating account" className="size-3.5 text-muted-foreground" />}
        </Prop>
        {account && (
          <>
            <Prop label="Identifier">
              <span className="min-w-0 flex-1 truncate font-mono text-xs" title={account.identifier}>
                {account.identifier}
              </span>
              <CopyButton value={account.identifier} label="Copy identifier" className="size-6" />
            </Prop>
            <Prop label="Password">
              {/* Masked until hovered or focused */}
              <span tabIndex={0} className="group min-w-0 flex-1 truncate font-mono text-xs outline-none">
                <span className="group-hover:hidden group-focus:hidden">{"•".repeat(account.password.length)}</span>
                <span className="hidden group-hover:inline group-focus:inline">{account.password}</span>
              </span>
              <CopyButton value={account.password} label="Copy password" className="size-6" />
            </Prop>
          </>
        )}
      </PanelSection>

      <PanelSection title="Request" help="The authorization request the demo client sends with the preview client. prompt is login by default, so a remembered session never skips the draft.">
        <Prop label="acr_values">
          <Choice label="acr_values" className="w-40" none="none" value={launch.acrValues} options={[{ value: "faye:1fa" }, { value: "faye:2fa" }]} onChange={(acrValues) => set({ acrValues })} />
        </Prop>
        <Prop label="prompt">
          <Choice label="prompt" className="w-40" none="none" value={launch.prompt} options={[{ value: "login" }, { value: "consent" }]} onChange={(prompt) => set({ prompt })} />
        </Prop>
        <Prop label="IP address">
          <Input aria-label="Request IP" className={cn(denseMono, "w-40")} value={launch.ip} onChange={(e) => set({ ip: e.target.value })} placeholder="Browser IP" />
          <label className="flex shrink-0 items-center gap-2 text-xs whitespace-nowrap text-muted-foreground">
            <Switch label="High risk IP" checked={launch.ip === RISKY_IP} onChange={(on) => set({ ip: on ? RISKY_IP : "" })} />
            High risk
          </label>
        </Prop>
      </PanelSection>

      <PanelSection title="Authenticator" help="With TOTP creates the new user with TOTP already enrolled. Otherwise paste the secret from the Enroll TOTP screen to get the current code.">
        <Prop label="With TOTP">
          <Switch label="With TOTP" checked={!!account?.totpSecret} disabled={!account || seeding} onChange={(on) => seed(on)} />
          {!account && <span className="text-xs text-muted-foreground">New user only</span>}
        </Prop>
        <TotpField secret={secret} onChange={setSecret} />
      </PanelSection>

      <PanelSection title="Window" help="Where the demo client opens. Remembered in this browser.">
        <RadioGroup
          value={openIn}
          onValueChange={(v) => {
            const open = v as typeof openIn;
            setOpenIn(open);
            try {
              localStorage.setItem("faye.preview.open", open);
            } catch {
              // ignored: private windows can refuse storage
            }
          }}
          aria-label="Open in"
          className="grid grid-cols-2 gap-2"
        >
          {(["popup", "tab"] as const).map((v) => (
            <FieldLabel
              key={v}
              htmlFor={`open-in-${v}`}
              className="text-xs font-normal text-muted-foreground transition-colors hover:bg-muted/50 has-data-[state=checked]:border-accent-ui has-data-[state=checked]:bg-accent-ui/5 has-data-[state=checked]:font-medium has-data-[state=checked]:text-foreground has-data-[state=checked]:hover:bg-accent-ui/5 dark:has-data-[state=checked]:bg-accent-ui/10 [&>*]:data-[slot=field]:p-2"
            >
              <Field orientation="horizontal">
                <span className="grid flex-1 gap-1.5">
                  <WindowDiagram kind={v} on={openIn === v} />
                  {v === "popup" ? "Popup" : "New tab"}
                </span>
                <RadioGroupItem value={v} id={`open-in-${v}`} />
              </Field>
            </FieldLabel>
          ))}
        </RadioGroup>
      </PanelSection>

      {launchSlot &&
        createPortal(
          <>
            <span title={blocked ?? (running ? "Restarts in the preview window" : openIn === "popup" ? "Opens the demo client in a popup" : "Opens the demo client in a new tab")}>
              <Button size="sm" onClick={start} disabled={blocked !== null} className="h-7">
                {running ? <Spinner className="size-3.5" aria-hidden /> : <Play className="size-3.5" />}
                {blocked ?? (launching ? "Opening…" : running ? "Previewing…" : "Preview")}
              </Button>
            </span>
            <Help side="bottom">Runs the draft, not the live version, through the demo client.</Help>
          </>,
          launchSlot,
        )}

      <PanelSection
        title="Trace"
        help="Engine events of the preview run. When the run ends, it is compared with the draft’s enumerated paths."
      >
        {!run ? (
          <p className="text-xs text-muted-foreground">No run</p>
        ) : (
          <div className="grid text-xs @container">
            <div className="flex items-center gap-1.5 border-b border-border/60 pb-1.5 text-muted-foreground">
              <span>{stamp(run.events[0].at)}</span>
              <span className="font-mono" title={run.flowId}>{run.flowId.slice(0, 10)}</span>
              <CopyButton value={run.flowId} label="Copy flow ID" className="size-6" />
              <span className="ml-auto tabular-nums">
                {duration(run.events.at(-1)!.at - run.events[0].at)} · {run.events.length} events
              </span>
            </div>
            <ol aria-live="polite">
              {run.events.map((r, i) => {
                const e = r.event;
                const last = i === run.events.length - 1;
                const waiting = !run.end && last && e.event === "screen";
                const dot =
                  e.event === "end"
                    ? e.outcome === "succeed"
                      ? "var(--success)"
                      : "var(--danger)"
                    : e.event === "start"
                      ? "var(--accent-ui)"
                      : e.event === "option"
                        ? "var(--tone-violet)"
                        : e.event === "screen"
                          ? "var(--warning)"
                          : e.event === "hook"
                            ? "var(--tone-teal)"
                            : "var(--edge)";
                return (
                  <Fragment key={r.seq}>
                    {run.signIn && e.event === "start" && e.outcome === "consent" && (
                      <li aria-hidden className="flex items-center gap-2 py-1 text-muted-foreground">
                        <Separator className="flex-1 bg-border/60" />
                        <span className="font-sans text-xs">Consent</span>
                        <Separator className="flex-1 bg-border/60" />
                      </li>
                    )}
                    <li
                      className={cn(
                        "trace-in grid min-h-7 grid-cols-[--spacing(23)_--spacing(3)_--spacing(15)_minmax(0,1fr)_auto] items-center gap-2 border-b border-border/60 hover:bg-muted/50",
                        !run.end && last && "bg-muted/40",
                      )}
                    >
                      <span className="text-muted-foreground tabular-nums" title={`${stamp(r.at)}.${String(r.at % 1000).padStart(3, "0")}`}>
                        {timeOf(r.at)}
                      </span>
                      <span className="relative flex h-full items-center justify-center">
                        <span className="absolute inset-y-0 w-px bg-border/60" />
                        <span className={cn("relative size-1.5 rounded-full", waiting && "motion-safe:animate-pulse")} style={{ background: dot }} />
                      </span>
                      <Badge variant="outline" className="h-5 px-1.5 font-normal text-muted-foreground">
                        {e.event}
                      </Badge>
                      <span className="flex min-w-0 items-center gap-1">
                        <span className="truncate" title={describe(e)}>
                          {e.event === "start" ? (e.outcome ?? "") + " " + (e.id ?? "").slice(7, 15) : describe(e)}
                        </span>
                        {e.event === "end" && (
                          <>
                            {e.amr?.map((a) => (
                              <Chip key={a} mono className="h-4 px-1">
                                {a}
                              </Chip>
                            ))}
                            {e.acr && (
                              <Chip mono className="h-4 px-1">
                                {e.acr}
                              </Chip>
                            )}
                          </>
                        )}
                      </span>
                      <span className="hidden text-right text-muted-foreground tabular-nums @xs:inline">{i > 0 ? delta(r.at - run.events[i - 1].at) : ""}</span>
                    </li>
                  </Fragment>
                );
              })}
              {!run.end && (
                <li className="flex items-center gap-1.5 pt-1.5 text-muted-foreground">
                  <Spinner className="size-3" aria-hidden /> Waiting for input in the preview window
                </li>
              )}
            </ol>
          </div>
        )}
        {run?.signIn && <Expectation part="Sign in" end={run.signIn.end} summary={summaries[run.signIn.flowId] ?? null} />}
        {run?.end && <Expectation part={run.signIn ? "Consent" : null} end={run.end} summary={summaries[run.flowId] ?? null} />}
      </PanelSection>
    </div>
  );
}

/** How the run (or one part of it) ended, and whether that is what the Paths tab predicted for the way it went. */
function Expectation({ part, end, summary }: { part: string | null; end: TraceEvent; summary: PreviewRun | null }) {
  const succeeded = end.outcome === "succeed";
  return (
    <div className={cn("grid animate-in fade-in-0 slide-in-from-top-2 gap-1 rounded-md border p-2 text-xs", summary?.matches === false && "border-danger")}>
      <div className="flex items-center gap-1.5 font-medium">
        {succeeded ? <CircleCheck className="size-3.5 text-success" /> : <CircleX className="size-3.5 text-danger" />}
        {part && <span>{part}</span>}
        {succeeded ? (
          <span className="font-mono">
            {end.amr?.join("+") ?? ""} {end.acr ?? ""}
          </span>
        ) : (
          <span className="font-mono">{end.outcome}</span>
        )}
      </div>
      {!summary ? (
        <span className="text-muted-foreground">Comparing with enumerated paths…</span>
      ) : summary.matches ? (
        <span className="text-muted-foreground">
          Matches path <span className="font-mono">{summary.expected[0]?.path}</span>
        </span>
      ) : summary.expected.length === 0 ? (
        <span className="text-danger">Matches no enumerated path: the engine and the checker disagree.</span>
      ) : (
        <span className="text-danger">
          Expected{" "}
          {summary.expected.map((p) => (
            <span key={p.path} className="font-mono">
              {p.succeeds ? `${p.amr.join("+")} ${p.acr}` : "failure"}{" "}
            </span>
          ))}
          for this path.
        </span>
      )}
      {part !== "Sign in" && <span className="text-muted-foreground">The demo client also checks the issued token.</span>}
    </div>
  );
}

/** A small browser window, with the demo client (highlighted) in a popup over it or in a second tab. */
function WindowDiagram({ kind, on }: { kind: "popup" | "tab"; on: boolean }) {
  const hi = on ? "fill-accent-ui/15 stroke-accent-ui" : "fill-muted stroke-muted-foreground/50";
  return (
    <svg viewBox="0 0 64 40" aria-hidden className="w-full" strokeWidth={0.5}>
      <rect x={0.5} y={0.5} width={63} height={39} rx={3} className="fill-background stroke-border" />
      {kind === "popup" ? (
        <>
          <line x1={0.5} y1={7} x2={63.5} y2={7} className="stroke-border" />
          {[4, 7.5, 11].map((cx) => (
            <circle key={cx} cx={cx} cy={3.75} r={1} className="fill-border" />
          ))}
          <rect x={34} y={11} width={24} height={25} rx={2} className={hi} />
          <line x1={34} y1={15.5} x2={58} y2={15.5} className={on ? "stroke-accent-ui" : "stroke-muted-foreground/50"} />
        </>
      ) : (
        <>
          <line x1={0.5} y1={8} x2={63.5} y2={8} className="stroke-border" />
          <rect x={3} y={2.5} width={17} height={5.5} rx={1} className="fill-muted stroke-border" />
          <rect x={21} y={2.5} width={17} height={5.5} rx={1} className={hi} />
          <rect x={3} y={11} width={58} height={26} rx={1.5} className={hi} />
        </>
      )}
    </svg>
  );
}

function TotpField({ secret, onChange }: { secret: string; onChange: (s: string) => void }) {
  const [code, setCode] = useState<{ code: string; left: number } | null>(null);
  useEffect(() => {
    if (!secret) return;
    let live = true;
    const tick = () =>
      totp(secret)
        .then((c) => live && setCode({ code: c, left: remaining() }))
        .catch(() => live && setCode(null));
    tick();
    const timer = setInterval(tick, 1000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [secret]);
  const shown = secret ? code : null;
  return (
    <Prop label="Secret" align="start">
      <div className="grid min-w-0 flex-1 gap-1.5">
        <Input
          aria-label="TOTP secret"
          className={cn(denseMono, "w-full")}
          value={secret}
          onChange={(e) => onChange(e.target.value.trim())}
          placeholder="Secret from otp_enroll"
          spellCheck={false}
        />
        {shown && (
          <span className="flex items-center gap-2 text-xs">
            <span className="font-mono text-base tracking-widest" aria-label="Current code">
              {shown.code.slice(0, 3)} {shown.code.slice(3)}
            </span>
            <span className="text-muted-foreground">{shown.left}s</span>
            <CopyButton value={shown.code} label="Copy code" className="size-6" />
          </span>
        )}
      </div>
    </Prop>
  );
}
