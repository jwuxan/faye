"use client";

import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Checkbox } from "@faye/ui/components/checkbox";
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSeparator, FieldSet } from "@faye/ui/components/field";
import { Input } from "@faye/ui/components/input";
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@faye/ui/components/input-group";
import { Slider } from "@faye/ui/components/slider";
import { Switch } from "@faye/ui/components/switch";
import { cn } from "@faye/ui/lib/utils";
import { CircleX, Lock, RotateCcw } from "lucide-react";
import { type ReactNode, useState } from "react";
import { api, type Branding, type Settings as TenantSettings, type SettingsView, type Validation } from "../../../lib/api";
import { PageHeader } from "../../shell";
import { useDraft } from "./draft";

/** Edits a copy of the tenant's settings; Save sends them all, and the server refuses a save the live flow would fail. */
function useSettingsForm() {
  const d = useDraft();
  const view = d.settings;
  const [edited, setEdited] = useState<TenantSettings | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const s = edited ?? view?.settings ?? null;
  const dirty = !!view && !!edited && JSON.stringify(edited) !== JSON.stringify(view.settings);

  const save = async () => {
    if (!edited) return;
    setProblems([]);
    setSaving(true);
    try {
      const { status, body } = await api<SettingsView & Validation & { error?: string }>(`tenants/${d.tenant}/settings`, { method: "PUT", json: edited });
      if (status === 200) {
        d.setSettings(body);
        setEdited(null);
        d.notify("ok", "Settings saved");
      } else if (status === 422) setProblems(body.errors.map((e) => e.message));
      else setProblems([body.error ?? `HTTP ${status}`]);
    } catch (e) {
      d.fail(e);
    }
    setSaving(false);
  };
  const discard = () => {
    setEdited(null);
    setProblems([]);
  };
  return { d, view, s, set: setEdited, dirty, save, discard, problems, saving };
}

type Form = ReturnType<typeof useSettingsForm>;

