"use client";

import { Button } from "@faye/ui/components/button";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, CircleX, Copy, ExternalLink, LoaderCircle, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { api, type PreviewRun, type PreviewView, type Seeded } from "../../../lib/api";
import { remaining, totp } from "../../../lib/totp";
import { describe, type LiveRun, type Recorded } from "../../../lib/trace";
import { field, Select } from "../../ui";

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
  const [summary, setSummary] = useState<PreviewRun | null>(null);

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
      .then(({ body }) => setSummary(body))
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
      <div className="grid gap-2 p-4 text-[13px]">
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
    <div className="grid content-start gap-5 p-4 text-[13px]">
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
        <h3 className="text-xs font-medium text-muted-foreground">Scenario</h3>
        <div className="flex flex-wrap gap-1.5">
          <Preset onClick={() => seed(false)} disabled={seeding}>
            New user
          </Preset>
          <Preset onClick={() => seed(true)} disabled={seeding}>
            User with TOTP
          </Preset>
          <Preset onClick={() => set({ ip: RISKY_IP })} active={launch.ip === RISKY_IP}>
            High risk IP
          </Preset>
          <Preset onClick={() => set({ acrValues: "faye:2fa" })} active={launch.acrValues === "faye:2fa"}>
            Client requests MFA
          </Preset>
          <button
            type="button"
            aria-label="Reset scenario"
            title="Reset scenario"
            onClick={() => {
              setLaunch(EMPTY);
              setAccount(null);
              setSecret("");
            }}
            className="grid size-7 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <RotateCcw className="size-3.5" />
          </button>
        </div>
        {account && (
          <dl className="grid grid-cols-[max-content_1fr_auto] items-center gap-x-2 gap-y-1 rounded-md border p-2 text-xs">
            <dt className="text-muted-foreground">Account</dt>
            <dd className="truncate font-mono" title={account.identifier}>
              {account.identifier}
            </dd>
            <CopyButton text={account.identifier} label="Copy identifier" />
            <dt className="text-muted-foreground">Password</dt>
            <dd className="truncate font-mono">{account.password}</dd>
            <CopyButton text={account.password} label="Copy password" />
          </dl>
        )}
        <p className="text-xs text-muted-foreground">Seeded accounts sign in only through preview clients.</p>
      </section>

      <section className="grid gap-2" aria-label="Launch">
        <h3 className="text-xs font-medium text-muted-foreground">Launch</h3>
        <Row label="login_hint">
          <input className={cn(field, "w-full font-mono")} value={launch.loginHint} onChange={(e) => set({ loginHint: e.target.value })} placeholder="alice@example.com" />
        </Row>
        <Row label="acr_values">
          <Select className="w-full font-mono" value={launch.acrValues} onChange={(e) => set({ acrValues: e.target.value })}>
            <option value="">none</option>
            <option value="faye:1fa">faye:1fa</option>
            <option value="faye:2fa">faye:2fa</option>
          </Select>
        </Row>
        <Row label="prompt">
          <Select className="w-full font-mono" value={launch.prompt} onChange={(e) => set({ prompt: e.target.value })}>
            <option value="">default</option>
            <option value="login">login</option>
            <option value="consent">consent</option>
          </Select>
        </Row>
        <Row label="request.ip">
          <input className={cn(field, "w-full font-mono")} value={launch.ip} onChange={(e) => set({ ip: e.target.value })} placeholder="the browser's" />
        </Row>
        <span title={blocked ?? "Open the demo client with this draft"}>
          <Button onClick={start} disabled={blocked !== null} className="h-8 w-full gap-1.5">
            <ExternalLink className="size-3.5" />
            {blocked ?? "Launch preview"}
          </Button>
        </span>
        <TotpField secret={secret} onChange={setSecret} />
      </section>

      <section className="grid gap-2" aria-label="Trace">
        <h3 className="flex items-center text-xs font-medium text-muted-foreground">
          Trace
          {run && <span className="ml-auto font-mono font-normal">{run.flowId.slice(0, 10)}</span>}
        </h3>
        {!run ? (
          <p className="text-xs text-muted-foreground">Launch a preview: each step appears here and lights up in the tree as it runs.</p>
        ) : (
          <ol className="grid gap-0.5 font-mono text-xs" aria-live="polite">
            {run.events.map((r) => (
              <li key={r.seq} className="grid grid-cols-[3.75rem_1fr] gap-2">
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
        {run?.end && <Expectation run={run} summary={summary?.flowId === run.flowId ? summary : null} />}
      </section>
    </div>
  );
}

/** How the run ended, and whether that is what the Paths tab predicted for the way it went. */
function Expectation({ run, summary }: { run: LiveRun; summary: PreviewRun | null }) {
  const end = run.end!;
  const succeeded = end.outcome === "succeed";
  return (
    <div className={cn("grid gap-1 rounded-md border p-2 text-xs", summary?.matches === false && "border-danger")}>
      <div className="flex items-center gap-1.5 font-medium">
        {succeeded ? <CircleCheck className="size-3.5 text-success" /> : <CircleX className="size-3.5 text-danger" />}
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
      <span className="text-muted-foreground">The demo client window shows the issued token and checks it too.</span>
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
        <input
          className={cn(field, "w-full font-mono")}
          value={secret}
          onChange={(e) => onChange(e.target.value.trim())}
          placeholder="paste from otp_enroll"
          spellCheck={false}
        />
        {shown && (
          <span className="flex items-center gap-2 text-xs">
            <span className="font-mono text-[15px] tracking-widest" aria-label="Current code">
              {shown.code.slice(0, 3)} {shown.code.slice(3)}
            </span>
            <span className="text-muted-foreground">{shown.left}s</span>
            <CopyButton text={shown.code} label="Copy code" />
          </span>
        )}
      </div>
    </Row>
  );
}

function Preset({ children, onClick, active, disabled }: { children: string; onClick: () => void; active?: boolean; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      className={cn(
        "h-7 rounded-md border px-2 text-xs transition-colors hover:bg-accent disabled:opacity-50",
        active && "border-accent-ui bg-accent-ui/8 text-accent-ui",
      )}
    >
      {children}
    </button>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid grid-cols-[6.5rem_1fr] items-start gap-2">
      <span className="pt-1 font-mono text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

function CopyButton({ text, label }: { text: string; label: string }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={() => navigator.clipboard?.writeText(text)}
      className="grid size-6 place-items-center rounded text-muted-foreground hover:bg-accent hover:text-foreground"
    >
      <Copy className="size-3.5" />
    </button>
  );
}
