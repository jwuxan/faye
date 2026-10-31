"use client";

import { Button } from "@faye/ui/components/button";
import { cn } from "@faye/ui/lib/utils";
import { CircleX } from "lucide-react";
import type { ReactNode } from "react";
import { useState } from "react";
import { api, type Catalog, type Settings as TenantSettings, type SettingsView, type Validation } from "../../../lib/api";
import { field, Switch } from "../../ui";

/** Tenant settings: MFA policy, which handlers and screens flows may use, and branding. */
export function Settings({
  tenant,
  view,
  catalog,
  onSaved,
  notify,
}: {
  tenant: string;
  view: SettingsView;
  catalog: Catalog | null;
  onSaved: (view: SettingsView) => void;
  notify: (kind: "ok" | "error", text: string) => void;
}) {
  const [s, setS] = useState<TenantSettings>(view.settings);
  const [problems, setProblems] = useState<string[]>([]);
  const branding = s.branding ?? {};
  const dirty = JSON.stringify(s) !== JSON.stringify(view.settings);
  const setBranding = (patch: Partial<TenantSettings["branding"]>) => {
    const next = { ...branding, ...patch };
    for (const k of Object.keys(patch) as (keyof typeof next)[]) if (patch[k] === undefined || patch[k] === "") delete next[k];
    setS({ ...s, branding: next });
  };

  const save = async () => {
    setProblems([]);
    const { status, body } = await api<SettingsView & Validation & { error?: string }>(`tenants/${tenant}/settings`, { method: "PUT", json: s });
    if (status === 200) {
      onSaved(body);
      notify("ok", "Settings saved");
    } else if (status === 422) setProblems(body.errors.map((e) => e.message));
    else setProblems([body.error ?? `HTTP ${status}`]);
  };

  return (
    <div className="h-full overflow-auto">
      <div className="max-w-[640px] px-8 py-6">
        <div className="flex items-center gap-2 pb-4">
          <h2 className="text-[14px] font-medium">Tenant settings</h2>
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className={cn("size-1.5 rounded-full", view.saved ? "bg-accent-ui" : "bg-border")} />
            {view.saved ? "overrides config" : "from config"}
          </span>
        </div>

        <Section title="Policy">
          <Row label="Require MFA" hint="Every sign in reaches faye:2fa">
            <Switch label="Require MFA" checked={s.mfaRequired} onChange={(mfaRequired) => setS({ ...s, mfaRequired })} />
          </Row>
          <Allowlist title="Handlers" all={catalog?.handlers.map((h) => h.name) ?? []} value={s.handlers} onChange={(handlers) => setS({ ...s, handlers })} />
          <Allowlist title="Screens" all={catalog?.screens.map((x) => x.type) ?? []} value={s.screens} onChange={(screens) => setS({ ...s, screens })} />
          <Row label="Egress" hint="Set by the operator">
            <span className="font-mono text-xs">{view.egress.join(", ") || "none"}</span>
          </Row>
        </Section>

        <Section title="Branding">
          <Row label="Name">
            <input className={cn(field, "w-64")} value={branding.name ?? ""} maxLength={64} placeholder="Acme" onChange={(e) => setBranding({ name: e.target.value })} />
          </Row>
          <Row label="Logo URL">
            <input className={cn(field, "w-64 font-mono text-xs")} value={branding.logoUrl ?? ""} placeholder="https://" onChange={(e) => setBranding({ logoUrl: e.target.value })} />
          </Row>
          <Row label="Primary color">
            <label className={cn(field, "flex w-36 items-center gap-2")}>
              <input type="color" aria-label="Primary color" className="size-4 cursor-pointer rounded-[3px] border-0 bg-transparent p-0" value={branding.primaryColor ?? "#171717"} onChange={(e) => setBranding({ primaryColor: e.target.value })} />
              <span className="font-mono text-xs">{branding.primaryColor ?? "default"}</span>
            </label>
            {branding.primaryColor && (
              <button type="button" className="text-xs text-muted-foreground hover:text-foreground" onClick={() => setBranding({ primaryColor: undefined })}>
                Reset
              </button>
            )}
          </Row>
          <Row label="Corner radius">
            <input
              type="number"
              min={0}
              max={24}
              aria-label="Corner radius"
              className={cn(field, "w-20")}
              value={branding.radius ?? ""}
              placeholder="10"
              onChange={(e) => setBranding({ radius: e.target.value === "" ? undefined : Number(e.target.value) })}
            />
            <span className="text-xs text-muted-foreground">px</span>
          </Row>
          <Row label="Preview" top>
            <Preview branding={branding} />
          </Row>
        </Section>

        {problems.length > 0 && (
          <ul className="mt-4 grid gap-px overflow-hidden rounded-md" role="alert">
            {problems.map((p, i) => (
              <li key={i} className="flex gap-2 bg-danger/8 px-3 py-2 text-xs">
                <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
                {p}
              </li>
            ))}
          </ul>
        )}

        <div className="mt-6 flex items-center gap-2">
          <Button onClick={save} disabled={!dirty} className="h-8 px-3">
            Save
          </Button>
          {dirty && (
            <Button variant="outline" className="h-8 px-3" onClick={() => setS(view.settings)}>
              Discard
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-t py-4">
      <h3 className="pb-2 text-[11px] font-medium tracking-[0.04em] text-muted-foreground uppercase">{title}</h3>
      <div className="grid gap-3">{children}</div>
    </section>
  );
}

function Row({ label, hint, children, top }: { label: string; hint?: string; children: ReactNode; top?: boolean }) {
  return (
    <div className={cn("grid grid-cols-[200px_1fr] gap-4", top ? "items-start" : "items-center")}>
      <span className="grid">
        <span className="text-[13px]">{label}</span>
        {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
      </span>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

function Allowlist({ title, all, value, onChange }: { title: string; all: string[]; value: string[] | null; onChange: (v: string[] | null) => void }) {
  return (
    <div className="grid grid-cols-[200px_1fr] items-start gap-4">
      <span className="grid pt-0.5">
        <span className="text-[13px]">{title}</span>
        <span className="text-xs text-muted-foreground">{value === null ? "all allowed" : `${value.length} of ${all.length} allowed`}</span>
      </span>
      <div className="flex flex-wrap gap-1">
        {all.map((name) => {
          const on = value === null || value.includes(name);
          return (
            <button
              key={name}
              type="button"
              aria-pressed={on}
              onClick={() => {
                const current = value ?? all;
                const next = on ? current.filter((v) => v !== name) : [...current, name];
                onChange(next.length === all.length ? null : next);
              }}
              className={cn(
                "h-6 rounded-[4px] border px-1.5 font-mono text-[11px] transition-colors",
                on ? "border-transparent bg-muted text-foreground" : "border-dashed text-muted-foreground line-through decoration-muted-foreground/50",
              )}
            >
              {name}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** How the sign in card's header and button look with this branding. */
function Preview({ branding }: { branding: TenantSettings["branding"] }) {
  const style: Record<string, string> = {};
  if (branding.primaryColor) style["--primary"] = branding.primaryColor;
  if (branding.radius !== undefined) style["--radius"] = `${branding.radius}px`;
  return (
    <div style={style} className="grid w-64 gap-2 rounded-[var(--radius)] border bg-card p-3">
      <span className="text-[13px] font-semibold">{branding.name || "Sign in"}</span>
      <span className="h-7 rounded-[calc(var(--radius)-2px)] border bg-background" />
      <span className="grid h-7 place-items-center rounded-[calc(var(--radius)-2px)] bg-primary text-xs text-primary-foreground">Continue</span>
    </div>
  );
}