function SettingsFrame({ title, description, form, children }: { title: string; description: string; form: Form; children: ReactNode }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={form.d.tenant} title={title} />
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="mx-auto grid max-w-4xl gap-8 px-6 py-8 pb-24">
          <div className="grid gap-1">
            <div className="flex items-center gap-2">
              <h1 className="text-[20px] leading-7 font-semibold tracking-tight">{title}</h1>
              {form.view && (
                <Badge variant="outline" className="gap-1.5 font-normal text-muted-foreground">
                  <span className={cn("size-1.5 rounded-full", form.view.saved ? "bg-accent-ui" : "bg-border")} />
                  {form.view.saved ? "saved in the console" : "from server config"}
                </Badge>
              )}
            </div>
            <p className="text-muted-foreground">{description}</p>
          </div>
          {form.s ? children : <p className="text-muted-foreground">Loading…</p>}
          {form.problems.length > 0 && (
            <ul className="grid gap-px overflow-hidden rounded-md" role="alert">
              {form.problems.map((p, i) => (
                <li key={i} className="flex gap-2 bg-danger/8 px-3 py-2 text-xs">
                  <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
                  {p}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      <div
        className={cn(
          "flex h-14 shrink-0 items-center gap-2 border-t bg-background px-6 transition-[margin,opacity] duration-200",
          form.dirty ? "opacity-100" : "pointer-events-none -mb-14 opacity-0",
        )}
        aria-hidden={!form.dirty}
      >
        <span className="text-[13px] text-muted-foreground">Unsaved changes</span>
        <Button variant="outline" size="sm" className="ml-auto" onClick={form.discard} tabIndex={form.dirty ? 0 : -1}>
          Discard
        </Button>
        <Button size="sm" onClick={form.save} disabled={form.saving} tabIndex={form.dirty ? 0 : -1}>
          {form.saving ? "Saving…" : "Save changes"}
        </Button>
      </div>
    </div>
  );
}

/** Tenant policy: MFA, which handlers and screens flows may use, and the egress the operator granted. */
export function GeneralSettings() {
  const form = useSettingsForm();
  const { s, set, d, view } = form;
  return (
    <SettingsFrame title="General" description="Policy every flow of this tenant is checked against. A save is refused if the live version would fail under it." form={form}>
      {s && (
        <FieldGroup className="gap-8">
          <FieldSet>
            <FieldLegend>Sign in policy</FieldLegend>
            <Field orientation="horizontal" className="rounded-lg border p-4">
              <FieldContent>
                <FieldLabel htmlFor="mfa">Require MFA</FieldLabel>
                <FieldDescription className="text-xs">Every successful sign in must reach faye:2fa: two verified factors.</FieldDescription>
              </FieldContent>
              <Switch id="mfa" checked={s.mfaRequired} onCheckedChange={(mfaRequired) => set({ ...s, mfaRequired })} />
            </Field>
          </FieldSet>
          <FieldSeparator />
          <Allowlist
            title="Handlers"
            description="Built in handlers flows may call. The checker refuses a flow that uses one not allowed."
            items={d.catalog?.handlers.map((h) => ({ name: h.name, note: h.amr.filter((a) => a !== "user").join(" · ") || (h.bindsSubject ? "binds subject" : "") })) ?? []}
            value={s.handlers}
            onChange={(handlers) => set({ ...s, handlers })}
          />
          <FieldSeparator />
          <Allowlist
            title="Screens"
            description="Typed screens flows may show."
            items={d.catalog?.screens.map((x) => ({ name: x.type, note: x.options.join(" · ") })) ?? []}
            value={s.screens}
            onChange={(screens) => set({ ...s, screens })}
          />
          <FieldSeparator />
          <FieldSet>
            <FieldLegend className="flex items-center gap-2">
              Egress
              <Lock className="size-3.5 text-muted-foreground" />
            </FieldLegend>
            <FieldDescription className="text-xs">
              Named HTTP targets this tenant’s hooks may call. Set by the operator in <code className="font-mono">faye.tenants.{d.tenant}.egress</code>, so it cannot be changed here.
            </FieldDescription>
            <div className="flex flex-wrap gap-1.5">
              {(view?.egress.length ?? 0) > 0 ? (
                view!.egress.map((t) => (
                  <Badge key={t} variant="secondary" className="font-mono font-normal">
                    {t}
                  </Badge>
                ))
              ) : (
                <span className="text-xs text-muted-foreground">none</span>
              )}
            </div>
          </FieldSet>
        </FieldGroup>
      )}
    </SettingsFrame>
  );
}

function Allowlist({
  title,
  description,
  items,
  value,
  onChange,
}: {
  title: string;
  description: string;
  items: { name: string; note: string }[];
  value: string[] | null;
  onChange: (v: string[] | null) => void;
}) {
  const all = items.map((i) => i.name);
  const allowed = value ?? all;
  return (
    <FieldSet>
      <div className="flex items-center gap-2">
        <FieldLegend className="mb-0">{title}</FieldLegend>
        <span className="text-xs text-muted-foreground">{value === null ? "all allowed" : `${value.length} of ${all.length} allowed`}</span>
        {value !== null && (
          <Button variant="ghost" size="xs" className="ml-auto text-muted-foreground" onClick={() => onChange(null)}>
            <RotateCcw />
            Allow all
          </Button>
        )}
      </div>
      <FieldDescription className="text-xs">{description}</FieldDescription>
      <div className="grid overflow-hidden rounded-lg border sm:grid-cols-2">
        {items.map((item, i) => {
          const on = allowed.includes(item.name);
          const id = `${title}-${item.name}`;
          return (
            <Field
              key={item.name}
              orientation="horizontal"
              className={cn("gap-3 px-3 py-2.5 hover:bg-accent/50", i % 2 === 0 && "sm:border-r", i < items.length - (items.length % 2 === 0 ? 2 : 1) && "border-b")}
            >
              <Checkbox
                id={id}
                checked={on}
                onCheckedChange={(c) => {
                  const next = c ? [...allowed, item.name] : allowed.filter((v) => v !== item.name);
                  onChange(next.length === all.length ? null : next);
                }}
              />
              <FieldContent className="min-w-0 gap-0.5">
                <FieldLabel htmlFor={id} className={cn("font-mono text-xs font-normal", !on && "text-muted-foreground line-through")}>
                  {item.name}
                </FieldLabel>
                {item.note && <span className="truncate font-mono text-[11px] text-muted-foreground">{item.note}</span>}
              </FieldContent>
            </Field>
          );
        })}
      </div>
    </FieldSet>
  );
}

const DEFAULT_RADIUS = 10;

/** How the tenant's login screens look: name, logo, color and corners, with a live preview. */
export function BrandingSettings() {
  const form = useSettingsForm();
  const { s, set } = form;
  const branding: Branding = s?.branding ?? {};
  const patch = (p: Partial<Branding>) => {
    if (!s) return;
    const next = { ...branding, ...p };
    for (const k of Object.keys(p) as (keyof Branding)[]) if (p[k] === undefined || p[k] === "") delete next[k];
    set({ ...s, branding: next });
  };
  const logoProblem = branding.logoUrl && !branding.logoUrl.startsWith("https://") ? "Logos load over https only" : null;
  return (
    <SettingsFrame title="Branding" description="The login screens of this tenant: its name, logo, primary color and corner radius. Screens stay typed templates; only these change." form={form}>
      {s && (
        <div className="grid items-start gap-8 lg:grid-cols-[1fr_340px]">
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="brand-name">Name</FieldLabel>
              <Input id="brand-name" value={branding.name ?? ""} maxLength={64} placeholder="Acme" onChange={(e) => patch({ name: e.target.value })} />
              <FieldDescription className="text-xs">Shown at the top of every screen. Empty shows the screen’s own title.</FieldDescription>
            </Field>
            <Field data-invalid={logoProblem !== null}>
              <FieldLabel htmlFor="brand-logo">Logo URL</FieldLabel>
              <Input
                id="brand-logo"
                className="font-mono text-xs md:text-xs"
                value={branding.logoUrl ?? ""}
                placeholder="https://"
                aria-invalid={logoProblem !== null}
                onChange={(e) => patch({ logoUrl: e.target.value })}
              />
              <FieldDescription className={cn("text-xs", logoProblem && "text-danger")}>{logoProblem ?? "A square image, shown at 32 px."}</FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="brand-color">Primary color</FieldLabel>
              <InputGroup className="w-48">
                <InputGroupAddon>
                  <input
                    type="color"
                    aria-label="Pick primary color"
                    className="size-5 cursor-pointer rounded-[4px] border-0 bg-transparent p-0 [&::-webkit-color-swatch]:rounded-[4px] [&::-webkit-color-swatch]:border-0 [&::-webkit-color-swatch-wrapper]:p-0"
                    value={branding.primaryColor ?? "#171717"}
                    onChange={(e) => patch({ primaryColor: e.target.value })}
                  />
                </InputGroupAddon>
                <InputGroupInput
                  id="brand-color"
                  className="font-mono text-xs md:text-xs"
                  value={branding.primaryColor ?? ""}
                  placeholder="default"
                  maxLength={7}
                  onChange={(e) => patch({ primaryColor: e.target.value.toLowerCase() })}
                />
                {branding.primaryColor && (
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton size="icon-xs" aria-label="Reset color" onClick={() => patch({ primaryColor: undefined })}>
                      <RotateCcw />
                    </InputGroupButton>
                  </InputGroupAddon>
                )}
              </InputGroup>
              <FieldDescription className="text-xs">Buttons and focus rings. #rrggbb.</FieldDescription>
            </Field>
            <Field>
              <div className="flex items-center">
                <FieldLabel htmlFor="brand-radius">Corner radius</FieldLabel>
                <span className="ml-auto font-mono text-xs text-muted-foreground">{branding.radius ?? DEFAULT_RADIUS} px</span>
              </div>
              <Slider
                id="brand-radius"
                min={0}
                max={24}
                step={1}
                value={[branding.radius ?? DEFAULT_RADIUS]}
                onValueChange={([radius]) => patch({ radius: radius === DEFAULT_RADIUS ? undefined : radius })}
                className="py-1.5"
              />
              <FieldDescription className="text-xs">Cards, fields and buttons; 0 is square.</FieldDescription>
            </Field>
          </FieldGroup>
          <div className="grid gap-2 lg:sticky lg:top-0">
            <span className="text-xs font-medium text-muted-foreground">Preview</span>
            <LoginPreview branding={branding} />
          </div>
        </div>
      )}
    </SettingsFrame>
  );
}

/** The identifier screen as the Login UI draws it, with this branding. */
function LoginPreview({ branding }: { branding: Branding }) {
  const radius = branding.radius ?? DEFAULT_RADIUS;
  const style = {
    "--primary": branding.primaryColor ?? undefined,
    "--primary-foreground": branding.primaryColor ? "#ffffff" : undefined,
    "--radius": `${radius}px`,
  } as React.CSSProperties;
  const logo = branding.logoUrl?.startsWith("https://") ? branding.logoUrl : null;
  return (
    <div className="grid place-items-center rounded-lg border bg-surface-2 bg-[radial-gradient(var(--border)_1px,transparent_1px)] [background-size:16px_16px] px-6 py-10">
      <div style={style} className="grid w-full max-w-[280px] gap-4 rounded-[var(--radius)] border bg-card p-5 shadow-sm">
        <div className="grid gap-3">
          {logo ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={logo} alt="" className="size-8 rounded-[calc(var(--radius)/2)] object-contain" />
          ) : (
            <span className="grid size-8 place-items-center rounded-[calc(var(--radius)/2)] bg-primary text-[13px] font-semibold text-primary-foreground">
              {(branding.name ?? "S").slice(0, 1).toUpperCase()}
            </span>
          )}
          <div className="grid gap-0.5">
            <span className="text-[15px] font-semibold">{branding.name ? `Sign in to ${branding.name}` : "Sign in"}</span>
            <span className="text-xs text-muted-foreground">Enter your email to continue</span>
          </div>
        </div>
        <div className="grid gap-1.5">
          <span className="text-xs font-medium">Email</span>
          <span className="flex h-8 items-center rounded-[calc(var(--radius)-2px)] border bg-background px-2.5 text-xs text-muted-foreground">alice@example.com</span>
        </div>
        <span className="grid h-8 place-items-center rounded-[calc(var(--radius)-2px)] bg-primary text-xs font-medium text-primary-foreground">Continue</span>
      </div>
    </div>
  );
}
