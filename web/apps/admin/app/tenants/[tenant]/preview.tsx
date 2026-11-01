"use client";

import { Button } from "@faye/ui/components/button";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, CircleX, ExternalLink, LoaderCircle, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { api, type PreviewRun, type PreviewView, type Seeded } from "../../../lib/api";
import { remaining, totp } from "../../../lib/totp";
import { describe, type LiveRun, type Recorded, type TraceEvent } from "../../../lib/trace";
import { Input } from "@faye/ui/components/input";
import { Choice, CopyButton, denseMono, Help, IconButton, Overline, Prop, Segmented, Switch } from "../../ui";

/** An IP in TEST-NET-3, which the local ip-reputation stub scores as high risk. */
const RISKY_IP = "203.0.113.7";

type Launch = { loginHint: string; acrValues: string; prompt: string; ip: string };
const EMPTY: Launch = { loginHint: "", acrValues: "", prompt: "", ip: "" };

/**
 * Runs the draft against a real OAuth client without publishing it: presets set the launch, Launch
 * opens the demo client with the tenant's preview client, and the run's trace streams in as it
 * happens (the block tree highlights it).
 */
export function Preview({
  tenant,
  demoClient,
  blocked,
  run,
  onEvent,
  fail,
}: {
  tenant: string;
  demoClient: string;
  /** Why the draft cannot run, or null. */
  blocked: string | null;
  run: LiveRun | null;
  onEvent: (r: Recorded) => void;
  fail: (e: unknown) => void;
}) {
  const [view, setView] = useState<PreviewView | null>(null);
  const [launch, setLaunch] = useState<Launch>(EMPTY);
  const [account, setAccount] = useState<Seeded | null>(null);
  const [secret, setSecret] = useState("");
  const [seeding, setSeeding] = useState(false);
  const [following, setFollowing] = useState<"connecting" | "open" | "lost">("connecting");
  // What the checker predicted, by flow: a sign in and the consent run that follows it are two
  const [summaries, setSummaries] = useState<Record<string, PreviewRun>>({});

  useEffect(() => {
    api<PreviewView>(`tenants/${tenant}/preview`)
      .then(({ body }) => {
        setView(body);
        setLaunch((l) => ({ ...l, ip: body.scenario.request.ip ?? "" }));
      })
      .catch(fail);
  }, [tenant, fail]);

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
    const win = window.open("about:blank", "faye-preview", "popup,width=480,height=820");
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
    }
  };

  if (!view) return <div className="p-4 text-muted-foreground">Loading…</div>;
  if (!view.enabled) {
    return (
      <div className="grid gap-2 p-4 text-sm">
        <h2 className="font-medium">Preview</h2>
        <p className="text-muted-foreground">
          Previews are off on this server. Start Faye with <code className="font-mono text-xs">FAYE_PREVIEW_ENABLED=true</code> to run drafts with the{" "}
          <code className="font-mono text-xs">preview-{tenant}</code> client.
        </p>
      </div>
    );
  }

  const set = (patch: Partial<Launch>) => setLaunch((l) => ({ ...l, ...patch }));
  return (
    <div className="grid content-start gap-5 p-4 text-sm">
      <header className="grid gap-1">
        <div className="flex items-center gap-2">
          <h2 className="font-medium">Preview</h2>
          <span className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
            <span className={cn("size-1.5 rounded-full", following === "open" ? "bg-success" : following === "lost" ? "bg-danger" : "bg-warning")} />
            {following === "open" ? "Following" : following === "lost" ? "Not following" : "Connecting…"}
          </span>
        </div>
        <p className="text-xs text-muted-foreground">
          Signs in with <span className="font-mono">preview-{tenant}</span>, which runs this draft. Nothing is published.
        </p>
      </header>

      <section className="grid gap-2" aria-label="Scenario">
        <div className="flex items-center gap-1">
          <Overline>Scenario</Overline>
          <Help>Who signs in and under what conditions. The account tab seeds a fresh preview account and passes it as login_hint; the switches set the request IP and acr_values.</Help>
          <IconButton
            icon={RotateCcw}
            label="Reset scenario"
            className="ml-auto size-6"
            onClick={() => {
              setLaunch(EMPTY);
              setAccount(null);
              setSecret("");
            }}
          />
        </div>
        <Segmented
          label="Account"
          value={account ? (account.totpSecret ? "totp" : "new") : "none"}
          options={[
            { value: "none", label: "No account" },
            { value: "new", label: "New user" },
            { value: "totp", label: "User with TOTP" },
          ]}
          onChange={(v) => {
            if (v === "none") {
              setAccount(null);
              setSecret("");
              set({ loginHint: "" });
            } else if (!seeding) seed(v === "totp");
          }}
        />
        <Prop label="High risk IP">
          <Switch label="High risk IP" checked={launch.ip === RISKY_IP} onChange={(on) => set({ ip: on ? RISKY_IP : "" })} />
          <span className="font-mono text-xs text-muted-foreground">{RISKY_IP}</span>
        </Prop>
        <Prop label="Requests MFA">
          <Switch label="Client requests MFA" checked={launch.acrValues === "faye:2fa"} onChange={(on) => set({ acrValues: on ? "faye:2fa" : "" })} />
          <span className="font-mono text-xs text-muted-foreground">acr_values=faye:2fa</span>
        </Prop>
        {account && (
          <dl className="grid grid-cols-[max-content_1fr_auto] items-center gap-x-2 gap-y-1 rounded-md border p-2 text-xs">
            <dt className="text-muted-foreground">Account</dt>
            <dd className="truncate font-mono" title={account.identifier}>
              {account.identifier}
            </dd>
            <CopyButton value={account.identifier} label="Copy identifier" className="size-6" />
            <dt className="text-muted-foreground">Password</dt>
            <dd className="truncate font-mono">{account.password}</dd>
            <CopyButton value={account.password} label="Copy password" className="size-6" />
          </dl>
        )}
        <p className="text-xs text-muted-foreground">Seeded accounts sign in only through preview clients.</p>
      </section>

      <section className="grid gap-2" aria-label="Launch">
        <div className="flex items-center gap-1">
          <Overline>Launch</Overline>
          <Help>The authorization request the demo client sends with the preview client. Launch opens it in a popup; the run uses this draft, not the published version.</Help>
        </div>
        <Row label="acr_values">
          <Choice label="acr_values" className="w-full" none="none" value={launch.acrValues} options={[{ value: "faye:1fa" }, { value: "faye:2fa" }]} onChange={(acrValues) => set({ acrValues })} />
        </Row>
        <Row label="prompt">
          <Choice label="prompt" className="w-full" none="default" value={launch.prompt} options={[{ value: "login" }, { value: "consent" }]} onChange={(prompt) => set({ prompt })} />
        </Row>
        <Row label="request.ip">
          <Input className={cn(denseMono, "w-full")} value={launch.ip} onChange={(e) => set({ ip: e.target.value })} placeholder="the browser's" />
        </Row>
        <span title={blocked ?? "Open the demo client with this draft"}>
          <Button size="sm" onClick={start} disabled={blocked !== null} className="w-full">
            <ExternalLink className="size-3.5" />
            {blocked ?? "Launch preview"}
          </Button>
        </span>
        <TotpField secret={secret} onChange={setSecret} />
      </section>

      <section className="grid gap-2" aria-label="Trace">
        <div className="flex items-center">
          <Overline>Trace</Overline>
          <Help>Each step of the preview run as the engine takes it. When the run ends, its path is checked against the paths the checker enumerated for this draft.</Help>
          {run && <span className="ml-auto font-mono text-xs text-muted-foreground">{run.flowId.slice(0, 10)}</span>}
        </div>
        {!run ? (
          <p className="text-xs text-muted-foreground">Launch a preview: each step appears here and lights up in the tree as it runs.</p>
        ) : (
          <ol className="grid gap-0.5 font-mono text-xs" aria-live="polite">
            {run.events.map((r) => (
              <li key={r.seq} className="trace-in grid grid-cols-[--spacing(15)_1fr] gap-2">
                <span className="text-muted-foreground">{r.event.event}</span>
                <span className="truncate" title={describe(r.event)}>
                  {r.event.event === "start" ? (r.event.outcome ?? "") + " " + (r.event.id ?? "").slice(7, 15) : describe(r.event)}
                </span>
              </li>
            ))}
            {!run.end && (
              <li className="flex items-center gap-1.5 pt-1 text-muted-foreground">
                <LoaderCircle className="size-3 animate-spin" /> waiting for the person in the preview window
              </li>
            )}
          </ol>
        )}
        {run?.signIn && <Expectation part="Sign in" end={run.signIn.end} summary={summaries[run.signIn.flowId] ?? null} />}
        {run?.end && <Expectation part={run.signIn ? "Consent" : null} end={run.end} summary={summaries[run.flowId] ?? null} />}
      </section>
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
        <span className="text-muted-foreground">Checking against the predicted paths…</span>
      ) : summary.matches ? (
        <span className="text-muted-foreground">
          As predicted: <span className="font-mono">{summary.expected[0]?.path}</span>
        </span>
      ) : summary.expected.length === 0 ? (
        <span className="text-danger">No path the checker enumerated makes the decisions and calls this run made.</span>
      ) : (
        <span className="text-danger">
          Predicted{" "}
          {summary.expected.map((p) => (
            <span key={p.path} className="font-mono">
              {p.succeeds ? `${p.amr.join("+")} ${p.acr}` : "failure"}{" "}
            </span>
          ))}
          for this path.
        </span>
      )}
      {part !== "Sign in" && <span className="text-muted-foreground">The demo client window shows the issued token and checks it too.</span>}
    </div>
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
    <Row label="TOTP secret">
      <div className="grid gap-1">
        <Input
          className={cn(denseMono, "w-full")}
          value={secret}
          onChange={(e) => onChange(e.target.value.trim())}
          placeholder="paste from otp_enroll"
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
    </Row>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid grid-cols-[--spacing(26)_1fr] items-start gap-2">
      <span className="pt-1 font-mono text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}
